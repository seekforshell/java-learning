# 业务场景

首先明确kafka的消息传输场景，在0.11之前，Kafka的消息场景是最少一次，但这样无法解决消息重复的问题，为了实现精确一次传输语义(EOS)，kafka引入了幂等性和事务两种方案，用来解决读写数据的一致性问题。

kafka幂等性的限制性在于只能对单topic的单partition有效，因为幂等性通过pid和seq保持消息的有序性和唯一性。

## 事务解决问题



事务可以解决同一个消费者多topic写入问题



# 事务消息



```
1. Atomicity: A consumer's *application* should not be exposed to messages from uncommitted transactions.
原子性，即未执行事务级别情况下，未提交的事务消费者无法感知
2. Durability: The broker cannot lose any committed transactions.
持久性
3. Ordering: A transaction-aware consumer should see transactions in the original transaction-order within each partition.
有序性。多个事务有序性同样对设置了感知事务的消费者也同样有序
4. Interleaving: Each partition should be able to accept messages from both transactional and non-transactional producers
交叉，就是说存在事务生产者和非事务生产者时，两者并不冲突
5. There should be no duplicate messages within transactions.

```



对于上面的概念可以结合下图理解：

![img](/Users/yujingzhi/work/git/java-learning/system/message/kafka/images/t1.png)



# 事务流程（生产者视角）

## 发起事务



producer发起事务是由客户端的事务管理器触发的，主要是发送InitProducerIdRequest，在刚开始启动事务时由于协调者不存在会触发FindCoordiator的请求，根据leastLoadedNode（请求数量最少）来选择一个broker作为协调者。

```java
synchronized TransactionalRequestResult initializeTransactions(ProducerIdAndEpoch producerIdAndEpoch) {
    boolean isEpochBump = producerIdAndEpoch != ProducerIdAndEpoch.NONE;
    return handleCachedTransactionRequestResult(() -> {
        // If this is an epoch bump, we will transition the state as part of handling the EndTxnRequest
        if (!isEpochBump) {
            transitionTo(State.INITIALIZING);
            log.info("Invoking InitProducerId for the first time in order to acquire a producer ID");
        } else {
            log.info("Invoking InitProducerId with current producer ID and epoch {} in order to bump the epoch", producerIdAndEpoch);
        }
        InitProducerIdRequestData requestData = new InitProducerIdRequestData()
                .setTransactionalId(transactionalId)
                .setTransactionTimeoutMs(transactionTimeoutMs)
                .setProducerId(producerIdAndEpoch.producerId)
                .setProducerEpoch(producerIdAndEpoch.epoch);
        InitProducerIdHandler handler = new InitProducerIdHandler(new InitProducerIdRequest.Builder(requestData),
                isEpochBump);
        enqueueRequest(handler);
        return handler.result;
    }, State.INITIALIZING);
}
```



协调者受到InitProducerIdRequest会根据事务id的hash值取内部topic __transaction_state来选择一个partition作为事务状态的维护者。

此外会写入事务日志，跟其他topic的日志数据一样。

事务日志存储哪些数据呢？

上代码：

kafka.coordinator.transaction.TransactionStateManager#appendTransactionToLog

从如下代码分析，存储到日志中的主要包括事务Id和事务状态元数据。

```
producerId: Long, 生产者id
lastProducerId: Long, 
producerEpoch: Short, 任期
lastProducerEpoch: Short,
txnTimeoutMs: Int, 
txnState: TransactionState, 事务状态
topicPartitions: immutable.Set[TopicPartition], 参与到事务中的topic和partition列表
txnStartTimestamp: Long,
txnLastUpdateTimestamp: Long
```

```scala
  def appendTransactionToLog(transactionalId: String,
                             coordinatorEpoch: Int,
                             newMetadata: TxnTransitMetadata,
                             responseCallback: Errors => Unit,
                             retryOnError: Errors => Boolean = _ => false): Unit = {

    // generate the message for this transaction metadata
    val keyBytes = TransactionLog.keyToBytes(transactionalId)
    val valueBytes = TransactionLog.valueToBytes(newMetadata)
    val timestamp = time.milliseconds()

    val records = MemoryRecords.withRecords(TransactionLog.EnforcedCompressionType, new SimpleRecord(timestamp, keyBytes, valueBytes))
    val topicPartition = new TopicPartition(Topic.TRANSACTION_STATE_TOPIC_NAME, partitionFor(transactionalId))
    val recordsPerPartition = Map(topicPartition -> records)
    
		......
    // 写日志的内容在recordsPerPartition中
    replicaManager.appendRecords(
      newMetadata.txnTimeoutMs.toLong,
      TransactionLog.EnforcedRequiredAcks,
      internalTopicsAllowed = true,
      origin = AppendOrigin.Coordinator,
      recordsPerPartition,
      updateCacheCallback,
      delayedProduceLock = Some(stateLock.readLock))

    ......
   }
```



## 开始事务

开始事务仅仅会将本地事务管理器的状态置为IN_TRANSACTION

## 发送数据

在事务交互完成之后，生产者就可以发送数据了。

在发送数据流程中，会将涉及的partition加入到事务管理器的bookkeeper中和newPartitionsInTransaction成员中。其中bookKeeper用来保证数据发送的有序性，保证数据不会重复，newPartitionsInTransaction是一个集合最终会发送到事务协调者写入事务日志。

```java
public synchronized void maybeAddPartitionToTransaction(TopicPartition topicPartition) {
  if (isPartitionAdded(topicPartition) || isPartitionPendingAdd(topicPartition))
  	return;

  log.debug("Begin adding new partition {} to transaction", topicPartition);
  topicPartitionBookkeeper.addPartition(topicPartition);
  newPartitionsInTransaction.add(topicPartition);
}
```



## 消费者偏移加入事务中

这个是相对于生产者端的消费者而言的，消费者消费topic-A数据并将其写入topic-B数据，那么此时的消费位置也需要加入事务中。核心代码如下：

AddOffsetsToTxnRequest的数据包括事务ID、生产者id、epoch和消费者组信息

```java
public synchronized TransactionalRequestResult sendOffsetsToTransaction(final Map<TopicPartition, OffsetAndMetadata> offsets,
                                                                        final ConsumerGroupMetadata groupMetadata) {
    ensureTransactional();
    maybeFailWithError();
    if (currentState != State.IN_TRANSACTION)
        throw new KafkaException("Cannot send offsets to transaction either because the producer is not in an " +
                "active transaction");

    log.debug("Begin adding offsets {} for consumer group {} to transaction", offsets, groupMetadata);
    AddOffsetsToTxnRequest.Builder builder = new AddOffsetsToTxnRequest.Builder(transactionalId,
            producerIdAndEpoch.producerId, producerIdAndEpoch.epoch, groupMetadata.groupId());
    AddOffsetsToTxnHandler handler = new AddOffsetsToTxnHandler(builder, offsets, groupMetadata);
    enqueueRequest(handler);
    return handler.result;
}
```

事务协调器在接收到请求后，核心处理逻辑如下：

这里要说明的是记录消费者偏移加入到事务其实和普通topic的没有实质区别，也只是使用了内部topic，名称为__topic __consumer_offset。

```scala
def handleAddOffsetsToTxnRequest(request: RequestChannel.Request): Unit = {
  ensureInterBrokerVersion(KAFKA_0_11_0_IV0)
  val addOffsetsToTxnRequest = request.body[AddOffsetsToTxnRequest]
  val transactionalId = addOffsetsToTxnRequest.transactionalId
  val groupId = addOffsetsToTxnRequest.consumerGroupId
  // 获取消费者组对应的partition
  val offsetTopicPartition = new TopicPartition(GROUP_METADATA_TOPIC_NAME, groupCoordinator.partitionFor(groupId))

  if (!authorize(request, WRITE, TRANSACTIONAL_ID, transactionalId))
    sendResponseMaybeThrottle(request, requestThrottleMs =>
      new AddOffsetsToTxnResponse(requestThrottleMs, Errors.TRANSACTIONAL_ID_AUTHORIZATION_FAILED))
  else if (!authorize(request, READ, GROUP, groupId))
    sendResponseMaybeThrottle(request, requestThrottleMs =>
      new AddOffsetsToTxnResponse(requestThrottleMs, Errors.GROUP_AUTHORIZATION_FAILED))
  else {
    def sendResponseCallback(error: Errors): Unit = {
      def createResponse(requestThrottleMs: Int): AbstractResponse = {
        val responseBody: AddOffsetsToTxnResponse = new AddOffsetsToTxnResponse(requestThrottleMs, error)
        trace(s"Completed $transactionalId's AddOffsetsToTxnRequest for group $groupId on partition " +
          s"$offsetTopicPartition: errors: $error from client ${request.header.clientId}")
        responseBody
      }
      sendResponseMaybeThrottle(request, createResponse)
    }

    // 写入事务日志
    txnCoordinator.handleAddPartitionsToTransaction(transactionalId,
      addOffsetsToTxnRequest.producerId,
      addOffsetsToTxnRequest.producerEpoch,
      Set(offsetTopicPartition),
      sendResponseCallback)
  }
}
```



## 提交事务

主要是向事务协调者发送结束事务的请求，包括事务id、pid、epoch和事务状态。

```java
private TransactionalRequestResult beginCompletingTransaction(TransactionResult transactionResult) {
    if (!newPartitionsInTransaction.isEmpty())
        enqueueRequest(addPartitionsToTransactionHandler());

    // If the error is an INVALID_PRODUCER_ID_MAPPING error, the server will not accept an EndTxnRequest, so skip
    // directly to InitProducerId. Otherwise, we must first abort the transaction, because the producer will be
    // fenced if we directly call InitProducerId.
    if (!(lastError instanceof InvalidPidMappingException)) {
        EndTxnRequest.Builder builder = new EndTxnRequest.Builder(
                new EndTxnRequestData()
                        .setTransactionalId(transactionalId)
                        .setProducerId(producerIdAndEpoch.producerId)
                        .setProducerEpoch(producerIdAndEpoch.epoch)
                        .setCommitted(transactionResult.id));

      	// 发送结束事务标记
        EndTxnHandler handler = new EndTxnHandler(builder);
        enqueueRequest(handler);
        if (!shouldBumpEpoch()) {
            return handler.result;
        }
    }

    return initializeTransactions(this.producerIdAndEpoch);
}
```



协调者受到请求后会将生产者对应的事务分区（事务topic相应的分区）日志追加一条日志，写入事务状态日志。

事务协调者在收到结束事务的请求后会回调以下方法

kafka.coordinator.transaction.TransactionMarkerChannelManager#addTxnMarkersToSend

入参包括事务id，事务结果（是commit还是abort）、事务元数据、事务状态转变元数据。

其中事务元数据包含了所有涉及到的事务的topic-partition，可能是业务topic-partition也可能是消费者偏移这样的内部topic。

这些都会发送到各个leader partition写入相应broker的日志中。

```scala
def addTxnMarkersToSend(transactionalId: String,
                        coordinatorEpoch: Int,
                        txnResult: TransactionResult,
                        txnMetadata: TransactionMetadata,
                        newMetadata: TxnTransitMetadata): Unit = {

  def appendToLogCallback(error: Errors): Unit = {
    error match {
      case Errors.NONE =>
        trace(s"Completed sending transaction markers for $transactionalId as $txnResult")

        txnStateManager.getTransactionState(transactionalId) match {
          case Left(Errors.NOT_COORDINATOR) =>
            info(s"No longer the coordinator for $transactionalId with coordinator epoch $coordinatorEpoch; cancel appending $newMetadata to transaction log")

          case Left(Errors.COORDINATOR_LOAD_IN_PROGRESS) =>
            info(s"Loading the transaction partition that contains $transactionalId while my current coordinator epoch is $coordinatorEpoch; " +
              s"so cancel appending $newMetadata to transaction log since the loading process will continue the remaining work")

          case Left(unexpectedError) =>
            throw new IllegalStateException(s"Unhandled error $unexpectedError when fetching current transaction state")

          case Right(Some(epochAndMetadata)) =>
            if (epochAndMetadata.coordinatorEpoch == coordinatorEpoch) {
              debug(s"Sending $transactionalId's transaction markers for $txnMetadata with coordinator epoch $coordinatorEpoch succeeded, trying to append complete transaction log now")

              tryAppendToLog(TxnLogAppend(transactionalId, coordinatorEpoch, txnMetadata, newMetadata))
            } else {
              info(s"The cached metadata $txnMetadata has changed to $epochAndMetadata after completed sending the markers with coordinator " +
                s"epoch $coordinatorEpoch; abort transiting the metadata to $newMetadata as it may have been updated by another process")
            }

          case Right(None) =>
            val errorMsg = s"The coordinator still owns the transaction partition for $transactionalId, but there is " +
              s"no metadata in the cache; this is not expected"
            fatal(errorMsg)
            throw new IllegalStateException(errorMsg)
        }

      case other =>
        val errorMsg = s"Unexpected error ${other.exceptionName} before appending to txn log for $transactionalId"
        fatal(errorMsg)
        throw new IllegalStateException(errorMsg)
    }
  }

  val delayedTxnMarker = new DelayedTxnMarker(txnMetadata, appendToLogCallback, txnStateManager.stateReadLock)
  txnMarkerPurgatory.tryCompleteElseWatch(delayedTxnMarker, Seq(transactionalId))

  // 加入队列中，按照node异步发送
  addTxnMarkersToBrokerQueue(transactionalId, txnMetadata.producerId, txnMetadata.producerEpoch, txnResult, coordinatorEpoch, txnMetadata.topicPartitions.toSet)
}
```



最后通过TransactionMarkerChannelManager这个内部线程发送mark到各个broker.



dowork()->generateRequests() ->drainQueuedTransactionMarkers()

```scala
private[transaction] def drainQueuedTransactionMarkers(): Iterable[RequestAndCompletionHandler] = {
  retryLogAppends()
  // 获取所有的markder
  val txnIdAndMarkerEntries: java.util.List[TxnIdAndMarkerEntry] = new util.ArrayList[TxnIdAndMarkerEntry]()
  markersQueueForUnknownBroker.forEachTxnTopicPartition { case (_, queue) =>
    queue.drainTo(txnIdAndMarkerEntries)
  }

  for (txnIdAndMarker: TxnIdAndMarkerEntry <- txnIdAndMarkerEntries.asScala) {
    val transactionalId = txnIdAndMarker.txnId
    val producerId = txnIdAndMarker.txnMarkerEntry.producerId
    val producerEpoch = txnIdAndMarker.txnMarkerEntry.producerEpoch
    val txnResult = txnIdAndMarker.txnMarkerEntry.transactionResult
    val coordinatorEpoch = txnIdAndMarker.txnMarkerEntry.coordinatorEpoch
    val topicPartitions = txnIdAndMarker.txnMarkerEntry.partitions.asScala.toSet

    addTxnMarkersToBrokerQueue(transactionalId, producerId, producerEpoch, txnResult, coordinatorEpoch, topicPartitions)
  }

  // 按照broker过滤markerqueue组装WriteTxnMarkersRequest
  markersQueuePerBroker.values.map { brokerRequestQueue =>
    val txnIdAndMarkerEntries = new util.ArrayList[TxnIdAndMarkerEntry]()
    brokerRequestQueue.forEachTxnTopicPartition { case (_, queue) =>
      queue.drainTo(txnIdAndMarkerEntries)
    }
    (brokerRequestQueue.destination, txnIdAndMarkerEntries)
  }.filter { case (_, entries) => !entries.isEmpty }.map { case (node, entries) =>
    val markersToSend = entries.asScala.map(_.txnMarkerEntry).asJava
    val requestCompletionHandler = new TransactionMarkerRequestCompletionHandler(node.id, txnStateManager, this, entries)
    RequestAndCompletionHandler(node, new WriteTxnMarkersRequest.Builder(markersToSend), requestCompletionHandler)
  }
}
```



WriteTxnMarkersRequest的核心数据如下，从以下marker数据可以看出，主要指当前topic-partition的事务结果信息，这些信息将被写入到leader partition中。

```scala
public TxnMarkerEntry(long producerId,
                      short producerEpoch,
                      int coordinatorEpoch,
                      TransactionResult result,
                      List<TopicPartition> partitions) {
    this.producerId = producerId;
    this.producerEpoch = producerEpoch;
    this.coordinatorEpoch = coordinatorEpoch;
    this.result = result;
    this.partitions = partitions;
}
```

## 回滚事务

同上

# FAQ

开启事务。生产者重启会不会造成消息重复？



# 参考



https://cwiki.apache.org/confluence/display/KAFKA/Transactional+Messaging+in+Kafka

幂等性：

https://cwiki.apache.org/confluence/display/KAFKA/Idempotent+Producer

https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging#KIP98ExactlyOnceDeliveryandTransactionalMessaging-RPCProtocolSummary
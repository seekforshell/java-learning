

## 生产者

### 生产者启动流程





### 生产者发包流程图

<img src="images/io_flow.png" alt="image-20201125155533078" style="zoom:50%;" />



#### ProducerRecord

生产者发送消息的类，kafka发送的消息是以ProducerRecord的格式发送的。

```java
public class ProducerRecord<K, V> {

    private final String topic;
    private final Integer partition;
    private final Headers headers;
    private final K key;
    private final V value;
    private final Long timestamp;
    /**
     * Creates a record to be sent to a specified topic and partition
     *
     * @param topic The topic the record will be appended to
     * @param partition The partition to which the record should be sent
     * @param key The key that will be included in the record
     * @param value The record contents
     */
    public ProducerRecord(String topic, Integer partition, K key, V value) {
        this(topic, partition, null, key, value, null);
    }
	...  
}
```



#### KafkaProducer

发送消息入口，interceptors消息发送的的拦截器，可以在消息发送时进行拦截，对报文进行解析或者进行自定义动作。

```java
@Override
public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
    // intercept the record, which can be potentially modified; this method does not throw exceptions
    ProducerRecord<K, V> interceptedRecord = this.interceptors.onSend(record);
    return doSend(interceptedRecord, callback);
}
```



doSend

```java
private Future<RecordMetadata> doSend(ProducerRecord<K, V> record, Callback callback) {
    TopicPartition tp = null;
    try {
      	// 检查sender状态
        throwIfProducerClosed();
        // first make sure the metadata for the topic is available
        long nowMs = time.milliseconds();
        ClusterAndWaitTime clusterAndWaitTime;
        try {
          	// 这里需要等待topic/partition的元数据更新，比如partition的个数等
            clusterAndWaitTime = waitOnMetadata(record.topic(), record.partition(), nowMs, maxBlockTimeMs);
        } catch (KafkaException e) {
            if (metadata.isClosed())
                throw new KafkaException("Producer closed while send in progress", e);
            throw e;
        }
        nowMs += clusterAndWaitTime.waitedOnMetadataMs;
        long remainingWaitMs = Math.max(0, maxBlockTimeMs - clusterAndWaitTime.waitedOnMetadataMs);
        Cluster cluster = clusterAndWaitTime.cluster;
      	// 序列化键为字节数组
        byte[] serializedKey;
        try {
            serializedKey = keySerializer.serialize(record.topic(), record.headers(), record.key());
        } catch (ClassCastException cce) {
            throw new SerializationException("Can't convert key of class " + record.key().getClass().getName() +
                    " to class " + producerConfig.getClass(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG).getName() +
                    " specified in key.serializer", cce);
        }
      	// 序列化值为字节数组
        byte[] serializedValue;
        try {
            serializedValue = valueSerializer.serialize(record.topic(), record.headers(), record.value());
        } catch (ClassCastException cce) {
            throw new SerializationException("Can't convert value of class " + record.value().getClass().getName() +
                    " to class " + producerConfig.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG).getName() +
                    " specified in value.serializer", cce);
        }
      	// 为消息分配Partition，这里的分配策略可以通过参数指定，比如按key的hash取topic的hash数，round--robin等策略
        int partition = partition(record, serializedKey, serializedValue, cluster);
        tp = new TopicPartition(record.topic(), partition);

        setReadOnly(record.headers());
        Header[] headers = record.headers().toArray();

      	// 计算要发送的消息的长度大小，包括producer的头部大小、消息头部的大小及报文负载本省需要的字节大小等
        int serializedSize = AbstractRecords.estimateSizeInBytesUpperBound(apiVersions.maxUsableProduceMagic(),
                compressionType, serializedKey, serializedValue, headers);
      	// 确保消息大小合理，不能超出设定大小及设定的内存buffer大小
        ensureValidRecordSize(serializedSize);
        long timestamp = record.timestamp() == null ? nowMs : record.timestamp();
        if (log.isTraceEnabled()) {
            log.trace("Attempting to append record {} with callback {} to topic {} partition {}", record, callback, record.topic(), partition);
        }
        // producer callback will make sure to call both 'callback' and interceptor callback
        Callback interceptCallback = new InterceptorCallback<>(callback, this.interceptors, tp);

        if (transactionManager != null && transactionManager.isTransactional()) {
            transactionManager.failIfNotReadyForSend();
        }
      	// 往消息池中添加需要发送的消息，注意：这里会按partition来进行消息发送（批发送和消息压缩等）
        RecordAccumulator.RecordAppendResult result = accumulator.append(tp, timestamp, serializedKey,
                serializedValue, headers, interceptCallback, remainingWaitMs, true, nowMs);

        if (result.abortForNewBatch) {
            int prevPartition = partition;
            partitioner.onNewBatch(record.topic(), cluster, prevPartition);
            partition = partition(record, serializedKey, serializedValue, cluster);
            tp = new TopicPartition(record.topic(), partition);
            if (log.isTraceEnabled()) {
                log.trace("Retrying append due to new batch creation for topic {} partition {}. The old partition was {}", record.topic(), partition, prevPartition);
            }
            // producer callback will make sure to call both 'callback' and interceptor callback
            interceptCallback = new InterceptorCallback<>(callback, this.interceptors, tp);

            result = accumulator.append(tp, timestamp, serializedKey,
                serializedValue, headers, interceptCallback, remainingWaitMs, false, nowMs);
        }

        if (transactionManager != null && transactionManager.isTransactional())
            transactionManager.maybeAddPartitionToTransaction(tp);

        if (result.batchIsFull || result.newBatchCreated) {
            log.trace("Waking up the sender since topic {} partition {} is either full or getting a new batch", record.topic(), partition);
            this.sender.wakeup();
        }
      	// 返回消息发送的同步future
        return result.future;
        // handling exceptions and record the errors;
        // for API exceptions return them in the future,
        // for other exceptions throw directly
    } catch (ApiException e) {
        log.debug("Exception occurred during message send:", e);
        if (callback != null)
            callback.onCompletion(null, e);
        this.errors.record();
        this.interceptors.onSendError(record, tp, e);
        return new FutureFailure(e);
    } catch (InterruptedException e) {
        this.errors.record();
        this.interceptors.onSendError(record, tp, e);
        throw new InterruptException(e);
    } catch (BufferExhaustedException e) {
        this.errors.record();
        this.metrics.sensor("buffer-exhausted-records").record();
        this.interceptors.onSendError(record, tp, e);
        throw e;
    } catch (KafkaException e) {
        this.errors.record();
        this.interceptors.onSendError(record, tp, e);
        throw e;
    } catch (Exception e) {
        // we notify interceptor about all exceptions, since onSend is called before anything else in this method
        this.interceptors.onSendError(record, tp, e);
        throw e;
    }
}
```



#### RecordAccumulator



此类是生产者的消息池，相当于一个缓冲队列的角色。



```java
public RecordAccumulator(LogContext logContext,
                         int batchSize,
                         CompressionType compression,
                         int lingerMs,
                         long retryBackoffMs,
                         int deliveryTimeoutMs,
                         Metrics metrics,
                         String metricGrpName,
                         Time time,
                         ApiVersions apiVersions,
                         TransactionManager transactionManager,
                         BufferPool bufferPool) {
    this.log = logContext.logger(RecordAccumulator.class);
    this.drainIndex = 0;
    this.closed = false;
    this.flushesInProgress = new AtomicInteger(0);
    this.appendsInProgress = new AtomicInteger(0);
  	// 批量发送大小
    this.batchSize = batchSize;
  	// 消息压缩
    this.compression = compression;
    this.lingerMs = lingerMs;
    this.retryBackoffMs = retryBackoffMs;
    this.deliveryTimeoutMs = deliveryTimeoutMs;
  	// 存储的是分区到分区双端队列的映射关系
  	// TopicPartition -> ArrayDeque<ProducerBatch>
    this.batches = new CopyOnWriteMap<>();
  	// 发送消息的内存池，可对消息内存进行分配、回收、大小控制
    this.free = bufferPool;
    this.incomplete = new IncompleteBatches();
    this.muted = new HashMap<>();
    this.time = time;
    this.apiVersions = apiVersions;
  	// 事务管理器
    this.transactionManager = transactionManager;
    registerMetrics(metrics, metricGrpName);
}
```

org.apache.kafka.clients.producer.internals.RecordAccumulator#append

```java
public RecordAppendResult append(TopicPartition tp,
                                 long timestamp,
                                 byte[] key,
                                 byte[] value,
                                 Header[] headers,
                                 Callback callback,
                                 long maxTimeToBlock,
                                 boolean abortOnNewBatch,
                                 long nowMs) throws InterruptedException {
    // We keep track of the number of appending thread to make sure we do not miss batches in
    // abortIncompleteBatches().
    appendsInProgress.incrementAndGet();
    ByteBuffer buffer = null;
    if (headers == null) headers = Record.EMPTY_HEADERS;
    try {
        // 获取分区的双端队列，没有则初始化
        Deque<ProducerBatch> dq = getOrCreateDeque(tp);
        synchronized (dq) {
            if (closed)
                throw new KafkaException("Producer closed while send in progress");
          	// 尝试往最后一个元素里追加消息如果列表为空则返回Null
            RecordAppendResult appendResult = tryAppend(timestamp, key, value, headers, callback, dq, nowMs);
            if (appendResult != null)
                return appendResult;
        }

        // we don't have an in-progress record batch try to allocate a new batch
        if (abortOnNewBatch) {
            // Return a result that will cause another call to append.
            return new RecordAppendResult(null, false, false, true);
        }

      	// 以下步骤为创建分区对应的ProducerBatch的过程，然后添加到双端队列尾部
        byte maxUsableMagic = apiVersions.maxUsableProduceMagic();
      	// 计算发送分区消息的批大小（也就是说当消息累计到此数量时发送消息，这个数量会影响发送算的吞吐量）
      	// 这里取消息大小和设定值中的最大值，因为可能消息会很大
        int size = Math.max(this.batchSize, AbstractRecords.estimateSizeInBytesUpperBound(maxUsableMagic, compression, key, value, headers));
        log.trace("Allocating a new {} byte message buffer for topic {} partition {}", size, tp.topic(), tp.partition());
      	// 内存池分配大小
        buffer = free.allocate(size, maxTimeToBlock);

        // Update the current time in case the buffer allocation blocked above.
        nowMs = time.milliseconds();
        synchronized (dq) {
            // Need to check if producer is closed again after grabbing the dequeue lock.
            if (closed)
                throw new KafkaException("Producer closed while send in progress");
						// 这里首先再次尝试，因为有可能存在竞态条件
            RecordAppendResult appendResult = tryAppend(timestamp, key, value, headers, callback, dq, nowMs);
            if (appendResult != null) {
                // Somebody else found us a batch, return the one we waited for! Hopefully this doesn't happen often...
                return appendResult;
            }
						// 创建消息构建起
            MemoryRecordsBuilder recordsBuilder = recordsBuilder(buffer, maxUsableMagic);
            ProducerBatch batch = new ProducerBatch(tp, recordsBuilder, nowMs);
            FutureRecordMetadata future = Objects.requireNonNull(batch.tryAppend(timestamp, key, value, headers,
                    callback, nowMs));

            dq.addLast(batch);
            incomplete.add(batch);

            // Don't deallocate this buffer in the finally block as it's being used in the record batch
            buffer = null;
            return new RecordAppendResult(future, dq.size() > 1 || batch.isFull(), true, false);
        }
    } finally {
        if (buffer != null)
            free.deallocate(buffer);
        appendsInProgress.decrementAndGet();
    }
}
```

tryAppend

```java
public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Header[] headers, Callback callback, long now) {
    if (!recordsBuilder.hasRoomFor(timestamp, key, value, headers)) {
        return null;
    } else {
        Long checksum = this.recordsBuilder.append(timestamp, key, value, headers);
        this.maxRecordSize = Math.max(this.maxRecordSize, AbstractRecords.estimateSizeInBytesUpperBound(magic(),
                recordsBuilder.compressionType(), key, value, headers));
        this.lastAppendTime = now;
        FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                               timestamp, checksum,
                                                               key == null ? -1 : key.length,
                                                               value == null ? -1 : value.length,
                                                               Time.SYSTEM);
        // we have to keep every future returned to the users in case the batch needs to be
        // split to several new batches and resent.
        thunks.add(new Thunk(callback, future));
        this.recordCount++;
        return future;
    }
}
```

那么这些数据加到accumulator中的数据什么发送的呢，看下面的分解：



生成KafkaProducer的时候会生成相应的Sender，以下是sender的主流程，

sender会定时获取accumulator中的消息并组成成相应的request请求。

```java
public void run() {
    log.debug("Starting Kafka producer I/O thread.");

    // main loop, runs until close is called
    while (running) {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Uncaught error in kafka producer I/O thread: ", e);
        }
    }

    log.debug("Beginning shutdown of Kafka producer I/O thread, sending remaining records.");

    // okay we stopped accepting requests but there may still be
    // requests in the transaction manager, accumulator or waiting for acknowledgment,
    // wait until these are completed.
    while (!forceClose && ((this.accumulator.hasUndrained() || this.client.inFlightRequestCount() > 0) || hasPendingTransactionalRequests())) {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Uncaught error in kafka producer I/O thread: ", e);
        }
    }

    // Abort the transaction if any commit or abort didn't go through the transaction manager's queue
    while (!forceClose && transactionManager != null && transactionManager.hasOngoingTransaction()) {
        if (!transactionManager.isCompleting()) {
            log.info("Aborting incomplete transaction due to shutdown");
            transactionManager.beginAbort();
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Uncaught error in kafka producer I/O thread: ", e);
        }
    }

    if (forceClose) {
        // We need to fail all the incomplete transactional requests and batches and wake up the threads waiting on
        // the futures.
        if (transactionManager != null) {
            log.debug("Aborting incomplete transactional requests due to forced shutdown");
            transactionManager.close();
        }
        log.debug("Aborting incomplete batches due to forced shutdown");
        this.accumulator.abortIncompleteBatches();
    }
    try {
        this.client.close();
    } catch (Exception e) {
        log.error("Failed to close network client", e);
    }

    log.debug("Shutdown of Kafka producer I/O thread has completed.");
}
```

runOnce

```java
void runOnce() {
    if (transactionManager != null) {
        try {
            transactionManager.maybeResolveSequences();

            // do not continue sending if the transaction manager is in a failed state
            if (transactionManager.hasFatalError()) {
                RuntimeException lastError = transactionManager.lastError();
                if (lastError != null)
                    maybeAbortBatches(lastError);
                client.poll(retryBackoffMs, time.milliseconds());
                return;
            }

            // Check whether we need a new producerId. If so, we will enqueue an InitProducerId
            // request which will be sent below
            transactionManager.bumpIdempotentEpochAndResetIdIfNeeded();

            if (maybeSendAndPollTransactionalRequest()) {
                return;
            }
        } catch (AuthenticationException e) {
            // This is already logged as error, but propagated here to perform any clean ups.
            log.trace("Authentication exception while processing transactional request", e);
            transactionManager.authenticationFailed(e);
        }
    }

    long currentTimeMs = time.milliseconds();
  	// 发送数据主流程
    long pollTimeout = sendProducerData(currentTimeMs);
    client.poll(pollTimeout, currentTimeMs);
}
```





```java
private long sendProducerData(long now) {
    Cluster cluster = metadata.fetch();
    // get the list of partitions with data ready to send
    RecordAccumulator.ReadyCheckResult result = this.accumulator.ready(cluster, now);

    // if there are any partitions whose leaders are not known yet, force metadata update
    if (!result.unknownLeaderTopics.isEmpty()) {
        // The set of topics with unknown leader contains topics with leader election pending as well as
        // topics which may have expired. Add the topic again to metadata to ensure it is included
        // and request metadata update, since there are messages to send to the topic.
        for (String topic : result.unknownLeaderTopics)
            this.metadata.add(topic, now);

        log.debug("Requesting metadata update due to unknown leader topics from the batched records: {}",
            result.unknownLeaderTopics);
        this.metadata.requestUpdate();
    }

    // remove any nodes we aren't ready to send to
    Iterator<Node> iter = result.readyNodes.iterator();
    long notReadyTimeout = Long.MAX_VALUE;
    while (iter.hasNext()) {
        Node node = iter.next();
        if (!this.client.ready(node, now)) {
            iter.remove();
            notReadyTimeout = Math.min(notReadyTimeout, this.client.pollDelayMs(node, now));
        }
    }

    // 从accumulator中获取待发送的消息
    Map<Integer, List<ProducerBatch>> batches = this.accumulator.drain(cluster, result.readyNodes, this.maxRequestSize, now);
    addToInflightBatches(batches);
    if (guaranteeMessageOrder) {
        // Mute all the partitions drained
        for (List<ProducerBatch> batchList : batches.values()) {
            for (ProducerBatch batch : batchList)
                this.accumulator.mutePartition(batch.topicPartition);
        }
    }

    accumulator.resetNextBatchExpiryTime();
    List<ProducerBatch> expiredInflightBatches = getExpiredInflightBatches(now);
    List<ProducerBatch> expiredBatches = this.accumulator.expiredBatches(now);
    expiredBatches.addAll(expiredInflightBatches);

    // Reset the producer id if an expired batch has previously been sent to the broker. Also update the metrics
    // for expired batches. see the documentation of @TransactionState.resetIdempotentProducerId to understand why
    // we need to reset the producer id here.
    if (!expiredBatches.isEmpty())
        log.trace("Expired {} batches in accumulator", expiredBatches.size());
    for (ProducerBatch expiredBatch : expiredBatches) {
        String errorMessage = "Expiring " + expiredBatch.recordCount + " record(s) for " + expiredBatch.topicPartition
            + ":" + (now - expiredBatch.createdMs) + " ms has passed since batch creation";
        failBatch(expiredBatch, -1, NO_TIMESTAMP, new TimeoutException(errorMessage), false);
        if (transactionManager != null && expiredBatch.inRetry()) {
            // This ensures that no new batches are drained until the current in flight batches are fully resolved.
            transactionManager.markSequenceUnresolved(expiredBatch);
        }
    }
    sensors.updateProduceRequestMetrics(batches);

    // If we have any nodes that are ready to send + have sendable data, poll with 0 timeout so this can immediately
    // loop and try sending more data. Otherwise, the timeout will be the smaller value between next batch expiry
    // time, and the delay time for checking data availability. Note that the nodes may have data that isn't yet
    // sendable due to lingering, backing off, etc. This specifically does not include nodes with sendable data
    // that aren't ready to send since they would cause busy looping.
    long pollTimeout = Math.min(result.nextReadyCheckDelayMs, notReadyTimeout);
    pollTimeout = Math.min(pollTimeout, this.accumulator.nextExpiryTimeMs() - now);
    pollTimeout = Math.max(pollTimeout, 0);
    if (!result.readyNodes.isEmpty()) {
        log.trace("Nodes with data ready to send: {}", result.readyNodes);
        // if some partitions are already ready to be sent, the select time would be 0;
        // otherwise if some partition already has some data accumulated but not ready yet,
        // the select time will be the time difference between now and its linger expiry time;
        // otherwise the select time will be the time difference between now and the metadata expiry time;
        pollTimeout = 0;
    }
    sendProduceRequests(batches, now);
    return pollTimeout;
}
```



往后继续追看代码：

```java
/**
 * Create a produce request from the given record batches
 */
private void sendProduceRequest(long now, int destination, short acks, int timeout, List<ProducerBatch> batches) {
    if (batches.isEmpty())
        return;

    Map<TopicPartition, MemoryRecords> produceRecordsByPartition = new HashMap<>(batches.size());
    final Map<TopicPartition, ProducerBatch> recordsByPartition = new HashMap<>(batches.size());

    // find the minimum magic version used when creating the record sets
    byte minUsedMagic = apiVersions.maxUsableProduceMagic();
    for (ProducerBatch batch : batches) {
        if (batch.magic() < minUsedMagic)
            minUsedMagic = batch.magic();
    }

    for (ProducerBatch batch : batches) {
        TopicPartition tp = batch.topicPartition;
        MemoryRecords records = batch.records();

        // down convert if necessary to the minimum magic used. In general, there can be a delay between the time
        // that the producer starts building the batch and the time that we send the request, and we may have
        // chosen the message format based on out-dated metadata. In the worst case, we optimistically chose to use
        // the new message format, but found that the broker didn't support it, so we need to down-convert on the
        // client before sending. This is intended to handle edge cases around cluster upgrades where brokers may
        // not all support the same message format version. For example, if a partition migrates from a broker
        // which is supporting the new magic version to one which doesn't, then we will need to convert.
        if (!records.hasMatchingMagic(minUsedMagic))
            records = batch.records().downConvert(minUsedMagic, 0, time).records();
        produceRecordsByPartition.put(tp, records);
        recordsByPartition.put(tp, batch);
    }

    String transactionalId = null;
    if (transactionManager != null && transactionManager.isTransactional()) {
        transactionalId = transactionManager.transactionalId();
    }
  	// 可以看到这里发送的Produce的消息类型
    ProduceRequest.Builder requestBuilder = ProduceRequest.Builder.forMagic(minUsedMagic, acks, timeout,
            produceRecordsByPartition, transactionalId);
    RequestCompletionHandler callback = new RequestCompletionHandler() {
        public void onComplete(ClientResponse response) {
            handleProduceResponse(response, recordsByPartition, time.milliseconds());
        }
    };

    String nodeId = Integer.toString(destination);
  	// 生成Produce的消息类型
    ClientRequest clientRequest = client.newClientRequest(nodeId, requestBuilder, now, acks != 0,
            requestTimeoutMs, callback);
  	// 组装成NetworkSend放到kafkaChannel中
    client.send(clientRequest, now);
    log.trace("Sent produce request to {}: {}", nodeId, requestBuilder);
}
```

org.apache.kafka.common.protocol.ApiKeys

```java

PRODUCE(0, "Produce", ProduceRequest.schemaVersions(), ProduceResponse.schemaVersions()),
```

## 幂等性

首先需要明确的是Kafka的幂等性仅仅实现了分区为单元，如果想要跨分区多会话的场景。

kafka如何保持幂等性？引入了PID（epoch）和seq的概念。

首先seq在哪里分配的？主流程如下，broker端收到生产者发送的消息后，如果ack=0则不需要回复消息，否则回复ProduceResponse消息，客户端回调处理，将seq更新，如果报错则重发报文从而实现幂等性

下面是生产者在发送过程中分配RecordBatch的流程：

org.apache.kafka.clients.producer.internals.Sender#runOnce

org.apache.kafka.clients.producer.internals.Sender#sendProducerData

org.apache.kafka.clients.producer.internals.RecordAccumulator#drain

org.apache.kafka.clients.producer.internals.RecordAccumulator#drainBatchesForOneNode

```scala
private List<ProducerBatch> drainBatchesForOneNode(Cluster cluster, Node node, int maxSize, long now) {
    int size = 0;
    List<PartitionInfo> parts = cluster.partitionsForNode(node.id());
    List<ProducerBatch> ready = new ArrayList<>();
    /* to make starvation less likely this loop doesn't start at 0 */
    int start = drainIndex = drainIndex % parts.size();
    do {
        PartitionInfo part = parts.get(drainIndex);
        TopicPartition tp = new TopicPartition(part.topic(), part.partition());
        this.drainIndex = (this.drainIndex + 1) % parts.size();

        // Only proceed if the partition has no in-flight batches.
        if (isMuted(tp, now))
            continue;

        Deque<ProducerBatch> deque = getDeque(tp);
        if (deque == null)
            continue;

        synchronized (deque) {
            // invariant: !isMuted(tp,now) && deque != null
            ProducerBatch first = deque.peekFirst();
            if (first == null)
                continue;

            // first != null
            boolean backoff = first.attempts() > 0 && first.waitedTimeMs(now) < retryBackoffMs;
            // Only drain the batch if it is not during backoff period.
            if (backoff)
                continue;

            if (size + first.estimatedSizeInBytes() > maxSize && !ready.isEmpty()) {
                // there is a rare case that a single batch size is larger than the request size due to
                // compression; in this case we will still eventually send this batch in a single request
                break;
            } else {
                if (shouldStopDrainBatchesForPartition(first, tp))
                    break;

                boolean isTransactional = transactionManager != null && transactionManager.isTransactional();
                ProducerIdAndEpoch producerIdAndEpoch =
                    transactionManager != null ? transactionManager.producerIdAndEpoch() : null;
                ProducerBatch batch = deque.pollFirst();
                if (producerIdAndEpoch != null && !batch.hasSequence()) {
                    // If the batch already has an assigned sequence, then we should not change the producer id and
                    // sequence number, since this may introduce duplicates. In particular, the previous attempt
                    // may actually have been accepted, and if we change the producer id and sequence here, this
                    // attempt will also be accepted, causing a duplicate.
                    //
                    // Additionally, we update the next sequence number bound for the partition, and also have
                    // the transaction manager track the batch so as to ensure that sequence ordering is maintained
                    // even if we receive out of order responses.
                    batch.setProducerState(producerIdAndEpoch, transactionManager.sequenceNumber(batch.topicPartition), isTransactional);
                    transactionManager.incrementSequenceNumber(batch.topicPartition, batch.recordCount);
                    log.debug("Assigned producerId {} and producerEpoch {} to batch with base sequence " +
                            "{} being sent to partition {}", producerIdAndEpoch.producerId,
                        producerIdAndEpoch.epoch, batch.baseSequence(), tp);

                    transactionManager.addInFlightBatch(batch);
                }
                batch.close();
                size += batch.records().sizeInBytes();
                ready.add(batch);

                batch.drained(now);
            }
        }
    } while (start != drainIndex);
    return ready;
}
```



## 消息场景分解

列此篇章的目的在于希望能够通过分析一些常用的收发包业务场景来剖析kafka，这里会从client和broker等视角做分析。

### InitProducerIdRequest

#### client

org.apache.kafka.clients.producer.internals.Sender#run

org.apache.kafka.clients.producer.internals.Sender#runOnce

org.apache.kafka.clients.producer.internals.TransactionManager#bumpIdempotentEpochAndResetIdIfNeeded

```java
void runOnce() {
  	// 事务管理器不为空有两种情况：一种幂等性，一种事务
    if (transactionManager != null) {
        try {
            transactionManager.maybeResolveSequences();

            // do not continue sending if the transaction manager is in a failed state
            if (transactionManager.hasFatalError()) {
                RuntimeException lastError = transactionManager.lastError();
                if (lastError != null)
                    maybeAbortBatches(lastError);
                client.poll(retryBackoffMs, time.milliseconds());
                return;
            }

            // Check whether we need a new producerId. If so, we will enqueue an InitProducerId
            // request which will be sent below
          	// 非事务情况下，这里我们发送一个InitProducerIdRequest
            transactionManager.bumpIdempotentEpochAndResetIdIfNeeded();

            if (maybeSendAndPollTransactionalRequest()) {
                return;
            }
        } catch (AuthenticationException e) {
            // This is already logged as error, but propagated here to perform any clean ups.
            log.trace("Authentication exception while processing transactional request", e);
            transactionManager.authenticationFailed(e);
        }
    }

    long currentTimeMs = time.milliseconds();
  	// 发送request核心流程
    long pollTimeout = sendProducerData(currentTimeMs);
    client.poll(pollTimeout, currentTimeMs);
}
```



#### broker

客户端在生产数据的时候发现自己

kafka.server.KafkaApis#handleInitProducerIdRequest

```scala
def handleInitProducerId(transactionalId: String,
                         transactionTimeoutMs: Int,
                         expectedProducerIdAndEpoch: Option[ProducerIdAndEpoch],
                         responseCallback: InitProducerIdCallback): Unit = {

  // 这里是幂等性的情况，幂等性时不需要写事务日志
  if (transactionalId == null) {
    // if the transactional id is null, then always blindly accept the request
    // and return a new producerId from the producerId manager
    val producerId = producerIdManager.generateProducerId()
    responseCallback(InitProducerIdResult(producerId, producerEpoch = 0, Errors.NONE))
  } else if (transactionalId.isEmpty) {
    // if transactional id is empty then return error as invalid request. This is
    // to make TransactionCoordinator's behavior consistent with producer client
    responseCallback(initTransactionError(Errors.INVALID_REQUEST))
  } else if (!txnManager.validateTransactionTimeoutMs(transactionTimeoutMs)) {
    // check transactionTimeoutMs is not larger than the broker configured maximum allowed value
    responseCallback(initTransactionError(Errors.INVALID_TRANSACTION_TIMEOUT))
  } else {
    // 这里是客户端开启事务的流程
    val coordinatorEpochAndMetadata = txnManager.getTransactionState(transactionalId).right.flatMap {
      case None =>
        val producerId = producerIdManager.generateProducerId()
        val createdMetadata = new TransactionMetadata(transactionalId = transactionalId,
          producerId = producerId,
          lastProducerId = RecordBatch.NO_PRODUCER_ID,
          producerEpoch = RecordBatch.NO_PRODUCER_EPOCH,
          lastProducerEpoch = RecordBatch.NO_PRODUCER_EPOCH,
          txnTimeoutMs = transactionTimeoutMs,
          state = Empty,
          topicPartitions = collection.mutable.Set.empty[TopicPartition],
          txnLastUpdateTimestamp = time.milliseconds())
        txnManager.putTransactionStateIfNotExists(createdMetadata)

      case Some(epochAndTxnMetadata) => Right(epochAndTxnMetadata)
    }

    val result: ApiResult[(Int, TxnTransitMetadata)] = coordinatorEpochAndMetadata.right.flatMap {
      existingEpochAndMetadata =>
        val coordinatorEpoch = existingEpochAndMetadata.coordinatorEpoch
        val txnMetadata = existingEpochAndMetadata.transactionMetadata

        txnMetadata.inLock {
          prepareInitProducerIdTransit(transactionalId, transactionTimeoutMs, coordinatorEpoch, txnMetadata,
            expectedProducerIdAndEpoch)
        }
    }

    result match {
      case Left(error) =>
        responseCallback(initTransactionError(error))

      case Right((coordinatorEpoch, newMetadata)) =>
        if (newMetadata.txnState == PrepareEpochFence) {
          // abort the ongoing transaction and then return CONCURRENT_TRANSACTIONS to let client wait and retry
          def sendRetriableErrorCallback(error: Errors): Unit = {
            if (error != Errors.NONE) {
              responseCallback(initTransactionError(error))
            } else {
              responseCallback(initTransactionError(Errors.CONCURRENT_TRANSACTIONS))
            }
          }

          handleEndTransaction(transactionalId,
            newMetadata.producerId,
            newMetadata.producerEpoch,
            TransactionResult.ABORT,
            sendRetriableErrorCallback)
        } else {
          def sendPidResponseCallback(error: Errors): Unit = {
            if (error == Errors.NONE) {
              info(s"Initialized transactionalId $transactionalId with producerId ${newMetadata.producerId} and producer " +
                s"epoch ${newMetadata.producerEpoch} on partition " +
                s"${Topic.TRANSACTION_STATE_TOPIC_NAME}-${txnManager.partitionFor(transactionalId)}")
              responseCallback(initTransactionMetadata(newMetadata))
            } else {
              info(s"Returning $error error code to client for $transactionalId's InitProducerId request")
              responseCallback(initTransactionError(error))
            }
          }

          txnManager.appendTransactionToLog(transactionalId, coordinatorEpoch, newMetadata, sendPidResponseCallback)
        }
    }
  }
}
```

### ProduceRequest



#### client



#### broker





kafka.server.KafkaApis#handleProduceRequest

kafka.server.ReplicaManager#appendRecords

kafka.server.ReplicaManager#appendToLocalLog

kafka.cluster.Partition#appendRecordsToLeader

kafka.log.Log#append



追加record到partion的leader

```scala
def appendRecordsToLeader(records: MemoryRecords, origin: AppendOrigin, requiredAcks: Int): LogAppendInfo = {
  val (info, leaderHWIncremented) = inReadLock(leaderIsrUpdateLock) {
    leaderLogIfLocal match {
      case Some(leaderLog) =>
        val minIsr = leaderLog.config.minInSyncReplicas
        val inSyncSize = inSyncReplicaIds.size

        // Avoid writing to leader if there are not enough insync replicas to make it safe
        if (inSyncSize < minIsr && requiredAcks == -1) {
          throw new NotEnoughReplicasException(s"The size of the current ISR $inSyncReplicaIds " +
            s"is insufficient to satisfy the min.isr requirement of $minIsr for partition $topicPartition")
        }

      	// 调用Log的appendAsLeader方法
        val info = leaderLog.appendAsLeader(records, leaderEpoch = this.leaderEpoch, origin,
          interBrokerProtocolVersion)

        // we may need to increment high watermark since ISR could be down to 1
        (info, maybeIncrementLeaderHW(leaderLog))

      case None =>
        throw new NotLeaderForPartitionException("Leader not local for partition %s on broker %d"
          .format(topicPartition, localBrokerId))
    }
  }

  // some delayed operations may be unblocked after HW changed
  if (leaderHWIncremented)
    tryCompleteDelayedRequests()
  else {
    // probably unblock some follower fetch requests since log end offset has been updated
    delayedOperations.checkAndCompleteFetch()
  }

  info
}
```



Log#append详解。主要步骤如下：

(1)

(2)

(3)

```java
private def append(records: MemoryRecords,
                   origin: AppendOrigin,    // client or Coordinator
                   interBrokerProtocolVersion: ApiVersion,
                   assignOffsets: Boolean,	// 是否更新offset，当前节点是leader时为true
                   leaderEpoch: Int): LogAppendInfo = {
  maybeHandleIOException(s"Error while appending records to $topicPartition in dir ${dir.getParent}") {
    // 校验消息的有效性：比如单调性、版本、大小等
    val appendInfo = analyzeAndValidateRecords(records, origin)

    // 有效的record个数
    if (appendInfo.shallowCount == 0)
      return appendInfo

    // trim any invalid bytes or partial messages before appending it to the on-disk log
    var validRecords = trimInvalidBytes(records, appendInfo)

    // 这里对临界资源Log使用互斥锁：考虑事务和非事务的写场景，都可以写数据，只不过事务场景下维护了事务日志，
    // 在消费者消费时可以根据偏移值来过滤无效消息
    lock synchronized {
      checkIfMemoryMappedBufferClosed()
        
      // a.是否对对消息集合使用nextOffset和epoch信息  
      if (assignOffsets) {
        // assign offsets to the message set
        val offset = new LongRef(nextOffsetMetadata.messageOffset)
        appendInfo.firstOffset = Some(offset.value)
        val now = time.milliseconds
        val validateAndOffsetAssignResult = try {
          LogValidator.validateMessagesAndAssignOffsets(validRecords,
            topicPartition,
            offset,
            time,
            now,
            appendInfo.sourceCodec,
            appendInfo.targetCodec,
            config.compact,
            config.messageFormatVersion.recordVersion.value,
            config.messageTimestampType,
            config.messageTimestampDifferenceMaxMs,
            leaderEpoch,
            origin,
            interBrokerProtocolVersion,
            brokerTopicStats)
        } catch {
          case e: IOException =>
            throw new KafkaException(s"Error validating messages while appending to log $name", e)
        }
        validRecords = validateAndOffsetAssignResult.validatedRecords
        appendInfo.maxTimestamp = validateAndOffsetAssignResult.maxTimestamp
        appendInfo.offsetOfMaxTimestamp = validateAndOffsetAssignResult.shallowOffsetOfMaxTimestamp
        appendInfo.lastOffset = offset.value - 1
        appendInfo.recordConversionStats = validateAndOffsetAssignResult.recordConversionStats
        if (config.messageTimestampType == TimestampType.LOG_APPEND_TIME)
          appendInfo.logAppendTime = now

        // re-validate message sizes if there's a possibility that they have changed (due to re-compression or message
        // format conversion)
        if (validateAndOffsetAssignResult.messageSizeMaybeChanged) {
          for (batch <- validRecords.batches.asScala) {
            if (batch.sizeInBytes > config.maxMessageSize) {
              // we record the original message set size instead of the trimmed size
              // to be consistent with pre-compression bytesRejectedRate recording
              brokerTopicStats.topicStats(topicPartition.topic).bytesRejectedRate.mark(records.sizeInBytes)
              brokerTopicStats.allTopicsStats.bytesRejectedRate.mark(records.sizeInBytes)
              throw new RecordTooLargeException(s"Message batch size is ${batch.sizeInBytes} bytes in append to" +
                s"partition $topicPartition which exceeds the maximum configured size of ${config.maxMessageSize}.")
            }
          }
        }
      } else {
        // we are taking the offsets we are given
        if (!appendInfo.offsetsMonotonic)
          throw new OffsetsOutOfOrderException(s"Out of order offsets found in append to $topicPartition: " +
                                               records.records.asScala.map(_.offset))

        if (appendInfo.firstOrLastOffsetOfFirstBatch < nextOffsetMetadata.messageOffset) {
          // we may still be able to recover if the log is empty
          // one example: fetching from log start offset on the leader which is not batch aligned,
          // which may happen as a result of AdminClient#deleteRecords()
          val firstOffset = appendInfo.firstOffset match {
            case Some(offset) => offset
            case None => records.batches.asScala.head.baseOffset()
          }

          val firstOrLast = if (appendInfo.firstOffset.isDefined) "First offset" else "Last offset of the first batch"
          throw new UnexpectedAppendOffsetException(
            s"Unexpected offset in append to $topicPartition. $firstOrLast " +
            s"${appendInfo.firstOrLastOffsetOfFirstBatch} is less than the next offset ${nextOffsetMetadata.messageOffset}. " +
            s"First 10 offsets in append: ${records.records.asScala.take(10).map(_.offset)}, last offset in" +
            s" append: ${appendInfo.lastOffset}. Log start offset = $logStartOffset",
            firstOffset, appendInfo.lastOffset)
        }
      }

      // 更新epoche信息
      validRecords.batches.asScala.foreach { batch =>
        if (batch.magic >= RecordBatch.MAGIC_VALUE_V2) {
          maybeAssignEpochStartOffset(batch.partitionLeaderEpoch, batch.baseOffset)
        } else {
          // In partial upgrade scenarios, we may get a temporary regression to the message format. In
          // order to ensure the safety of leader election, we clear the epoch cache so that we revert
          // to truncation by high watermark after the next leader election.
          leaderEpochCache.filter(_.nonEmpty).foreach { cache =>
            warn(s"Clearing leader epoch cache after unexpected append with message format v${batch.magic}")
            cache.clearAndFlush()
          }
        }
      }

      // check messages set size may be exceed config.segmentSize
      // 消息集合的大小不能大于段大小
      if (validRecords.sizeInBytes > config.segmentSize) {
        throw new RecordBatchTooLargeException(s"Message batch size is ${validRecords.sizeInBytes} bytes in append " +
          s"to partition $topicPartition, which exceeds the maximum configured segment size of ${config.segmentSize}.")
      }

      // maybe roll the log if this segment is full
      val segment = maybeRoll(validRecords.sizeInBytes, appendInfo)

      val logOffsetMetadata = LogOffsetMetadata(
        messageOffset = appendInfo.firstOrLastOffsetOfFirstBatch,
        segmentBaseOffset = segment.baseOffset,
        relativePositionInSegment = segment.size)

      // now that we have valid records, offsets assigned, and timestamps updated, we need to
      // validate the idempotent/transactional state of the producers and collect some metadata
      // 从record中收集事务和幂等性（seq/epoch/pid等）到producerStateManager中
      // Maintains a mapping from ProducerIds to metadata about the last appended entries: 
      // epoch, sequence number, last offset, etc.)
      val (updatedProducers, completedTxns, maybeDuplicate) = analyzeAndValidateProducerState(
        logOffsetMetadata, validRecords, origin)

      maybeDuplicate.foreach { duplicate =>
        appendInfo.firstOffset = Some(duplicate.firstOffset)
        appendInfo.lastOffset = duplicate.lastOffset
        appendInfo.logAppendTime = duplicate.timestamp
        appendInfo.logStartOffset = logStartOffset
        return appendInfo
      }
			// 写timeindex和log文件
      segment.append(largestOffset = appendInfo.lastOffset,
        largestTimestamp = appendInfo.maxTimestamp,
        shallowOffsetOfMaxTimestamp = appendInfo.offsetOfMaxTimestamp,
        records = validRecords)

      // Increment the log end offset. We do this immediately after the append because a
      // write to the transaction index below may fail and we want to ensure that the offsets
      // of future appends still grow monotonically. The resulting transaction index inconsistency
      // will be cleaned up after the log directory is recovered. Note that the end offset of the
      // ProducerStateManager will not be updated and the last stable offset will not advance
      // if the append to the transaction index fails.
      updateLogEndOffset(appendInfo.lastOffset + 1)

      // update the producer state
      for (producerAppendInfo <- updatedProducers.values) {
        producerStateManager.update(producerAppendInfo)
      }

      // update the transaction index with the true last stable offset. The last offset visible
      // to consumers using READ_COMMITTED will be limited by this value and the high watermark.
      for (completedTxn <- completedTxns) {
        val lastStableOffset = producerStateManager.lastStableOffset(completedTxn)
        // 更新事务日志，用于对参与事务的消费者定义消息可见范围
        segment.updateTxnIndex(completedTxn, lastStableOffset)
        producerStateManager.completeTxn(completedTxn)
      }

      // always update the last producer id map offset so that the snapshot reflects the current offset
      // even if there isn't any idempotent data being written
      producerStateManager.updateMapEndOffset(appendInfo.lastOffset + 1)

      // update the first unstable offset (which is used to compute LSO)
      maybeIncrementFirstUnstableOffset()

      trace(s"Appended message set with last offset: ${appendInfo.lastOffset}, " +
        s"first offset: ${appendInfo.firstOffset}, " +
        s"next offset: ${nextOffsetMetadata.messageOffset}, " +
        s"and messages: $validRecords")

      // 将以上写入到文件进行flush操作
      if (unflushedMessages >= config.flushInterval)
        flush()

      appendInfo
    }
  }
}
```



## 事务机制

![Kafka Transactions Data Flow](images/Kafka Transactions Data Flow.png)

**Kafka的事务机制是通过写日志的方式实现的，事务操作的单元是leaderParitition，采用synchronized锁保护临界区。**

**事务状态在内存中有缓存，采用读写锁的方式保护，写入日志成功后回调更新内存中事务的缓存信息；**

**写入日志成功后会更新HW，HW更新是所有分区副本（必须是ISR或者允许lag范围内的）中LEO的最小值。**

#### 客户端流程

##### 步骤1-发起事务

调用方法为initializeTransactions，具体步骤如下：

1) 切换本地事务状态由UNINITIALIZED->INITIALIZING

2) 发送InitProducerIdRequest请求，使用闭锁方式同步等待结果，获取PID和epoch

```java
// InitProducerIdRequestData
new Schema(
  new Field("transactional_id", Type.NULLABLE_STRING, "The transactional id, or null if the producer is not transactional."),
  new Field("transaction_timeout_ms", Type.INT32, "The time in ms to wait for before aborting idle transactions sent by this producer.")
);
// InitProducerIdResponseData
new Schema(
  new Field("throttle_time_ms", Type.INT32, "The duration in milliseconds for which the request was throttled due to a quota violation, or zero if the request did not violate any quota."),
  new Field("error_code", Type.INT16, "The error code, or 0 if there was no error."),
  new Field("producer_id", Type.INT64, "The current producer id."),
  new Field("producer_epoch", Type.INT16, "The current epoch associated with the producer id.")
);
```

3) 处理请求InitPidRequest返回结果：第一次发起请求服务端会报NOT_COORDINATOR错，这个时候客户端发起FindCoordinatorRequest请求，异步返回协调者的地址信息

```java
// 查找协调器请求
new Schema(
	new Field("key", Type.STRING, "The coordinator key: 这里指的是生产者事务ID或者消费者组ID"),
	new Field("key_type", Type.INT8, "The coordinator key type.  (Group, transaction, etc.)")
);

// 协调器请求结果
new Schema(
  new Field("throttle_time_ms", Type.INT32, "The duration in milliseconds for which the request was throttled due to a quota violation, or zero if the request did not violate any quota."),
  new Field("error_code", Type.INT16, "The error code, or 0 if there was no error."),
  new Field("error_message", Type.NULLABLE_STRING, "The error message, or null if there was no error."),
  new Field("node_id", Type.INT32, "The node id."),
  new Field("host", Type.STRING, "The host name."),
  new Field("port", Type.INT32, "The port.")
);
```

InitPidRequest会返回producerId和epoch（注意这里的epoch应该是leader partition的epoch）

##### 步骤2-开启事务

org.apache.kafka.clients.producer.internals.TransactionManager#beginTransaction

a）将客户端状态由INITIALIZING->IN_TRANSACTION 

这个时候服务端并未开始事务，只有生产者发送一条数据到服务端才开始

##### 步骤3-发送数据

发送数据时会生成MemoryRecords，此记录会标明是否为事务数据。

在org.apache.kafka.clients.producer.KafkaProducer#doSend的发送流程中会将事务消息加入到 newPartitionsInTransaction Set集合中。

这样在实际发送消息时，Sender线程会发送AddPartitionsToTxnRequest事务请求，也就是说至于发送数据时kafka才会将要发往的分区信息提取出来然后发送到事务协调器加入到事务中。

1) org.apache.kafka.clients.producer.internals.Sender#runOnce

2) org.apache.kafka.clients.producer.internals.Sender#maybeSendAndPollTransactionalRequest

发送AddOffsetCommitsToTxnRequest：

如果消费者也参与到事务中，会批量的将所涉及到的消费者分区offset写到__consumer_offsets Topic的事务中。采用取模的方式获取内部topic的leader partition。

##### 步骤4-提交事务



提交事务到



事务协调者会查找涉及到事务所有Partition的leader节点并发送事务结束消息

```java
WriteTxnMarkersRequest
```



##### 步骤5-回滚事务



org.apache.kafka.clients.producer.internals.TransactionManager#beginCommit

org.apache.kafka.clients.producer.internals.TransactionManager#beginCompletingTransaction

org.apache.kafka.clients.producer.internals.TransactionManager#enqueueRequest

org.apache.kafka.clients.producer.internals.TransactionManager.FindCoordinatorHandler#handleResponse



```java
// 结束事务的请求格式
new Schema(
  new Field("transactional_id", Type.STRING, "The ID of the transaction to end."),
  new Field("producer_id", Type.INT64, "The producer ID."),
  new Field("producer_epoch", Type.INT16, "The current epoch associated with the producer."),
  new Field("committed", Type.BOOLEAN, "True if the transaction was committed, false if it was aborted.")
);
```

发送EndTxnRequest结束事务



#### 服务端流程

服务端收到FIND_COORDINATOR会在topic __transaction_state的所有分区中分配分区Id给此事务：

分区分配策略如下：

```java
def partitionFor(transactionalId: String): Int = Utils.abs(transactionalId.hashCode) % transactionTopicPartitionCount
```

然后找到此分区的leader信息返回给客户端。

服务端收到handleInitProducerIdRequest请求后，解析流程如下：

解析请求，生成producerId并把事务状态、事务的topicPartition信息写入到事务日志中。写到日志中的数据主要为：

```java
// value:参与到该事务的topic、事务状态和分区信息
private object ValueSchema {
  private val ProducerIdKey = "producer_id"
    private val ProducerEpochKey = "producer_epoch"
    private val TxnTimeoutKey = "transaction_timeout"
    private val TxnStatusKey = "transaction_status"
    private val TxnPartitionsKey = "transaction_partitions"
    private val TxnEntryTimestampKey = "transaction_entry_timestamp"
    private val TxnStartTimestampKey = "transaction_start_timestamp"

    private val PartitionIdsKey = "partition_ids"
    private val TopicKey = "topic"
}

// key
private object KeySchema {
  private val TXN_ID_KEY = "transactional_id"

}      
public SimpleRecord(long timestamp, ByteBuffer key, ByteBuffer value) {
  this(timestamp, key, value, Record.EMPTY_HEADERS);
}

// 事务中的内容，有value和key，value和key的模型见上，需要将其序列化成字节数组写到日志文件中
public SimpleRecord(long timestamp, byte[] key, byte[] value) {
  this(timestamp, Utils.wrapNullable(key), Utils.wrapNullable(value));
}

val topicPartition = new TopicPartition(Topic.TRANSACTION_STATE_TOPIC_NAME, partitionFor(transactionalId))
// 事务中所有的元数据，key为事务topic的名称和分配给此topic的分区Id,value为事务具体元数据
val recordsPerPartition = Map(topicPartition -> records)
```





## 网络收发流程



### 网络收发包整体流程



此章节从Kafka的网络模型、网络的连接等整体流程进行源码的解析。

#### Selector

org.apache.kafka.common.network.Selector

Selector是网络模型，包括**客户端、服务端**用的主流网络模型。Kafka对原生的kafka selector进行了封装。注意这里并没有使用netty模型。

```java
public Selector(int maxReceiveSize,
        long connectionMaxIdleMs,
        int failedAuthenticationDelayMs,
        Metrics metrics,
        Time time,
        String metricGrpPrefix,
        Map<String, String> metricTags,
        boolean metricsPerConnection,
        boolean recordTimePerConnection,
        ChannelBuilder channelBuilder,
        MemoryPool memoryPool,
        LogContext logContext) {
    try {
      	// 获取nio selector实现
        this.nioSelector = java.nio.channels.Selector.open();
    } catch (IOException e) {
        throw new KafkaException(e);
    }
  	// 能接受的报文大小，默认是没有限制的。
    this.maxReceiveSize = maxReceiveSize;
  	// kafka的时间工具
    this.time = time;
  	// kafka的客户端channel，nodeId->KafkaChannel
    this.channels = new HashMap<>();
    this.explicitlyMutedChannels = new HashSet<>();
    this.outOfMemory = false;
  	// 已经完成的发送消息和接收完毕的消息，便于后续处理
    this.completedSends = new ArrayList<>();
    this.completedReceives = new LinkedHashMap<>();
    this.immediatelyConnectedKeys = new HashSet<>();
    this.closingChannels = new HashMap<>();
    this.keysWithBufferedRead = new HashSet<>();
  	// 已经建立连接的nodeId,指的是broker的id
    this.connected = new ArrayList<>();
    this.disconnected = new HashMap<>();
  	// 通道关闭后发送失败的目的节点列表
    this.failedSends = new ArrayList<>();
    this.log = logContext.logger(Selector.class);
    this.sensors = new SelectorMetrics(metrics, metricGrpPrefix, metricTags, metricsPerConnection);
  	// KafkaChanne的构建工具
    this.channelBuilder = channelBuilder;
    this.recordTimePerConnection = recordTimePerConnection;
  	// 对于空闲连接的管理器，selector会在Poll是定时处理失效连接，超过指定时间connectionMaxIdleMs会关闭此连接
  	// 对于空闲连接的处理使用了LinkedHashmap的lru实现。
    this.idleExpiryManager = connectionMaxIdleMs < 0 ? null : new IdleExpiryManager(time, connectionMaxIdleMs);
    this.memoryPool = memoryPool;
    this.lowMemThreshold = (long) (0.1 * this.memoryPool.size());
    this.failedAuthenticationDelayMs = failedAuthenticationDelayMs;
    this.delayedClosingChannels = (failedAuthenticationDelayMs > NO_FAILED_AUTHENTICATION_DELAY) ? new LinkedHashMap<String, DelayedAuthenticationFailureClose>() : null;
}
```



##### poll

poll是网络io的主流程，包括连接的处理、数据的读写流程。

```java
public void poll(long timeout) throws IOException {
    if (timeout < 0)
        throw new IllegalArgumentException("timeout should be >= 0");

    boolean madeReadProgressLastCall = madeReadProgressLastPoll;
    clear();

    boolean dataInBuffers = !keysWithBufferedRead.isEmpty();
		// 对于某些特定的连接，不设置select超时时间
    if (!immediatelyConnectedKeys.isEmpty() || (madeReadProgressLastCall && dataInBuffers))
        timeout = 0;
		// 处理之前由于内存不足导致饱和状态的channel，将其设置为未饱和状态
    if (!memoryPool.isOutOfMemory() && outOfMemory) {
        //we have recovered from memory pressure. unmute any channel not explicitly muted for other reasons
        log.trace("Broker no longer low on memory - unmuting incoming sockets");
        for (KafkaChannel channel : channels.values()) {
            if (channel.isInMutableState() && !explicitlyMutedChannels.contains(channel)) {
                channel.maybeUnmute();
            }
        }
        outOfMemory = false;
    }

    /* check ready keys */
  	/* 调用原生的select并将其记录到传感器中 */
    long startSelect = time.nanoseconds();
    int numReadyKeys = select(timeout);
    long endSelect = time.nanoseconds();
    this.sensors.selectTime.record(endSelect - startSelect, time.milliseconds());

  	/* 处理已经ready的网络事件 */
    if (numReadyKeys > 0 || !immediatelyConnectedKeys.isEmpty() || dataInBuffers) {
      	// 获取所有的select keys
        Set<SelectionKey> readyKeys = this.nioSelector.selectedKeys();

        // Poll from channels that have buffered data (but nothing more from the underlying socket)
        if (dataInBuffers) {
            keysWithBufferedRead.removeAll(readyKeys); //so no channel gets polled twice
            Set<SelectionKey> toPoll = keysWithBufferedRead;
            keysWithBufferedRead = new HashSet<>(); //poll() calls will repopulate if needed
            pollSelectionKeys(toPoll, false, endSelect);
        }

        // Poll from channels where the underlying socket has more data
        pollSelectionKeys(readyKeys, false, endSelect);
        // Clear all selected keys so that they are included in the ready count for the next select
        readyKeys.clear();

        pollSelectionKeys(immediatelyConnectedKeys, true, endSelect);
        immediatelyConnectedKeys.clear();
    } else {
        madeReadProgressLastPoll = true; //no work is also "progress"
    }

    long endIo = time.nanoseconds();
    this.sensors.ioTime.record(endIo - endSelect, time.milliseconds());

    // Close channels that were delayed and are now ready to be closed
    completeDelayedChannelClose(endIo);

    // we use the time at the end of select to ensure that we don't close any connections that
    // have just been processed in pollSelectionKeys
  	// 处理过期的连接-超出了空闲时间的连接
    maybeCloseOldestConnection(endSelect);
}
```



##### pollSelectionKeys

```java
void pollSelectionKeys(Set<SelectionKey> selectionKeys,
                       boolean isImmediatelyConnected,
                       long currentTimeNanos) {
    for (SelectionKey key : determineHandlingOrder(selectionKeys)) {
      	// kafkaChannel在Key的attachment中
        KafkaChannel channel = channel(key);
        long channelStartTimeNanos = recordTimePerConnection ? time.nanoseconds() : 0;
        boolean sendFailed = false;
        String nodeId = channel.id();

        // register all per-connection metrics at once
        sensors.maybeRegisterConnectionMetrics(nodeId);
      	// 更新对端节点的失效时间：凡是有key事件的channel说明其在使用中
        if (idleExpiryManager != null)
            idleExpiryManager.update(nodeId, currentTimeNanos);

        try {
            /* complete any connections that have finished their handshake (either normally or immediately) */
          	// channel是否处于可连接住在状态，如果已经完成了连接需要注册读事件到selector中
            if (isImmediatelyConnected || key.isConnectable()) {
              	// 如果已经完成了连接需要注册读事件到selector中，并更新kafkaChannel的状态
                if (channel.finishConnect()) {
                    this.connected.add(nodeId);
                    this.sensors.connectionCreated.record();

                    SocketChannel socketChannel = (SocketChannel) key.channel();
                    log.debug("Created socket with SO_RCVBUF = {}, SO_SNDBUF = {}, SO_TIMEOUT = {} to node {}",
                            socketChannel.socket().getReceiveBufferSize(),
                            socketChannel.socket().getSendBufferSize(),
                            socketChannel.socket().getSoTimeout(),
                            nodeId);
                } else {
                    continue;
                }
            }

            /* if channel is not ready finish prepare */
            if (channel.isConnected() && !channel.ready()) {
                channel.prepare();
                if (channel.ready()) {
                    long readyTimeMs = time.milliseconds();
                    boolean isReauthentication = channel.successfulAuthentications() > 1;
                    if (isReauthentication) {
                        sensors.successfulReauthentication.record(1.0, readyTimeMs);
                        if (channel.reauthenticationLatencyMs() == null)
                            log.warn(
                                "Should never happen: re-authentication latency for a re-authenticated channel was null; continuing...");
                        else
                            sensors.reauthenticationLatency
                                .record(channel.reauthenticationLatencyMs().doubleValue(), readyTimeMs);
                    } else {
                        sensors.successfulAuthentication.record(1.0, readyTimeMs);
                        if (!channel.connectedClientSupportsReauthentication())
                            sensors.successfulAuthenticationNoReauth.record(1.0, readyTimeMs);
                    }
                    log.debug("Successfully {}authenticated with {}", isReauthentication ?
                        "re-" : "", channel.socketDescription());
                }
            }
          
            if (channel.ready() && channel.state() == ChannelState.NOT_CONNECTED)
                channel.state(ChannelState.READY);
            Optional<NetworkReceive> responseReceivedDuringReauthentication = channel.pollResponseReceivedDuringReauthentication();
            responseReceivedDuringReauthentication.ifPresent(receive -> {
                long currentTimeMs = time.milliseconds();
                addToCompletedReceives(channel, receive, currentTimeMs);
            });

            //if channel is ready and has bytes to read from socket or buffer, and has no
            //previous completed receive then read from it
          	// 核心流程：处理读请求
            if (channel.ready() && (key.isReadable() || channel.hasBytesBuffered()) && !hasCompletedReceive(channel)
                    && !explicitlyMutedChannels.contains(channel)) {
                attemptRead(channel);
            }

            if (channel.hasBytesBuffered()) {
                //this channel has bytes enqueued in intermediary buffers that we could not read
                //(possibly because no memory). it may be the case that the underlying socket will
                //not come up in the next poll() and so we need to remember this channel for the
                //next poll call otherwise data may be stuck in said buffers forever. If we attempt
                //to process buffered data and no progress is made, the channel buffered status is
                //cleared to avoid the overhead of checking every time.
                keysWithBufferedRead.add(key);
            }

            /* if channel is ready write to any sockets that have space in their buffer and for which we have data */

          	// 核心流程：处理写请求
            long nowNanos = channelStartTimeNanos != 0 ? channelStartTimeNanos : currentTimeNanos;
            try {
                attemptWrite(key, channel, nowNanos);
            } catch (Exception e) {
                sendFailed = true;
                throw e;
            }

            /* cancel any defunct sockets */
            if (!key.isValid())
                close(channel, CloseMode.GRACEFUL);

        } catch (Exception e) {
            String desc = channel.socketDescription();
            if (e instanceof IOException) {
                log.debug("Connection with {} disconnected", desc, e);
            } else if (e instanceof AuthenticationException) {
                boolean isReauthentication = channel.successfulAuthentications() > 0;
                if (isReauthentication)
                    sensors.failedReauthentication.record();
                else
                    sensors.failedAuthentication.record();
                String exceptionMessage = e.getMessage();
                if (e instanceof DelayedResponseAuthenticationException)
                    exceptionMessage = e.getCause().getMessage();
                log.info("Failed {}authentication with {} ({})", isReauthentication ? "re-" : "",
                    desc, exceptionMessage);
            } else {
                log.warn("Unexpected error from {}; closing connection", desc, e);
            }

            if (e instanceof DelayedResponseAuthenticationException)
                maybeDelayCloseOnAuthenticationFailure(channel);
            else
                close(channel, sendFailed ? CloseMode.NOTIFY_ONLY : CloseMode.GRACEFUL);
        } finally {
            maybeRecordTimePerConnection(channel, channelStartTimeNanos);
        }
    }
}
```



#### 报文处理

前面的selector流程讲解了报文如何在底层接收、发送的；而对于报文发送、接收后的处理在kafka的客户端和服务端的处理流程是不一样的。

客户端的处理实现在NetworkClient的poll流程中，server端在SocketServer的run流程中

##### 客户端

org.apache.kafka.clients.NetworkClient#poll

```java
public List<ClientResponse> poll(long timeout, long now) {
    ensureActive();

    if (!abortedSends.isEmpty()) {
        // If there are aborted sends because of unsupported version exceptions or disconnects,
        // handle them immediately without waiting for Selector#poll.
        List<ClientResponse> responses = new ArrayList<>();
        handleAbortedSends(responses);
        completeResponses(responses);
        return responses;
    }

  	// 向broker发送metadata消息请求并放到本地缓存中
    long metadataTimeout = metadataUpdater.maybeUpdate(now);
    try {
        this.selector.poll(Utils.min(timeout, metadataTimeout, defaultRequestTimeoutMs));
    } catch (IOException e) {
        log.error("Unexpected error during I/O", e);
    }

    // process completed actions
    long updatedNow = this.time.milliseconds();
    List<ClientResponse> responses = new ArrayList<>();
  	// 处理完成的发送请求
    handleCompletedSends(responses, updatedNow);
  	// 处理完成的接收请求
    handleCompletedReceives(responses, updatedNow);
    handleDisconnections(responses, updatedNow);
    handleConnections();
    handleInitiateApiVersionRequests(updatedNow);
    handleTimedOutRequests(responses, updatedNow);
    completeResponses(responses);

    return responses;
}
```



处理接收完的消息

```java
private void handleCompletedReceives(List<ClientResponse> responses, long now) {
    for (NetworkReceive receive : this.selector.completedReceives()) {
        String source = receive.source();
      	// inFlightRequest记录了当前发送的请求的元数据信息：包括消息的版本、请求结构、返回结构等及回调处理。
      	// 具体见InFlightRequest的讲解
      	// 目的是为了能将消息回应者返回的数据正确反序列化
        InFlightRequest req = inFlightRequests.completeNext(source);
      	// 反序列化返回数据，这里用到的关键元数据是ApiKeys
        Struct responseStruct = parseStructMaybeUpdateThrottleTimeMetrics(receive.payload(), req.header,
            throttleTimeSensor, now);
        if (log.isTraceEnabled()) {
            log.trace("Completed receive from node {} for {} with correlation id {}, received {}", req.destination,
                req.header.apiKey(), req.header.correlationId(), responseStruct);
        }
        // If the received response includes a throttle delay, throttle the connection.
        AbstractResponse body = AbstractResponse.
                parseResponse(req.header.apiKey(), responseStruct, req.header.apiVersion());
        maybeThrottle(body, req.header.apiVersion(), req.destination, now);
        if (req.isInternalRequest && body instanceof MetadataResponse)
            metadataUpdater.handleSuccessfulResponse(req.header, now, (MetadataResponse) body);
        else if (req.isInternalRequest && body instanceof ApiVersionsResponse)
            handleApiVersionsResponse(responses, req, now, (ApiVersionsResponse) body);
        else
            responses.add(req.completed(body, now));
    }
}
```



InFlightRequest





##### 服务端

kafka.network.Processor#run

```scala
override def run(): Unit = {
  startupComplete()
  try {
    while (isRunning) {
      try {
        // setup any new connections that have been queued up
        configureNewConnections()
        // register any new responses for writing
        processNewResponses()
        poll()
        processCompletedReceives()
        processCompletedSends()
        processDisconnected()
        closeExcessConnections()
      } catch {
        // We catch all the throwables here to prevent the processor thread from exiting. We do this because
        // letting a processor exit might cause a bigger impact on the broker. This behavior might need to be
        // reviewed if we see an exception that needs the entire broker to stop. Usually the exceptions thrown would
        // be either associated with a specific socket channel or a bad request. These exceptions are caught and
        // processed by the individual methods above which close the failing channel and continue processing other
        // channels. So this catch block should only ever see ControlThrowables.
        case e: Throwable => processException("Processor got uncaught exception.", e)
      }
    }
  } finally {
    debug(s"Closing selector - processor $id")
    CoreUtils.swallow(closeAll(), this, Level.ERROR)
    shutdownComplete()
  }
}
```



### 报文发送流程

#### attemptWrite

org.apache.kafka.common.network.Selector#attemptWrite

```java
private void attemptWrite(SelectionKey key, KafkaChannel channel, long nowNanos) throws IOException {
    if (channel.hasSend()
            && channel.ready()
            && key.isWritable()
            && !channel.maybeBeginClientReauthentication(() -> nowNanos)) {
        write(channel);
    }
}
```

org.apache.kafka.common.network.Selector#write



```java

void write(KafkaChannel channel) throws IOException {
    String nodeId = channel.id();
  	// 数据写入
    long bytesSent = channel.write();
 		// 将完成的发送请求加入到completedSends中
    Send send = channel.maybeCompleteSend();
    // We may complete the send with bytesSent < 1 if `TransportLayer.hasPendingWrites` was true and `channel.write()`
    // caused the pending writes to be written to the socket channel buffer
    if (bytesSent > 0 || send != null) {
        long currentTimeMs = time.milliseconds();
        if (bytesSent > 0)
            this.sensors.recordBytesSent(nodeId, bytesSent, currentTimeMs);
        if (send != null) {
            this.completedSends.add(send);
            this.sensors.recordCompletedSend(nodeId, send.size(), currentTimeMs);
        }
    }
}
```

#### NetworkSend

NetworkSend表示需要发送的消息的记录，包括发送消息的内容、目的地、发送消息的大小，还有多少消息没有发送，与之对应的是NetworkReceive。每次发包前先发送固定字节长度表示需要消息的大小是解决粘包问题的一种方案。

```java
public class NetworkSend extends ByteBufferSend {

    public NetworkSend(String destination, ByteBuffer buffer) {
        super(destination, sizeBuffer(buffer.remaining()), buffer);
    }

    private static ByteBuffer sizeBuffer(int size) {
        ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
      	// 注意这里所有的报文都是以4字节的int类型开始，表示消息的长度
        sizeBuffer.putInt(size);
        sizeBuffer.rewind();
        return sizeBuffer;
    }

}
```



```
public class ByteBufferSend implements Send {

    private final String destination;
    private final int size;
    protected final ByteBuffer[] buffers;
    private int remaining;
    private boolean pending = false;

    public ByteBufferSend(String destination, ByteBuffer... buffers) {
        this.destination = destination;
        this.buffers = buffers;
        for (ByteBuffer buffer : buffers)
            remaining += buffer.remaining();
        this.size = remaining;
    }

    @Override
    public String destination() {
        return destination;
    }

    @Override
    public boolean completed() {
        return remaining <= 0 && !pending;
    }

    @Override
    public long size() {
        return this.size;
    }

    @Override
    public long writeTo(GatheringByteChannel channel) throws IOException {
        long written = channel.write(buffers);
        if (written < 0)
            throw new EOFException("Wrote negative bytes to channel. This shouldn't happen.");
        remaining -= written;
        pending = TransportLayers.hasPendingWrites(channel);
        return written;
    }

    public long remaining() {
        return remaining;
    }
}
```

### 报文接收流程



#### selector-attemptRead

```java
private void attemptRead(KafkaChannel channel) throws IOException {
    String nodeId = channel.id();
		// 从网络底层读取字节流并生成NetworkReceive
    long bytesReceived = channel.read();
    if (bytesReceived != 0) {
        long currentTimeMs = time.milliseconds();
        sensors.recordBytesReceived(nodeId, bytesReceived, currentTimeMs);
        madeReadProgressLastPoll = true;
				
      	// 获取封装后的消息数据并将其放入到selector的字段completedReceives中便于后续对
      	// 完成的消息进行解析并回调处理
        NetworkReceive receive = channel.maybeCompleteReceive();
        if (receive != null) {
            addToCompletedReceives(channel, receive, currentTimeMs);
        }
    }
    if (channel.isMuted()) {
        outOfMemory = true; //channel has muted itself due to memory pressure.
    } else {
        madeReadProgressLastPoll = true;
    }
}
```



org.apache.kafka.common.network.KafkaChannel#read

```java
public long read() throws IOException {
    if (receive == null) {
        receive = new NetworkReceive(maxReceiveSize, id, memoryPool);
    }
		// 读取数据流
    long bytesReceived = receive(this.receive);

  	// 如果内存不足则设置channel的状态为Mute
    if (this.receive.requiredMemoryAmountKnown() && !this.receive.memoryAllocated() && isInMutableState()) {
        //pool must be out of memory, mute ourselves.
        mute();
    }
  
    return bytesReceived;
}
```



#### NetworkReceive



首先看下构造函数，

```java
public NetworkReceive(int maxSize, String source) {
    this.source = source; /* 发送消息的源端 */
    this.size = ByteBuffer.allocate(4); /* 报文的前四个字节 */
    this.buffer = null;					/* 接收消息的buffer */
    this.maxSize = maxSize;			/* 最大的消息大小 */
    this.memoryPool = MemoryPool.NONE;	/* 内存分配池 */
}
```

读取流程：

```java
public long readFrom(ScatteringByteChannel channel) throws IOException {
    int read = 0;
  	// 如果size还没有读入，则读入
    if (size.hasRemaining()) {
      	// 读取4个字节的size消息属性
        int bytesRead = channel.read(size);
        if (bytesRead < 0)
            throw new EOFException();
        read += bytesRead;
      	// 如果size读取结束则将bytebuffer的posion rewind便于读取实际的消息大小
        if (!size.hasRemaining()) {
            size.rewind();
          	// 实际的消息大小
            int receiveSize = size.getInt();
            if (receiveSize < 0)
                throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + ")");
            if (maxSize != UNLIMITED && receiveSize > maxSize)
                throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + " larger than " + maxSize + ")");
            requestedBufferSize = receiveSize; //may be 0 for some payloads (SASL)
            if (receiveSize == 0) {
                buffer = EMPTY_BUFFER;
            }
        }
    }
  	// 分配内存空间
    if (buffer == null && requestedBufferSize != -1) { //we know the size we want but havent been able to allocate it yet
        buffer = memoryPool.tryAllocate(requestedBufferSize);
        if (buffer == null)
            log.trace("Broker low on memory - could not allocate buffer of size {} for source {}", requestedBufferSize, source);
    }
  	// 读取消息内容
    if (buffer != null) {
        int bytesRead = channel.read(buffer);
        if (bytesRead < 0)
            throw new EOFException();
        read += bytesRead;
    }

    return read;
}
```



## Broker

### 常用配置文件



| 配置项         | 参数及含义                 | 描述 |
| -------------- | -------------------------- | ---- |
| broker.id      | broker的唯一标识，整数类型 |      |
| log.dirs       | 日志文件的存储位置         |      |
| num.partitions | 每个topic的分区个数        |      |



### 启动流程

代码流程如下：

1) kafka.Kafka#main

2) kafka.server.KafkaServerStartable#startup

3) kafka.server.KafkaServer#startup

Kafka server端的启动流程主要逻辑在KafkaServer.scala类中。

kafka.server.KafkaServer#startup

```scala
def startup(): Unit = {
  try {
    info("starting")

    if (isShuttingDown.get)
      throw new IllegalStateException("Kafka server is still shutting down, cannot re-start!")

    if (startupComplete.get)
      return

    val canStartup = isStartingUp.compareAndSet(false, true)
    if (canStartup) {
      brokerState.newState(Starting)

      /* setup zookeeper */
      initZkClient(time)

      /* Get or create cluster_id */
      _clusterId = getOrGenerateClusterId(zkClient)
      info(s"Cluster ID = $clusterId")

      /* load metadata */
      val (preloadedBrokerMetadataCheckpoint, initialOfflineDirs) = getBrokerMetadataAndOfflineDirs

      /* check cluster id */
      if (preloadedBrokerMetadataCheckpoint.clusterId.isDefined && preloadedBrokerMetadataCheckpoint.clusterId.get != clusterId)
        throw new InconsistentClusterIdException(
          s"The Cluster ID ${clusterId} doesn't match stored clusterId ${preloadedBrokerMetadataCheckpoint.clusterId} in meta.properties. " +
          s"The broker is trying to join the wrong cluster. Configured zookeeper.connect may be wrong.")

      /* generate brokerId */
      config.brokerId = getOrGenerateBrokerId(preloadedBrokerMetadataCheckpoint)
      logContext = new LogContext(s"[KafkaServer id=${config.brokerId}] ")
      this.logIdent = logContext.logPrefix

      // initialize dynamic broker configs from ZooKeeper. Any updates made after this will be
      // applied after DynamicConfigManager starts.
      config.dynamicConfig.initialize(zkClient)

      /* start scheduler */
      kafkaScheduler = new KafkaScheduler(config.backgroundThreads)
      kafkaScheduler.startup()

      /* create and configure metrics */
      val reporters = new util.ArrayList[MetricsReporter]
      reporters.add(new JmxReporter(jmxPrefix))
      val metricConfig = KafkaServer.metricConfig(config)
      metrics = new Metrics(metricConfig, reporters, time, true)

      /* register broker metrics */
      _brokerTopicStats = new BrokerTopicStats

      quotaManagers = QuotaFactory.instantiate(config, metrics, time, threadNamePrefix.getOrElse(""))
      notifyClusterListeners(kafkaMetricsReporters ++ metrics.reporters.asScala)

      logDirFailureChannel = new LogDirFailureChannel(config.logDirs.size)

      /* start log manager */
      logManager = LogManager(config, initialOfflineDirs, zkClient, brokerState, kafkaScheduler, time, brokerTopicStats, logDirFailureChannel)
      logManager.startup()

      metadataCache = new MetadataCache(config.brokerId)
      // Enable delegation token cache for all SCRAM mechanisms to simplify dynamic update.
      // This keeps the cache up-to-date if new SCRAM mechanisms are enabled dynamically.
      tokenCache = new DelegationTokenCache(ScramMechanism.mechanismNames)
      credentialProvider = new CredentialProvider(ScramMechanism.mechanismNames, tokenCache)

      // Create and start the socket server acceptor threads so that the bound port is known.
      // Delay starting processors until the end of the initialization sequence to ensure
      // that credentials have been loaded before processing authentications.
      // 启动数据面和控制面处理线程，其网络模型复用selector逻辑
      socketServer = new SocketServer(config, metrics, time, credentialProvider)
      socketServer.startup(startupProcessors = false)

      /* start replica manager */
      // kafa副本管理器，使用scheduler进行定时同步
      replicaManager = createReplicaManager(isShuttingDown)
      replicaManager.startup()

      val brokerInfo = createBrokerInfo
      val brokerEpoch = zkClient.registerBroker(brokerInfo)

      // Now that the broker is successfully registered, checkpoint its metadata
      checkpointBrokerMetadata(BrokerMetadata(config.brokerId, Some(clusterId)))

      /* start token manager */
      tokenManager = new DelegationTokenManager(config, tokenCache, time , zkClient)
      tokenManager.startup()

      /* 启动kafka控制器，其相当于master角色 */
      kafkaController = new KafkaController(config, zkClient, time, metrics, brokerInfo, brokerEpoch, tokenManager, threadNamePrefix)
      kafkaController.startup()

      adminManager = new AdminManager(config, metrics, metadataCache, zkClient)

      /* start group coordinator */
      // Hardcode Time.SYSTEM for now as some Streams tests fail otherwise, it would be good to fix the underlying issue
      // 启动组协调器
      groupCoordinator = GroupCoordinator(config, zkClient, replicaManager, Time.SYSTEM, metrics)
      groupCoordinator.startup()

      /* start transaction coordinator, with a separate background thread scheduler for transaction expiration and log loading */
      // Hardcode Time.SYSTEM for now as some Streams tests fail otherwise, it would be good to fix the underlying issue
      // 启动事务协调器
      transactionCoordinator = TransactionCoordinator(config, replicaManager, new KafkaScheduler(threads = 1, threadNamePrefix = "transaction-log-manager-"), zkClient, metrics, metadataCache, Time.SYSTEM)
      transactionCoordinator.startup()

      /* Get the authorizer and initialize it if one is specified.*/
      authorizer = config.authorizer
      authorizer.foreach(_.configure(config.originals))
      val authorizerFutures: Map[Endpoint, CompletableFuture[Void]] = authorizer match {
        case Some(authZ) =>
          authZ.start(brokerInfo.broker.toServerInfo(clusterId, config)).asScala.mapValues(_.toCompletableFuture).toMap
        case None =>
          brokerInfo.broker.endPoints.map { ep => ep.toJava -> CompletableFuture.completedFuture[Void](null) }.toMap
      }

      val fetchManager = new FetchManager(Time.SYSTEM,
        new FetchSessionCache(config.maxIncrementalFetchSessionCacheSlots,
          KafkaServer.MIN_INCREMENTAL_FETCH_SESSION_EVICTION_MS))

      /*  */
      dataPlaneRequestProcessor = new KafkaApis(socketServer.dataPlaneRequestChannel, replicaManager, adminManager, groupCoordinator, transactionCoordinator,
        kafkaController, zkClient, config.brokerId, config, metadataCache, metrics, authorizer, quotaManagers,
        fetchManager, brokerTopicStats, clusterId, time, tokenManager)

      dataPlaneRequestHandlerPool = new KafkaRequestHandlerPool(config.brokerId, socketServer.dataPlaneRequestChannel, dataPlaneRequestProcessor, time,
        config.numIoThreads, s"${SocketServer.DataPlaneMetricPrefix}RequestHandlerAvgIdlePercent", SocketServer.DataPlaneThreadPrefix)

      socketServer.controlPlaneRequestChannelOpt.foreach { controlPlaneRequestChannel =>
        controlPlaneRequestProcessor = new KafkaApis(controlPlaneRequestChannel, replicaManager, adminManager, groupCoordinator, transactionCoordinator,
          kafkaController, zkClient, config.brokerId, config, metadataCache, metrics, authorizer, quotaManagers,
          fetchManager, brokerTopicStats, clusterId, time, tokenManager)

        controlPlaneRequestHandlerPool = new KafkaRequestHandlerPool(config.brokerId, socketServer.controlPlaneRequestChannelOpt.get, controlPlaneRequestProcessor, time,
          1, s"${SocketServer.ControlPlaneMetricPrefix}RequestHandlerAvgIdlePercent", SocketServer.ControlPlaneThreadPrefix)
      }

      Mx4jLoader.maybeLoad()

      /* Add all reconfigurables for config change notification before starting config handlers */
      config.dynamicConfig.addReconfigurables(this)

      /* start dynamic config manager */
      dynamicConfigHandlers = Map[String, ConfigHandler](ConfigType.Topic -> new TopicConfigHandler(logManager, config, quotaManagers, kafkaController),
                                                         ConfigType.Client -> new ClientIdConfigHandler(quotaManagers),
                                                         ConfigType.User -> new UserConfigHandler(quotaManagers, credentialProvider),
                                                         ConfigType.Broker -> new BrokerConfigHandler(config, quotaManagers))

      // Create the config manager. start listening to notifications
      dynamicConfigManager = new DynamicConfigManager(zkClient, dynamicConfigHandlers)
      dynamicConfigManager.startup()

      socketServer.startControlPlaneProcessor(authorizerFutures)
      socketServer.startDataPlaneProcessors(authorizerFutures)
      brokerState.newState(RunningAsBroker)
      shutdownLatch = new CountDownLatch(1)
      startupComplete.set(true)
      isStartingUp.set(false)
      AppInfoParser.registerAppInfo(jmxPrefix, config.brokerId.toString, metrics, time.milliseconds())
      info("started")
    }
  }
  catch {
    case e: Throwable =>
      fatal("Fatal error during KafkaServer startup. Prepare to shutdown", e)
      isStartingUp.set(false)
      shutdown()
      throw e
  }
}
```

### 组协调器



### 事务管理器





### 控制器(controller)

#### KafkaController

```java
def startup() = {
  zkClient.registerStateChangeHandler(new StateChangeHandler {
    override val name: String = StateChangeHandlers.ControllerHandler
    override def afterInitializingSession(): Unit = {
      eventManager.put(RegisterBrokerAndReelect)
    }
    override def beforeInitializingSession(): Unit = {
      val queuedEvent = eventManager.clearAndPut(Expire)

      // Block initialization of the new session until the expiration event is being handled,
      // which ensures that all pending events have been processed before creating the new session
      queuedEvent.awaitProcessing()
    }
  })
  eventManager.put(Startup)
  eventManager.start()
}
```



## 消费者启动流程

KafkaConsumer

kafka在初始化过程中有几个比较重要的初始化过程，以下代码做了精简，突出了重点内容。总结下来，主要做以下几件事情：

1.

2.生成subscriptions。也就是订阅者的信息追踪类

3.assignors。主要用来消费者的分区划分策略

4.coordinator。组协调器，负责和server端进行心跳信息交互，完成消费者组的选主、分区划分、元数据更新等任务

5.fetcher

```java
private KafkaConsumer(ConsumerConfig config,
                      Deserializer<K> keyDeserializer,
                      Deserializer<V> valueDeserializer) {
    try {
		......
      
        this.metadata = new Metadata(retryBackoffMs, config.getLong(ConsumerConfig.METADATA_MAX_AGE_CONFIG),
                true, false, clusterResourceListeners);
		......

        NetworkClient netClient = new NetworkClient(
                new Selector(config.getLong(ConsumerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG), metrics, time, metricGrpPrefix, channelBuilder, logContext),
                this.metadata,
                clientId,
                100, // a fixed large enough value will suffice for max in-flight requests
                config.getLong(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG),
                config.getLong(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG),
                config.getInt(ConsumerConfig.SEND_BUFFER_CONFIG),
                config.getInt(ConsumerConfig.RECEIVE_BUFFER_CONFIG),
                config.getInt(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                time,
                true,
                new ApiVersions(),
                throttleTimeSensor,
                logContext);
        this.client = new ConsumerNetworkClient(
                logContext,
                netClient,
                metadata,
                time,
                retryBackoffMs,
                config.getInt(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                heartbeatIntervalMs); //Will avoid blocking an extended period of time to prevent heartbeat thread starvation
        OffsetResetStrategy offsetResetStrategy = OffsetResetStrategy.valueOf(config.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG).toUpperCase(Locale.ROOT));
        this.subscriptions = new SubscriptionState(offsetResetStrategy);
        this.assignors = config.getConfiguredInstances(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                PartitionAssignor.class);

        int maxPollIntervalMs = config.getInt(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
        int sessionTimeoutMs = config.getInt(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG);
        this.coordinator = new ConsumerCoordinator(logContext,
                this.client,
                groupId,
                maxPollIntervalMs,
                sessionTimeoutMs,
                new Heartbeat(sessionTimeoutMs, heartbeatIntervalMs, maxPollIntervalMs, retryBackoffMs),
                assignors,
                this.metadata,
                this.subscriptions,
                metrics,
                metricGrpPrefix,
                this.time,
                retryBackoffMs,
                config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG),
                config.getInt(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG),
                this.interceptors,
                config.getBoolean(ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG),
                config.getBoolean(ConsumerConfig.LEAVE_GROUP_ON_CLOSE_CONFIG));
        this.fetcher = new Fetcher<>(
                logContext,
                this.client,
                config.getInt(ConsumerConfig.FETCH_MIN_BYTES_CONFIG),
                config.getInt(ConsumerConfig.FETCH_MAX_BYTES_CONFIG),
                config.getInt(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG),
                config.getInt(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG),
                config.getInt(ConsumerConfig.MAX_POLL_RECORDS_CONFIG),
                config.getBoolean(ConsumerConfig.CHECK_CRCS_CONFIG),
                this.keyDeserializer,
                this.valueDeserializer,
                this.metadata,
                this.subscriptions,
                metrics,
                metricsRegistry.fetcherMetrics,
                this.time,
                this.retryBackoffMs,
                this.requestTimeoutMs,
                isolationLevel);

        config.logUnused();
        AppInfoParser.registerAppInfo(JMX_PREFIX, clientId, metrics);

        log.debug("Kafka consumer initialized");
    } catch (Throwable t) {
        // call close methods if internal objects are already constructed
        // this is to prevent resource leak. see KAFKA-2121
        close(0, true);
        // now propagate the exception
        throw new KafkaException("Failed to construct kafka consumer", t);
    }
}
```

## 副本机制



### 写日志流程

kafka本地日志流程：常见的写日志场景包括以下几种：

kafka.server.KafkaApis#handleProduceRequest	// 生产者发送数据写日志

kafka.coordinator.group.GroupMetadataManager#appendForGroup  // 消费者组提交内部topic

kafka.coordinator.transaction.TransactionStateManager#appendTransactionToLog  // 事务日志

以上三种场景底层流程如下：

写日志通用流程如下：

1) kafka.server.ReplicaManager#appendRecords

2) kafka.server.ReplicaManager#appendToLocalLog

3) kafka.cluster.Partition#appendRecordsToLeader

4.1) kafka.log.Log#appendAsLeader

4.2) kafka.cluster.Partition#maybeIncrementLeaderHW

### Log

```markdown
*关于设计*
Log日志对应于某个topic的partition，负责记录partition的日志信息，有以下重要的字段信息

	// 1. 表示追加消息到log中的偏移值
	@volatile private var nextOffsetMetadata: LogOffsetMetadata = _

  @volatile private var firstUnstableOffsetMetadata: Option[LogOffsetMetadata] = None

	// 2.高水位信息
  @volatile private var highWatermarkMetadata: LogOffsetMetadata = LogOffsetMetadata(logStartOffset)

	// 3.segmentlog由segment组成
  private val segments: ConcurrentNavigableMap[java.lang.Long, LogSegment] = new ConcurrentSkipListMap[java.lang.Long, LogSegment]

  // 分区的leader信息：epoch和offset
  @volatile var leaderEpochCache: Option[LeaderEpochFileCache] = None

```

kafka.server.KafkaApis#handleLeaderAndIsrRequest

kafka.server.ReplicaManager#becomeLeaderOrFollower

kafka.cluster.Partition#createLogIfNotExists

kafka.log.LogManager#getOrCreateLog

以下是getOrCreateLog中的部分代码，其中Log的初始化代码片段如下：

```scala
// log文件名：s"${topicPartition.topic}-${topicPartition.partition}"
val logDirName = {
  if (isFuture)
    Log.logFutureDirName(topicPartition)
  else
    Log.logDirName(topicPartition)
}

// 创建log文件
val logDir = logDirs
  .toStream // to prevent actually mapping the whole list, lazy map
  .map(createLogDirectory(_, logDirName))
  .find(_.isSuccess)
  .getOrElse(Failure(new KafkaStorageException("No log directories available. Tried " + logDirs.map(_.getAbsolutePath).mkString(", "))))
  .get // If Failure, will throw

/**
 * An append-only log for storing messages.
 *
 * The log is a sequence of LogSegments, each with a base offset denoting the first message in the segment.
 *
 * New log segments are created according to a configurable policy that controls the size in bytes or time interval
 * for a given segment.
 *
 * @param dir The directory in which log segments are created.
 * @param config The log configuration settings
 * @param logStartOffset The earliest offset allowed to be exposed to kafka client.
 *                       The logStartOffset can be updated by :
 *                       - user's DeleteRecordsRequest
 *                       - broker's log retention
 *                       - broker's log truncation
 *                       The logStartOffset is used to decide the following:
 *                       - Log deletion. LogSegment whose nextOffset <= log's logStartOffset can be deleted.
 *                         It may trigger log rolling if the active segment is deleted.
 *                       - Earliest offset of the log in response to ListOffsetRequest. To avoid OffsetOutOfRange exception after user seeks to earliest offset,
 *                         we make sure that logStartOffset <= log's highWatermark
 *                       Other activities such as log cleaning are not affected by logStartOffset.
 * @param recoveryPoint The offset at which to begin recovery--i.e. the first offset which has not been flushed to disk
 * @param scheduler The thread pool scheduler used for background actions
 * @param brokerTopicStats Container for Broker Topic Yammer Metrics
 * @param time The time instance used for checking the clock
 * @param maxProducerIdExpirationMs The maximum amount of time to wait before a producer id is considered expired
 * @param producerIdExpirationCheckIntervalMs How often to check for producer ids which need to be expired
 */
// 初始化log
val log = Log(
  dir = logDir,  // log文件名
  config = config, // log的配置，包括segment等
  logStartOffset = 0L, // 
  recoveryPoint = 0L,
  maxProducerIdExpirationMs = maxPidExpirationMs,  // 事务的失效时间
  producerIdExpirationCheckIntervalMs = LogManager.ProducerIdExpirationCheckIntervalMs,
  scheduler = scheduler,
  time = time,
  brokerTopicStats = brokerTopicStats,
  logDirFailureChannel = logDirFailureChannel)

// 最后将其放到缓存中
if (isFuture)
	futureLogs.put(topicPartition, log)
else
	currentLogs.put(topicPartition, log)


```

在Log实例化过程中，会进行静态初始化操作，locally类似于java中的static

```scala
locally {
  val startMs = time.milliseconds

  // create the log directory if it doesn't exist
  Files.createDirectories(dir.toPath)
	// 初始化leader-epoch-checkpoint文件:checkpoint文件会定时触发更新
  // checkpoint文件记录的是当前leader的epoch及对应的log offset（这里的offset指的是log end offset）
  // LeaderEpoch => Offsets的元数据信息
  initializeLeaderEpochCache()

  // 注意这里返回的是activeSegment的结尾偏移值，也就是下一次追加消息的偏移值
  val nextOffset = loadSegments()

  /* Calculate the offset of the next message */
  // 将当前segment的追加消息的offset、baseOffset和segment的大小组成元数据
  nextOffsetMetadata = LogOffsetMetadata(nextOffset, activeSegment.baseOffset, activeSegment.size)

  leaderEpochCache.foreach(_.truncateFromEnd(nextOffsetMetadata.messageOffset))

  updateLogStartOffset(math.max(logStartOffset, segments.firstEntry.getValue.baseOffset))

  // The earliest leader epoch may not be flushed during a hard failure. Recover it here.
  leaderEpochCache.foreach(_.truncateFromStart(logStartOffset))

  // Any segment loading or recovery code must not use producerStateManager, so that we can build the full state here
  // from scratch.
  if (!producerStateManager.isEmpty)
    throw new IllegalStateException("Producer state must be empty during log initialization")
  loadProducerState(logEndOffset, reloadFromCleanShutdown = hasCleanShutdownFile)

  info(s"Completed load of log with ${segments.size} segments, log start offset $logStartOffset and " +
    s"log end offset $logEndOffset in ${time.milliseconds() - startMs} ms")
}
```



## Controller

controller代码是kafka的非常重要的代码，需要我们深入学习。从某种意义上来说，它是kafka最核心的组件，一方面，他要为集群中的所有主题分区选取领导者副本；另一方面，它还承载着集群的全部元数据信息，并负责讲这些元数据信息同步到其他broker上。下面我们来一一讲解controller组件。

参考：

https://www.cnblogs.com/boanxin/p/13618431.html

## 工具类



### ConsumerConfig

这个类的主要作用是用来解析、配置、校验获取消费者端所支持的所有配置项。配置项可以用来根据配置参数的类型来解析成具体的java类型，比如list,class等，并且还会做参数的校验。

```java
static {
    // ConsumerConfig中的配置项定义
    CONFIG = new ConfigDef().define(BOOTSTRAP_SERVERS_CONFIG,
                                    Type.LIST,
                                    Collections.emptyList(),
                                    new ConfigDef.NonNullValidator(),
                                    Importance.HIGH,
                                    CommonClientConfigs.BOOTSTRAP_SERVERS_DOC)
        ......
}
```

有几个重要的类介绍一下：

**ConfigDef**

org.apache.kafka.common.config.ConfigDef

从这个类的初始化就可以看出，此类用来表示某一类的配置集合

```java

private final Map<String, ConfigKey> configKeys;
private final List<String> groups;
private Set<String> configsWithNoParent;

public ConfigDef() {
    configKeys = new LinkedHashMap<>();
    groups = new LinkedList<>();
    configsWithNoParent = null;
}
```

**Configkey**

configkey表示一个配置项的抽象，是配置工具类的最小功能单元。

```java
public static class ConfigKey {
    public final String name;
    public final Type type;
    public final String documentation;
    public final Object defaultValue;
    public final Validator validator;
    public final Importance importance;
    public final String group;
    public final int orderInGroup;
    public final Width width;
    public final String displayName;
    public final List<String> dependents;
    public final Recommender recommender;
    public final boolean internalConfig;

    public ConfigKey(String name, Type type, Object defaultValue, Validator validator,
                     Importance importance, String documentation, String group,
                     int orderInGroup, Width width, String displayName,
                     List<String> dependents, Recommender recommender,
                     boolean internalConfig) {
        this.name = name;
        this.type = type;
        this.defaultValue = NO_DEFAULT_VALUE.equals(defaultValue) ? NO_DEFAULT_VALUE : parseType(name, defaultValue, type);
        this.validator = validator;
        this.importance = importance;
        if (this.validator != null && hasDefault())
            this.validator.ensureValid(name, this.defaultValue);
        this.documentation = documentation;
        this.dependents = dependents;
        this.group = group;
        this.orderInGroup = orderInGroup;
        this.width = width;
        this.displayName = displayName;
        this.recommender = recommender;
        this.internalConfig = internalConfig;
    }

    public boolean hasDefault() {
        return !NO_DEFAULT_VALUE.equals(this.defaultValue);
    }
}
```






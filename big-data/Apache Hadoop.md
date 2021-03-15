



# HDFS



HDFS是一个分布式文件系统。HDFS是高容错的分布式文件系统，可以部署在低廉的机器上，可以存储大数据集，并且可以提供高吞吐。

## 使用场景

因为HDFS是一个文件系统，适合“一次写入，多次读取”的业务场景，适合数据的批处理。

## 架构



<img src="images/hdfs.png" alt="HDFS Architecture" style="zoom:75%;" />



## 文件操作

### 上传文件



```java
FileSystem fileSystem = FileSystem.get(uri, conf,fileSystemBase.getModifyUserName());

// 创建文件输出流
try (FSDataOutputStream fsout = fileSystem.create(new Path("xxx"), override, 4096)) {
  int readSize;
  byte[] buffer = new byte[4096];
  try (BufferedInputStream bfin = new BufferedInputStream(uploadReq.getInputStream())) {
    while ((readSize = bfin.read(buffer)) != -1) {
      // 写数据
      fsout.write(buffer, 0, readSize);
    }
    // 刷写数据
    fsout.flush();
  }
}


```



fsout.write(buffer, 0, readSize);这里写数据最终是调用org.apache.hadoop.hdfs.DFSOutputStream类，并且这个类继承了FSOutputSummer类。所以最后的写流程如下：

FSOutputSummer#write1

```java
private int write1(byte b[], int off, int len) throws IOException {
  if(count==0 && len>=buf.length) {
    // local buffer is empty and user buffer size >= local buffer size, so
    // simply checksum the user buffer and send it directly to the underlying
    // stream
    final int length = buf.length;
    writeChecksumChunks(b, off, length);
    return length;
  }
  
  // copy user data to local buffer
  int bytesToCopy = buf.length-count;
  bytesToCopy = (len<bytesToCopy) ? len : bytesToCopy;
  System.arraycopy(b, off, buf, count, bytesToCopy);
  count += bytesToCopy;
  if (count == buf.length) {
    // local buffer is full
    flushBuffer();
  } 
  return bytesToCopy;
}
```



### DFSClient

```java
public DFSClient(URI nameNodeUri, ClientProtocol rpcNamenode,
    Configuration conf, FileSystem.Statistics stats) throws IOException {
  // Copy only the required DFSClient configuration
  this.tracer = FsTracer.get(conf);
  this.dfsClientConf = new DfsClientConf(conf);
  this.conf = conf;
  this.stats = stats;
  this.socketFactory = NetUtils.getSocketFactory(conf, ClientProtocol.class);
  this.dtpReplaceDatanodeOnFailure = ReplaceDatanodeOnFailure.get(conf);
  this.smallBufferSize = DFSUtilClient.getSmallBufferSize(conf);
  this.dtpReplaceDatanodeOnFailureReplication = (short) conf
      .getInt(HdfsClientConfigKeys.BlockWrite.ReplaceDatanodeOnFailure.
              MIN_REPLICATION,
          HdfsClientConfigKeys.BlockWrite.ReplaceDatanodeOnFailure.
              MIN_REPLICATION_DEFAULT);
  if (LOG.isDebugEnabled()) {
    LOG.debug(
        "Sets " + HdfsClientConfigKeys.BlockWrite.ReplaceDatanodeOnFailure.
            MIN_REPLICATION + " to "
            + dtpReplaceDatanodeOnFailureReplication);
  }
  this.ugi = UserGroupInformation.getCurrentUser();

  this.namenodeUri = nameNodeUri;
  this.clientName = "DFSClient_" + dfsClientConf.getTaskId() + "_" +
      ThreadLocalRandom.current().nextInt()  + "_" +
      Thread.currentThread().getId();
  int numResponseToDrop = conf.getInt(
      DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY,
      DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_DEFAULT);
  ProxyAndInfo<ClientProtocol> proxyInfo = null;
  AtomicBoolean nnFallbackToSimpleAuth = new AtomicBoolean(false);

  if (numResponseToDrop > 0) {
    // This case is used for testing.
    LOG.warn(DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY
        + " is set to " + numResponseToDrop
        + ", this hacked client will proactively drop responses");
    proxyInfo = NameNodeProxiesClient.createProxyWithLossyRetryHandler(conf,
        nameNodeUri, ClientProtocol.class, numResponseToDrop,
        nnFallbackToSimpleAuth);
  }

  // 为了使能HA，这里使用一个代理类表示namenode
  if (proxyInfo != null) {
    this.dtService = proxyInfo.getDelegationTokenService();
    this.namenode = proxyInfo.getProxy();
  } else if (rpcNamenode != null) {
    // This case is used for testing.
    Preconditions.checkArgument(nameNodeUri == null);
    this.namenode = rpcNamenode;
    dtService = null;
  } else {
    Preconditions.checkArgument(nameNodeUri != null,
        "null URI");
    proxyInfo = NameNodeProxiesClient.createProxyWithClientProtocol(conf,
        nameNodeUri, nnFallbackToSimpleAuth);
    this.dtService = proxyInfo.getDelegationTokenService();
    this.namenode = proxyInfo.getProxy();
  }

  String localInterfaces[] =
      conf.getTrimmedStrings(DFS_CLIENT_LOCAL_INTERFACES);
  localInterfaceAddrs = getLocalInterfaceAddrs(localInterfaces);
  if (LOG.isDebugEnabled() && 0 != localInterfaces.length) {
    LOG.debug("Using local interfaces [" +
        Joiner.on(',').join(localInterfaces)+ "] with addresses [" +
        Joiner.on(',').join(localInterfaceAddrs) + "]");
  }

  Boolean readDropBehind =
      (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_READS) == null) ?
          null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_READS, false);
  Long readahead = (conf.get(DFS_CLIENT_CACHE_READAHEAD) == null) ?
      null : conf.getLong(DFS_CLIENT_CACHE_READAHEAD, 0);
  this.serverDefaultsValidityPeriod =
          conf.getLong(DFS_CLIENT_SERVER_DEFAULTS_VALIDITY_PERIOD_MS_KEY,
    DFS_CLIENT_SERVER_DEFAULTS_VALIDITY_PERIOD_MS_DEFAULT);
  Boolean writeDropBehind =
      (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES) == null) ?
          null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES, false);
  this.defaultReadCachingStrategy =
      new CachingStrategy(readDropBehind, readahead);
  this.defaultWriteCachingStrategy =
      new CachingStrategy(writeDropBehind, readahead);
  this.clientContext = ClientContext.get(
      conf.get(DFS_CLIENT_CONTEXT, DFS_CLIENT_CONTEXT_DEFAULT),
      dfsClientConf, conf);

  if (dfsClientConf.getHedgedReadThreadpoolSize() > 0) {
    this.initThreadsNumForHedgedReads(dfsClientConf.
        getHedgedReadThreadpoolSize());
  }

  this.initThreadsNumForStripedReads(dfsClientConf.
      getStripedReadThreadpoolSize());
  this.saslClient = new SaslDataTransferClient(
      conf, DataTransferSaslUtil.getSaslPropertiesResolver(conf),
      TrustedChannelResolver.getInstance(conf), nnFallbackToSimpleAuth);
}
```



NameNode的代理实现类设置：

```xml
<property>
  <name>dfs.client.failover.proxy.provider</name>
  <value>org.apache.hadoop.hdfs.server.namenode.ha.ConfiguredFailoverProxyProvider</value>
  <description>
    The prefix (plus a required nameservice ID) for the class name of the
    configured Failover proxy provider for the host.  For more detailed
    information, please consult the "Configuration Details" section of
    the HDFS High Availability documentation.
  </description>
</property>
```



```java
public static <T> Class<FailoverProxyProvider<T>> getFailoverProxyProviderClass(Configuration conf, URI nameNodeUri) throws IOException {
    if (nameNodeUri == null) {
        return null;
    } else {
        String host = nameNodeUri.getHost();
        String configKey = "dfs.client.failover.proxy.provider." + host;

        try {
            Class<FailoverProxyProvider<T>> ret = conf.getClass(configKey, (Class)null, FailoverProxyProvider.class);
            return ret;
        } catch (RuntimeException var5) {
            if (var5.getCause() instanceof ClassNotFoundException) {
                throw new IOException("Could not load failover proxy provider class " + conf.get(configKey) + " which is configured for authority " + nameNodeUri, var5);
            } else {
                throw var5;
            }
        }
    }
}
```



### 参考

https://zhuanlan.zhihu.com/p/61774441





# MapReduce

## MRAppMaster剖析



org.apache.hadoop.mapreduce.v2.app.MRAppMaster



参考：

https://www.jianshu.com/p/b81e4d9495d7



## 从wordcount看mapreduce





TextInputFormat负责split文件到多个mapper，mapper的划分由datablock的数量进行划分，这里的datablock可以理解为hdfs的block大小。



从hadoop的MapReduce appMaster来看，其具体逻辑实现比较复杂，如果想弄明白底层逻辑实现，关键点有如下几个：

1) mapreduce程序目前仅实现了yarn的调度，因此appMaster实现逻辑应大致清楚

2) mapreduce 过程分jobImpl -> TaskImpl -> TaskAttemptImpl三个主题逻辑，每一个主题逻辑都有相应的状态机，通过dispatch来管理事件并下发执行handler，然后落实到状态机的更变；

3) 两个入口类：

```
// job的入口执行类
org.apache.hadoop.mapreduce.v2.app.MRAppMaster

// job所有的task对应的入口实现类
org.apache.hadoop.mapred.YarnChild
```



实例如下：

org.apache.hadoop.examples.WordCount

```java
public static void main(String[] args) throws Exception {
  Configuration conf = new Configuration();
  String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
  if (otherArgs.length < 2) {
    System.err.println("Usage: wordcount <in> [<in>...] <out>");
    System.exit(2);
  }
  Job job = Job.getInstance(conf, "word count");
  job.setJarByClass(WordCount.class);
  // 设置mapper类实现
  job.setMapperClass(TokenizerMapper.class);
  job.setCombinerClass(IntSumReducer.class);
  job.setReducerClass(IntSumReducer.class);
  job.setOutputKeyClass(Text.class);
  job.setOutputValueClass(IntWritable.class);
  for (int i = 0; i < otherArgs.length - 1; ++i) {
    FileInputFormat.addInputPath(job, new Path(otherArgs[i]));
  }
  FileOutputFormat.setOutputPath(job,
    new Path(otherArgs[otherArgs.length - 1]));
  System.exit(job.waitForCompletion(true) ? 0 : 1);
}
```

### MapTask实现



org.apache.hadoop.mapreduce.Mapper#run

```java
public void run(Context context) throws IOException, InterruptedException {
  setup(context);
  try {
    while (context.nextKeyValue()) {
      // 这里才是真正执行map的地方
      map(context.getCurrentKey(), context.getCurrentValue(), context);
    }
  } finally {
    cleanup(context);
  }
}
```

以wordcount为例，mapper类实现如下：

```java
public static class TokenizerMapper 
     extends Mapper<Object, Text, Text, IntWritable>{
  
  private final static IntWritable one = new IntWritable(1);
  private Text word = new Text();
  // 一个mapTask中会有多个key.value，context包含了此任务的input和output等信息，用来读取或者写入
  public void map(Object key, Text value, Context context
                  ) throws IOException, InterruptedException {
    StringTokenizer itr = new StringTokenizer(value.toString());
    while (itr.hasMoreTokens()) {
      word.set(itr.nextToken());
      
      context.write(word, one);
    }
  }
}
```

以下有个context实例，此处省略了上下文代码，然后紧接着剖析output看是如何将输出发送到对应的reducer的：

```java
// get an output object
if (job.getNumReduceTasks() == 0) {
  output = 
    new NewDirectOutputCollector(taskContext, job, umbilical, reporter);
} else {
  output = new NewOutputCollector(taskContext, job, umbilical, reporter);
}

org.apache.hadoop.mapreduce.MapContext<INKEY, INVALUE, OUTKEY, OUTVALUE> 
mapContext = 
  new MapContextImpl<INKEY, INVALUE, OUTKEY, OUTVALUE>(job, getTaskID(), 
      input, output, 
      committer, 
      reporter, split);
```



MapContext中的output实现

```java
NewOutputCollector(org.apache.hadoop.mapreduce.JobContext jobContext,
                     JobConf job,
                     TaskUmbilicalProtocol umbilical,
                     TaskReporter reporter
                     ) throws IOException, ClassNotFoundException {
    collector = createSortingCollector(job, reporter);
  	// 查看reducer个数
    partitions = jobContext.getNumReduceTasks();
  	// 大于1时会有一个均衡策略，可通过mapreduce.job.partitioner.class设置
    if (partitions > 1) {
      partitioner = (org.apache.hadoop.mapreduce.Partitioner<K,V>)
        ReflectionUtils.newInstance(jobContext.getPartitionerClass(), job);
    } else {
      partitioner = new org.apache.hadoop.mapreduce.Partitioner<K,V>() {
        @Override
        public int getPartition(K key, V value, int numPartitions) {
          return partitions - 1;
        }
      };
    }
  }

  @Override
  public void write(K key, V value) throws IOException, InterruptedException {
    // 这里的collector 我们选择org.apache.hadoop.mapred.MapTask.MapOutputBuffer分析
    collector.collect(key, value,
                      partitioner.getPartition(key, value, partitions));
  }

  @Override
  public void close(TaskAttemptContext context
                    ) throws IOException,InterruptedException {
    try {
      collector.flush();
    } catch (ClassNotFoundException cnf) {
      throw new IOException("can't find class ", cnf);
    }
    collector.close();
  }
}
```

最终每个mapTask会写到

```java
// create spill file
final SpillRecord spillRec = new SpillRecord(partitions);
final Path filename =
    mapOutputFile.getSpillFileForWrite(numSpills, size);
out = rfs.create(filename);

public Path getSpillFileForWrite(int spillNumber, long size)
  throws IOException {
  return lDirAlloc.getLocalPathForWrite(
    String.format(SPILL_FILE_PATTERN,
                  conf.get(JobContext.TASK_ATTEMPT_ID), spillNumber), size, conf);
}
```

org.apache.hadoop.mapred.MapTask.MapOutputBuffer#sortAndSpill



### ReduceTask实现



org.apache.hadoop.mapred.ReduceTask#run

```java
public void run(JobConf job, final TaskUmbilicalProtocol umbilical)
  throws IOException, InterruptedException, ClassNotFoundException {
  job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());

  if (isMapOrReduce()) {
    copyPhase = getProgress().addPhase("copy");
    sortPhase  = getProgress().addPhase("sort");
    reducePhase = getProgress().addPhase("reduce");
  }
  // start thread that will handle communication with parent
  TaskReporter reporter = startReporter(umbilical);
  
  boolean useNewApi = job.getUseNewReducer();
  initialize(job, getJobID(), reporter, useNewApi);

  // check if it is a cleanupJobTask
  if (jobCleanup) {
    runJobCleanupTask(umbilical, reporter);
    return;
  }
  if (jobSetup) {
    runJobSetupTask(umbilical, reporter);
    return;
  }
  if (taskCleanup) {
    runTaskCleanupTask(umbilical, reporter);
    return;
  }
  
  // Initialize the codec
  codec = initCodec();
  RawKeyValueIterator rIter = null;
  ShuffleConsumerPlugin shuffleConsumerPlugin = null;
  
  Class combinerClass = conf.getCombinerClass();
  CombineOutputCollector combineCollector = 
    (null != combinerClass) ? 
   new CombineOutputCollector(reduceCombineOutputCounter, reporter, conf) : null;

  Class<? extends ShuffleConsumerPlugin> clazz =
        job.getClass(MRConfig.SHUFFLE_CONSUMER_PLUGIN, Shuffle.class, ShuffleConsumerPlugin.class);
         
  shuffleConsumerPlugin = ReflectionUtils.newInstance(clazz, job);
  LOG.info("Using ShuffleConsumerPlugin: " + shuffleConsumerPlugin);

  ShuffleConsumerPlugin.Context shuffleContext = 
    new ShuffleConsumerPlugin.Context(getTaskID(), job, FileSystem.getLocal(job), umbilical, 
                super.lDirAlloc, reporter, codec, 
                combinerClass, combineCollector, 
                spilledRecordsCounter, reduceCombineInputCounter,
                shuffledMapsCounter,
                reduceShuffleBytes, failedShuffleCounter,
                mergedMapOutputsCounter,
                taskStatus, copyPhase, sortPhase, this,
                mapOutputFile, localMapFiles);
  shuffleConsumerPlugin.init(shuffleContext);

  rIter = shuffleConsumerPlugin.run();

  // free up the data structures
  mapOutputFilesOnDisk.clear();
  
  sortPhase.complete();                         // sort is complete
  setPhase(TaskStatus.Phase.REDUCE); 
  statusUpdate(umbilical);
  Class keyClass = job.getMapOutputKeyClass();
  Class valueClass = job.getMapOutputValueClass();
  RawComparator comparator = job.getOutputValueGroupingComparator();

  if (useNewApi) {
    runNewReducer(job, umbilical, reporter, rIter, comparator, 
                  keyClass, valueClass);
  } else {
    runOldReducer(job, umbilical, reporter, rIter, comparator, 
                  keyClass, valueClass);
  }

  shuffleConsumerPlugin.close();
  done(umbilical, reporter);
}
```



# YARN



![Overview of ResourceManager High Availability](images/rm-ha-overview.png)



## 调度策略

| 策略名            |      |      |
| ----------------- | ---- | ---- |
| CapacityScheduler |      |      |
| FifoScheduler     |      |      |
| FairScheduler     |      |      |

https://blog.csdn.net/xiaomage510/article/details/82500067

# Hive





常见问题参考：

https://zhuanlan.zhihu.com/p/75550159
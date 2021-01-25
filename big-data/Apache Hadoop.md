



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



# YARN





# Hive




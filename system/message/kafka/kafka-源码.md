

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






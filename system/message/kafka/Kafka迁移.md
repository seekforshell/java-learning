## 迁移前提与环境
### 前提
从CDH迁移到EMR；EMR的kafka为全新版本，没有冗余topic数据
### 迁移版本
升级版本如下

| 升级前版本 | 升级后版本 |  |
| --- | --- | --- |
| CDH kafka 1.0.1 | EMR kafka 2.7 |  |

## 迁移工具
kafka 2.x 自带的connect-mirror-maker工具
使用方法如下，使用前需配置mm2.properties参数。
```shell
bash /kafka2/bin/connect-mirror-maker.sh -daemon  mm2.properties 
```
## 迁移步骤
具体迁移步骤如下：
### 配置参数


如果涉及到kafka集群开启了Kerberos，需要在mm2.properties中添加以下安全验证参数，比如emr集群的jaas认证配置如下
```shell
emr.sasl.jaas.config=com.sun.security.auth.module.Krb5LoginModule required useKeyTab=true keyTab="/etc/security/keytabs/kafka2.service.keytab" serviceName="kafka2" principal="kafka2/hostname@HADOOP.COM";
```
mm2.properties 配置参考如下：

| 配置项 | 是否必填 | 说明 |
| --- | --- | --- |
| clusters  | Y | 需要迁移的两个集群 |
| xxx.bootstrap.servers | Y | kafka集群地址列表，需要提前收集并填写 |
| xxx.sasl.jaas.config | N | 根据是否安全环境配置 |
| cdh->emr.enabled | Y | 表示从cdh到emr的数据迁移开关 |
| cdh->emr.topics | Y | 需要迁移的topic，支持正则 |
| groups | N | 消费者组正则，默认为所有 |
| groups.exclude  | N | 需要排除的消费者组 |
| sync.group.offsets.enabled | Y | 是否同步消费者组偏移，开启 |
| sync.topic.configs.enabled | Y | 是否同步topic元数据，开启 |
| sync.topic.acls.enabled | Y | 是否开启acl |



```shell
# 指定需要迁移的集群
clusters = cdh, emr

# connection information for each cluster
# This is a comma separated host:port pairs for each cluster
# for e.g. "A_host1:9092, A_host2:9092, A_host3:9092"
cdh.bootstrap.servers = 1.1.1.71:9092, 1.1.1.119:9092, 1.1.1.120:9092
emr.bootstrap.servers = hostname:9096

# 如果集群是开启安全认证的需要配置以下参数
emr.sasl.jaas.config=com.sun.security.auth.module.Krb5LoginModule required useKeyTab=true keyTab="/etc/security/keytabs/kafka2.service.keytab" serviceName="kafka2" principal="kafka2/hostname@HADOOP.COM";
emr.security.protocol=SASL_PLAINTEXT
emr.sasl.mechanism=GSSAPI
emr.sasl.kerberos.service.name=kafka2

# enable and configure individual replication flows
cdh->emr.enabled = true

# topic过滤，默认为 .* 
cdh->emr.topics = .*
# 消费者组过滤
groups=.*
groups.exclude = test-.*

#B->A.enabled = true
#B->A.topics = .*

# Setting replication factor of newly created remote topics
replication.factor=1

############################# Internal Topic Settings  #############################
# The replication factor for mm2 internal topics "heartbeats", "B.checkpoints.internal" and
# "mm2-offset-syncs.B.internal"
# For anything other than development testing, a value greater than 1 is recommended to ensure availability such as 3.
checkpoints.topic.replication.factor=1
heartbeats.topic.replication.factor=1
offset-syncs.topic.replication.factor=1

# The replication factor for connect internal topics "mm2-configs.B.internal", "mm2-offsets.B.internal" and
# "mm2-status.B.internal"
# For anything other than development testing, a value greater than 1 is recommended to ensure availability such as 3.
offset.storage.replication.factor=1
status.storage.replication.factor=1
config.storage.replication.factor=1

# 是否同步acl
#replication.policy.separator = _
sync.topic.acls.enabled = true
emit.heartbeats.interval.seconds = 5
# 是否同步消费者组偏移
sync.group.offsets.enabled=true
# 是否同步topic元数据
sync.topic.configs.enabled=true
```


### 执行命令


配置好mm2.properties并检查无误后执行如下命令：
```shell
bash /kafka2/bin/connect-mirror-maker.sh -daemon  mm2.properties 
```
## 验证测试
### topic验证
查看topic_fllink_sql是否同步
```shell
[root@hostname kafka2]# bash bin/kafka-topics.sh  --zookeeper 1.1.1.112:2181/k2  --list__consumer_offsets
cdh.checkpoints.internal
cdh.heartbeats
cdh.smoke_flink
cdh.smoke_flink_out
cdh.smoke_flink_out1
cdh.smoke_flink_out2
cdh.smoke_flinksql
cdh.topic_flinksql
flink_case_1
heartbeats
mm2-configs.cdh.internal
mm2-offsets.cdh.internal
mm2-status.cdh.internal
starry_kafka_service_check
```
### 数据验证


- 源端集群生产数据

随机写入一些数据即可
```shell
[deploy@cdh KAFKA]$ bash bin/kafka-console-producer --broker-list 1.1.1.71:9092,1.1.1.119:9092,1.1.1.129:9092 --topic topic_flinksql
...
>abc
>abc
>abc
>
>aba
>ddd
>cda
```

- 源端消费数据



注意这里的groupId为：groupId=console-consumer-74659
```shell
[deploy@cdh KAFKA]$ bin/kafka-console-consumer  --bootstrap-server 1.1.1.71:9092 --topic topic_flinksql --from-beginning
SLF4J: Class path contains multiple SLF4J bindings.
SLF4J: Found binding in [jar:file:/opt/apache-phoenix-4.14.0-cdh5.14.2-bin/phoenix-4.14.0-cdh5.14.2-client.jar!/org/slf4j/impl/StaticLoggerBinder.class]
SLF4J: Found binding in [jar:file:/opt/cloudera/parcels/KAFKA-3.1.0-1.3.1.0.p0.35/lib/kafka/libs/slf4j-log4j12-1.7.5.jar!/org/slf4j/impl/StaticLoggerBinder.class]
SLF4J: See http://www.slf4j.org/codes.html#multiple_bindings for an explanation.
SLF4J: Actual binding is of type [org.slf4j.impl.Log4jLoggerFactory]
21/07/02 14:15:34 INFO consumer.ConsumerConfig: ConsumerConfig values: 
        auto.commit.interval.ms = 5000
        auto.offset.reset = earliest
        bootstrap.servers = [1.1.1.71:9092]
        check.crcs = true
        client.id = 
        connections.max.idle.ms = 540000
        enable.auto.commit = true
        exclude.internal.topics = true
        fetch.max.bytes = 52428800
        fetch.max.wait.ms = 500
        fetch.min.bytes = 1
        group.id = console-consumer-74659
        heartbeat.interval.ms = 3000
        interceptor.classes = null
        internal.leave.group.on.close = true
        isolation.level = read_uncommitted
        key.deserializer = class org.apache.kafka.common.serialization.ByteArrayDeserializer
        max.partition.fetch.bytes = 1048576
        max.poll.interval.ms = 300000
        max.poll.records = 500
        metadata.max.age.ms = 300000
        metric.reporters = []
        metrics.num.samples = 2
        metrics.recording.level = INFO
        metrics.sample.window.ms = 30000
        partition.assignment.strategy = [class org.apache.kafka.clients.consumer.RangeAssignor]
        receive.buffer.bytes = 65536
        reconnect.backoff.max.ms = 1000
        reconnect.backoff.ms = 50
        request.timeout.ms = 305000
        retry.backoff.ms = 100
        sasl.jaas.config = null
        sasl.kerberos.kinit.cmd = /usr/bin/kinit
        sasl.kerberos.min.time.before.relogin = 60000
        sasl.kerberos.service.name = null
        sasl.kerberos.ticket.renew.jitter = 0.05
        sasl.kerberos.ticket.renew.window.factor = 0.8
        sasl.mechanism = GSSAPI
        security.protocol = PLAINTEXT
        send.buffer.bytes = 131072
        session.timeout.ms = 10000
        ssl.cipher.suites = null
        ssl.enabled.protocols = [TLSv1.2, TLSv1.1, TLSv1]
        ssl.endpoint.identification.algorithm = null
        ssl.key.password = null
        ssl.keymanager.algorithm = SunX509
        ssl.keystore.location = null
        ssl.keystore.password = null
        ssl.keystore.type = JKS
        ssl.protocol = TLS
        ssl.provider = null
        ssl.secure.random.implementation = null
        ssl.trustmanager.algorithm = PKIX
        ssl.truststore.location = null
        ssl.truststore.password = null
        ssl.truststore.type = JKS
        value.deserializer = class org.apache.kafka.common.serialization.ByteArrayDeserializer

21/07/02 14:15:34 INFO utils.AppInfoParser: Kafka version : 1.0.1-kafka-3.1.0-SNAPSHOT
21/07/02 14:15:34 INFO utils.AppInfoParser: Kafka commitId : unknown
21/07/02 14:15:34 INFO internals.AbstractCoordinator: [Consumer clientId=consumer-1, groupId=console-consumer-74659] Discovered group coordinator cdh.dtwave.sit.local:9092 (id: 2147483512 rack: null)
21/07/02 14:15:34 INFO internals.ConsumerCoordinator: [Consumer clientId=consumer-1, groupId=console-consumer-74659] Revoking previously assigned partitions []
21/07/02 14:15:34 INFO internals.AbstractCoordinator: [Consumer clientId=consumer-1, groupId=console-consumer-74659] (Re-)joining group

21/07/02 14:15:47 INFO internals.AbstractCoordinator: [Consumer clientId=consumer-1, groupId=console-consumer-74659] Successfully joined group with generation 1
21/07/02 14:15:47 INFO internals.ConsumerCoordinator: [Consumer clientId=consumer-1, groupId=console-consumer-74659] Setting newly assigned partitions [topic_flinksql-0]
abc
abc
abc

aba
ddd
cda
```
目的端消费数据
​

```shell
[root@hostname kafka2]# bash bin/kafka-console-consumer.sh --bootstrap-server hostname:9096 --from-beginning --topic cdh.topic_flinksql --consumer.config consumer-test-offset.properties 
abc
abc
abc

aba
ddd
cda

```
### 偏移值验证
源端偏移值查看，观察consumer-74659消费者组的偏移为7
```shell
[deploy@cdh KAFKA]$ bin/kafka-console-consumer --topic __consumer_offsets --bootstrap-server 1.1.1.71:9092,1.1.1.119:9092,1.1.1.120:9092 --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter"  --from-beginning | grep topic_flinksql
.....
[console-consumer-74659,topic_flinksql,0]::[OffsetMetadata[7,NO_METADATA],CommitTime 1625206552676,ExpirationTime 1625292952676]
[console-consumer-74659,topic_flinksql,0]::[OffsetMetadata[7,NO_METADATA],CommitTime 1625206557674,ExpirationTime 1625292957674]
```


目的端偏移验证
在目的kafka集群同样去观察改消费者的偏移是否一致，如果一致说明消费者组偏移已经同步
```shell
[2021-07-02 14:18:27,259] WARN TGT renewal thread has been interrupted and will exit. (org.apache.zookeeper.Login)
[root@hostname kafka2]# bin/kafka-console-consumer.sh --topic __consumer_offsets --bootstrap-server hostname:9096 --formatter "kafka.coordinator.group.GroupMetadataManager\$OffsetsMessageFormatter" --consumer.config consumer-test-offset.properties --from-beginning | grep topic_flinksql[console-consumer-98660,cdh.topic_flinksql,0]::OffsetAndMetadata(offset=0, leaderEpoch=Optional.empty, metadata=, commitTimestamp=1625206774316, expireTimestamp=None)
[console-consumer-74659,cdh.topic_flinksql,0]::OffsetAndMetadata(offset=7, leaderEpoch=Optional.empty, metadata=, commitTimestamp=1625206774320, expireTimestamp=None)
^C[2021-07-02 14:19:47,886] WARN [Principal=kafka2/hostname@HADOOP.COM]: TGT renewal thread has been interrupted and will exit. (org.apache.kafka.common.security.kerberos.KerberosLogin)
Processed a total of 13656 messages

```
## 参考
[http://kafka.apache.org/27/documentation.html#georeplication](http://kafka.apache.org/27/documentation.html#georeplication)
[https://cwiki.apache.org/confluence/display/KAFKA/KIP-382%3A+MirrorMaker+2.0#KIP382:MirrorMaker2.0-Config,ACLSync](https://cwiki.apache.org/confluence/display/KAFKA/KIP-382%3A+MirrorMaker+2.0#KIP382:MirrorMaker2.0-Config,ACLSync)

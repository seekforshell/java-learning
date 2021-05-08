# 介绍

kafka是一个流处理（实时和离线）平台。所以从kafka的使命来看其核心功能都是围绕打造流处理平台来服务，包括它的发布订阅模式、存储、连接器等。

# 基本概念

## 主题-Topic



## 分区

一个Topic可以有多个分区，发送到topic上的消息根据某种策略发到对应的分区，每个分区都有一个leader。

## 副本

一个分区可以有多个副本，这样设计主要是做到Ha，防止单点故障。

## 消费者组

一个topic可以有多个分区，一个消费者组是一个逻辑订阅者，每一个消费者都对应于topic中的一个或者多个分区。

比如一个topic有6个分区（P0,P1,P2,P3,P4,P5,P6），消费者组有3个（C1,C2,C3），那么可能的对应关系为：

{C1->[P0,P1]},{C2->[P2,P3]},{C3->[P4,P5]}。消费者组的设计是为了客户端负载均衡使用的。

## 核心概念之间的关系



![img](images\consumer-groups.png)



借上面的图来解释下kafka的设计思路：



## Topic-patition-replica

​	

一个topic可以有多个Partition（提高并发性能，如何选择哪个Partition?）；一个partition可以有多个replica，其中一个主replica，其余为follower。



副本之间是如何做到一致性的？考虑了CAP中的CP?



## 高水位



## 事务

<img src="images/kafka_txn.png" alt="image-20201118101425188" style="zoom: 33%;" />

https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging#KIP98ExactlyOnceDeliveryandTransactionalMessaging-DataFlow

#  生产者

### 常见配置



| 配置                                                         | 参数及意义                                                   | 作用                                                 |
| ------------------------------------------------------------ | ------------------------------------------------------------ | ---------------------------------------------------- |
| acks                                                         | 0:不要求leader做任何动作，生产者发送消息即认为成功，消息是否正常传递到leader不做任何保证<br>1:leader需要写消息到本地日志但是不需要等到所有follower的确定即可任务消息发送成功，如果此时leader挂掉了，那么消息也会丢掉<br>all(-1)：需要等待所有follower写完本地日志回复给leader，完成isr才回复给生产者；写本地日志是会更新 | 生产者发送消息到leader，leader需要收到确认消息的个数 |
| batch.size                                                   | 批量发送到同一个分区的消息大小个数，可设置合适大小提高吞吐量 |                                                      |
| [client.id](http://kafka.apache.org/25/documentation.html#client.id) | 客户端id，用于区分生产者                                     |                                                      |
| partitioner.class                                            | 分区选择类实现，发送的消息如何选择送往哪个分区               |                                                      |
| enable.idempotence                                           | 是否支持精确一致性的消息发送。如果设置为false则可能会导致重复消息，如果设置成true，那么acks必须设置成all，同时retires大于0，max.in.flight.requests.per.connection小于等于5 |                                                      |
| transactional.id                                             | 事务id，对于使用了相同事务id的多个producer需要等待前一个事务完成才能开启新的事务，保持事务的隔离性。同时需要设置幂等性参数为true。 |                                                      |





# 消费者



## 常见配置

| 配置                     | 参数及意义                                                   | 作用                                             |
| ------------------------ | ------------------------------------------------------------ | ------------------------------------------------ |
| group.id                 | 消费者所属的消费者组                                         |                                                  |
| allow.auto.create.topics | 是否允许自动创建topic，只有在broker使能才起作用              |                                                  |
| allow.auto.create.topics |                                                              |                                                  |
| isolation.level          | 字符串类型，“read_uncommitted”和“read_committed”，表示消费者所消费到的位置，如果设置为“read_committed"，那么消费这就会忽略事务未提交的消息，既只能消费到LSO(LastStableOffset)的位置，默认情况下，”read_uncommitted",既可以消费到HW（High Watermak）的位置。 | 默认值：read_uncommitted，其他值：read_committed |
| enable.auto.commit       | 是否自动提交offset                                           |                                                  |
| client.id                | 唯一标识消费者                                               |                                                  |
| group.id                 | 组，消费者是否分组；分组则会在客户端启动组协调器：负载用     |                                                  |



# FAQ

### GroupCoordinator机制



### Kafka为什么那么快？

- Cache Filesystem Cache PageCache缓存
- 顺序写 由于现代的操作系统提供了预读和写技术，磁盘的顺序写大多数情况下比随机写内存还要快。
- Zero-copy 零拷技术减少拷贝次数
- Batching of Messages 批量处理。合并小的请求，然后以流的方式进行交互，直顶网络上限。
- Pull 拉模式 使用拉模式进行消息的获取消费，与消费端处理能力相符。

### **Kafka中的事务是怎么实现的？**

https://honeypps.com/mq/kafka-basic-knowledge-of-transaction/



#### kafka事务的基本流程？



kafka如何保证事务的一致性？



### **Kafka中的消息是否会丢失和重复消费？**

丢失消息可以从生产者、消费者



### **kafka producer如何优化打入速度**



### Kafka中有那些地方需要选举？这些地方的选举策略又有哪些？



### **kafka如何实现延迟队列？**





### **为什么Kafka不支持读写分离？**




# 应用场景

## 消息系统



| 消息      | Kafka                                                 | RabbitMq | ActiveMQ |
| --------- | ----------------------------------------------------- | -------- | -------- |
| 可靠性-Ha |                                                       |          |          |
| 有序性    | 天然有序，同一个分区以队列方式                        |          |          |
| 消息延时  |                                                       |          |          |
| 吞吐量    | 顺序写，支持磁盘持久化，并且pageCache和zeroCopy等技术 |          |          |
|           |                                                       |          |          |

参考文档：

https://mp.weixin.qq.com/s?__biz=MzI3NDAwNDUwNg%3D%3D&mid=2648307598&idx=1&sn=eeaa9d795ef6ba13368e7a76ca14bae7&scene=45#wechat_redirect

## 核心功能



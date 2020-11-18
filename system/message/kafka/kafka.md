# 基本概念





## Topic-patition-replica

​	

一个topic可以有多个Partition（提高并发性能，如何选择哪个Partition?）；一个partition可以有多个replica，其中一个主replica，其余为follower。



副本之间是如何做到一致性的？考虑了CAP中的CP?

# FAQ

### GroupCoordinator机制



### Kafka为什么那么快？

- Cache Filesystem Cache PageCache缓存
- 顺序写 由于现代的操作系统提供了预读和写技术，磁盘的顺序写大多数情况下比随机写内存还要快。
- Zero-copy 零拷技术减少拷贝次数
- Batching of Messages 批量量处理。合并小的请求，然后以流的方式进行交互，直顶网络上限。
- Pull 拉模式 使用拉模式进行消息的获取消费，与消费端处理能力相符。

### **Kafka中的事务是怎么实现的？**

https://honeypps.com/mq/kafka-basic-knowledge-of-transaction/

### **Kafka中的消息是否会丢失和重复消费？**

### **kafka producer如何优化打入速度**



### Kafka中有那些地方需要选举？这些地方的选举策略又有哪些？



### **kafka如何实现延迟队列？**





### **为什么Kafka不支持读写分离？**




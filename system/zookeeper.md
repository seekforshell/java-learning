[toc]

# 概述

什么是Quorum ?

quorum /'kwɔ:rəm/ n.

a gathering of the minimal number of members of an organization to conduct business

 

其实在刚开始看zookeeper源代码的时候我就在想这个单词的含义；然后查了下词典翻译成中文是法定人数就一直很纳闷，直到看到了它的英文含义；

 A replicated group of servers in the same application is called a quorum, and in replicated mode, all servers in the quorum have copies of the same configuration file. The file is similar to the one used in standalone mode, but with a few differences. Here is an example:

 

tickTime=2000

dataDir=/var/zookeeper

clientPort=2181

initLimit=5

syncLimit=2

**server.1=zoo1:2888:3888**

**server.2=zoo2:2888:3888**

**server.3=zoo3:2888:3888**



# 框架



从上图可以看出，select模型是整个zk的网络框架的关键，首先需要注册selector和操作类型到channel，然后select进行poll操作，注意传参的pollWrapper是从系统申请的原生内存。pollArrayAddress携带了文件描述符合底层的监听事件类型（比如POLL.IN），返回的则是有哪些socket描述符是可读的，哪些是可写的，哪些是异常的。

然后进行进行0步骤，根据返回的socket描述符去计算存储在pollArrayAddress中的监听事件信息，通过channel(步骤1)进行事件翻译到selectionkey中，由于之前已经将selectionkey注册到了selector中，所以只需要遍历selector中已经ready的key就可以找到对应的channel，然后channel就可以进行读写已经ready的数据。



![image-20200916230104441](images\image-20200916230104441.png)

从下图的整个架构可以看出，  先进性数据恢复，然后使用select建立监听，如果有数据则将报文提交给选举之后提交的处理器（处理器专门用于处理各种类型报文，然后进行相应的回调处理）



![image-20200916230140116](images\image-20200916230140116.png)

## Paxos算法




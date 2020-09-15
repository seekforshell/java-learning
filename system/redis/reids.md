

# Redis修炼手册

## 集群部署

### 如何部署集群模式？

#### 环境准备

##### 基础环境

```
ifconfig ens33 192.168.126.2/24
ifconfig ens33 hw ether 00:50:56:29:F2:2E
```



##### redis环境

本次部署采用vmware workstation虚拟出六个redis节点，网络采用nat方式，每节点资源为：

1 core + 1GB

```
192.168.126.131 ~ 192.168.126.136
```

部署命令参考

临时网络配置：

```shell
ifconfig ens33 192.168.126.132/24
ifconfig ens33 hw ether 00:50:56:32:2a:ab

ifconfig ens33 192.168.126.133/24
ifconfig ens33 hw ether 00:50:56:3f:e5:0d 

ifconfig ens33 192.168.126.134/24
ifconfig ens33 hw ether 00:50:56:2F:7D:AF

ifconfig ens33 192.168.126.135/24
ifconfig ens33 hw ether 00:50:56:3C:47:99

ifconfig ens33 192.168.126.136/24
ifconfig ens33 hw ether 00:50:56:36:E9:1B
```

永久网络配置:

其中BOOTPROTO、GATEWAY、DNS1、IPADDR0、HWADDR、NETMASK、ONBOOT为必填项

```

[root@localhost ~]# cat /etc/sysconfig/network-scripts/ifcfg-ens33
TYPE=Ethernet
BOOTPROTO=static
DEFROUTE=yes
PEERDNS=yes
PEERROUTES=yes
IPV4_FAILURE_FATAL=no
IPV6INIT=yes
IPV6_AUTOCONF=yes
IPV6_DEFROUTE=yes
IPV6_PEERDNS=yes
IPV6_PEERROUTES=yes
IPV6_FAILURE_FATAL=no
IPV6_ADDR_GEN_MODE=stable-privacy
NAME=ens33
UUID=90200d21-bfe4-4296-ac7e-0264c99b9177
DEVICE=ens33
ONBOOT=yes
GATEWAY=192.168.126.1
DNS1=192.168.126.1
IPADDR0=192.168.126.2     
HWADDR=00:50:56:29:F2:2E  
NETMASK=255.255.255.0  
[root@localhost ~]#

```

注意：

永久化路由后会生成一条默认路由，需要将其删除

```

[root@node4 ~]# ip route
default via 192.168.126.2 dev ens33  proto static  metric 100
192.168.126.0/24 dev ens33  proto kernel  scope link  src 192.168.126.134  metric 100

```

route del default gw 192.168.126.1 dev ens33

#### 第一步：单机部署

初始化，主要是用于redis的配置里的一些参数

```
mkdir -p /opt/redis
mkdir -p /opt/redis/log
mkdir -p /opt/redis/rdb
```

下载安装包并编译：

```shell
# 先在131机器上下载并编译
wget http://download.redis.io/releases/redis-5.0.0tar.gz
# 解压后编译、安装到指定目录
cd redis-5.0.0 && make && make install PREFIX=/usr/local/redis
cp redis.conf /usr/local/redis/bin

```

设置开机启动

**vi /etc/systemd/system/redis.service**

```shell
[Unit]Description=redis-serverAfter=network.target
[Service]
Type=forking
ExecStart=/usr/local/redis/bin/redis-server /usr/local/redis/bin/redis.conf
PrivateTmp=true
[Install]
WantedBy=multi-user.target
```

启动命令

```shell
systemctl daemon-reload

systemctl start redis.service

systemctl enable redis.service
```

设置主机映射

vi /etc/hosts

```

192.168.126.131 node1
192.168.126.132 node2
192.168.126.133 node3
192.168.126.134 node4
192.168.126.135 node5
192.168.126.136 node6
```



4.创建客户端软连接

```shell
 ln -s /usr/local/redis/bin/redis-cli /usr/bin/redis-cli
```

5.关闭防火墙

```
chkconfig firewalld off
service firewalld stop
```



#### 第二步：创建集群

注意这里的端口号为6379 ？

```
redis-cli --cluster create 192.168.126.131:6379 192.168.126.132:6379 192.168.126.133:6379 192.168.126.134:6379 192.168.126.135:6379 192.168.126.136:6379 --cluster-replicas 1
```

日志如下

```
[root@node1 redis]# redis-cli --cluster create 192.168.126.131:6379 192.168.126.132:6379 192.168.126.133:6379 192.168.126.134:6379 192.168.126.135:6379 192.168.126.136:6379 --cluster-replicas 1
>>> Performing hash slots allocation on 6 nodes...
Master[0] -> Slots 0 - 5460
Master[1] -> Slots 5461 - 10922
Master[2] -> Slots 10923 - 16383
Adding replica 192.168.126.134:6379 to 192.168.126.131:6379
Adding replica 192.168.126.135:6379 to 192.168.126.132:6379
Adding replica 192.168.126.136:6379 to 192.168.126.133:6379
M: 0b7f0f75f182654ce5c54352fd685c618fa2fef0 192.168.126.131:6379
   slots:[0-5460] (5461 slots) master
M: 04284ad20b03846a2f07851473fb4ac93e8f0a0b 192.168.126.132:6379
   slots:[5461-10922] (5462 slots) master
M: 59de5cbf5be6cd98c2924c69b4e3d766d65c3c73 192.168.126.133:6379
   slots:[10923-16383] (5461 slots) master
S: 50581e806350880b89573a341659462cad6690f9 192.168.126.134:6379
   replicates 0b7f0f75f182654ce5c54352fd685c618fa2fef0
S: fd1e28f8dc7cf2807cec0b3f9457d60a1e364070 192.168.126.135:6379
   replicates 04284ad20b03846a2f07851473fb4ac93e8f0a0b
S: 491ac7f593e6f63a8e276889be132b705a259f72 192.168.126.136:6379
   replicates 59de5cbf5be6cd98c2924c69b4e3d766d65c3c73
Can I set the above configuration? (type 'yes' to accept): yes
>>> Nodes configuration updated
>>> Assign a different config epoch to each node
>>> Sending CLUSTER MEET messages to join the cluster
Waiting for the cluster to join
.....
>>> Performing Cluster Check (using node 192.168.126.131:6379)
M: 0b7f0f75f182654ce5c54352fd685c618fa2fef0 192.168.126.131:6379
   slots:[0-5460] (5461 slots) master
   1 additional replica(s)
S: fd1e28f8dc7cf2807cec0b3f9457d60a1e364070 192.168.126.135:6379
   slots: (0 slots) slave
   replicates 04284ad20b03846a2f07851473fb4ac93e8f0a0b
M: 59de5cbf5be6cd98c2924c69b4e3d766d65c3c73 192.168.126.133:6379
   slots:[10923-16383] (5461 slots) master
   1 additional replica(s)
M: 04284ad20b03846a2f07851473fb4ac93e8f0a0b 192.168.126.132:6379
   slots:[5461-10922] (5462 slots) master
   1 additional replica(s)
S: 50581e806350880b89573a341659462cad6690f9 192.168.126.134:6379
   slots: (0 slots) slave
   replicates 0b7f0f75f182654ce5c54352fd685c618fa2fef0
S: 491ac7f593e6f63a8e276889be132b705a259f72 192.168.126.136:6379
   slots: (0 slots) slave
   replicates 59de5cbf5be6cd98c2924c69b4e3d766d65c3c73
[OK] All nodes agree about slots configuration.
>>> Check for open slots...
>>> Check slots coverage...
[OK] All 16384 slots covered.
[root@node1 redis]#

```

#### 需要注意的问题

bind地址不要填127.0.0.1 需指明主机名称



## 常用命令

### config get

config set/get <subcommand>

子命令部分对应于redis.conf中的配置项

```
node1:6379> CONFIG
(error) ERR Unknown subcommand or wrong number of arguments for ''. Try CONFIG HELP.
node1:6379> CONFIG GET dbfilename
1) "dbfilename"
2) "dump.rdb"
node1:6379> CONFIG GET logfile
1) "logfile"
2) "/opt/redis/log/redis.log"
node1:6379> CONFIG GET databases
1) "databases"
2) "16"
node1:6379> CONFIG GET cluster-node-timeout
1) "cluster-node-timeout"
2) "15000"
node1:6379>

```



### 解析rdb文件



```
[root@node1 redis]# od -c /dump.rdb
0000000   R   E   D   I   S   0   0   0   9 372  \t   r   e   d   i   s
0000020   -   v   e   r 005   5   .   0   .   0 372  \n   r   e   d   i
0000040   s   -   b   i   t   s 300   @ 372 005   c   t   i   m   e 302
0000060 320 332  \n   _ 372  \b   u   s   e   d   -   m   e   m 302 340
0000100  \r   (  \0 372 016   r   e   p   l   -   s   t   r   e   a   m
0000120   -   d   b 300  \0 372  \a   r   e   p   l   -   i   d   (   5
0000140   7   a   6   3   e   f   3   2   8   0   2   0   f   c   3   4
0000160   8   6   1   2   3   5   6   5   1   6   0   1   a   9   8   d
0000200   8   e   3   7   e   6   6 372  \v   r   e   p   l   -   o   f
0000220   f   s   e   t 300  \0 372  \f   a   o   f   -   p   r   e   a
0000240   m   b   l   e 300  \0 377 365 332 035   L   P 277 246 332
0000257
[root@node1 redis]#

```



### 参考文档



https://www.cnblogs.com/heqiuyong/p/10463334.html

https://www.jianshu.com/p/be14306f5fd8



## 使用场景



## Redis集群原理

### 客户端命令执行流程

![image-20200720204919008](images\image-20200720204919008.png)

### 集群初始化过程

redis-cli --cluster create 192.168.126.131:6379 192.168.126.132:6379 192.168.126.133:6379 192.168.126.134:6379 192.168.126.135:6379 192.168.126.136:6379 --cluster-replicas 1



1.计算主节点个数

2.向所有节点发送meet消息

```c
clusterManagerLogInfo(">>> Sending CLUSTER MEET messages to join "
                      "the cluster\n");
clusterManagerNode *first = NULL;
listRewind(cluster_manager.nodes, &li);
while ((ln = listNext(&li)) != NULL) {
    clusterManagerNode *node = ln->value;
    if (first == NULL) {
        first = node;
        continue;
    }
    redisReply *reply = NULL;
    reply = CLUSTER_MANAGER_COMMAND(node, "cluster meet %s %d",
                                    first->ip, first->port);
    int is_err = 0;
    if (reply != NULL) {
        if ((is_err = reply->type == REDIS_REPLY_ERROR))
            CLUSTER_MANAGER_PRINT_REPLY_ERROR(node, reply->str);
        freeReplyObject(reply);
    } else {
        is_err = 1;
        fprintf(stderr, "Failed to send CLUSTER MEET command.\n");
    }
    if (is_err) {
        success = 0;
        goto cleanup;
    }
}
/* Give one second for the join to start, in order to avoid that
         * waiting for cluster join will find all the nodes agree about
         * the config as they are still empty with unassigned slots. */
sleep(1);
clusterManagerWaitForClusterJoin();
```

3.保存配置

保存配置到node.conf文件中，也就是cluster nodes的返回内容

```c
listRewind(cluster_manager.nodes, &li);
while ((ln = listNext(&li)) != NULL) {
    clusterManagerNode *node = ln->value;
    if (!node->dirty) continue;
    char *err = NULL;
    int flushed = clusterManagerFlushNodeConfig(node, &err);
    if (!flushed && !node->replicate) {
        if (err != NULL) {
            CLUSTER_MANAGER_PRINT_REPLY_ERROR(node, err);
            zfree(err);
        }
        success = 0;
        goto cleanup;
    }
}
```

至于后面应该是第一个节点将所有的节点信息同步到所有集群节点



### 集群通信原理

#### 集群报文格式

集群间的消息类型有以下几种，统一采用五种报文格式进行传输。

```c
/* Message types.
 *
 * Note that the PING, PONG and MEET messages are actually the same exact
 * kind of packet. PONG is the reply to ping, in the exact format as a PING,
 * while MEET is a special PING that forces the receiver to add the sender
 * as a node (if it is not already in the list). */
#define CLUSTERMSG_TYPE_PING 0          /* Ping */
#define CLUSTERMSG_TYPE_PONG 1          /* Pong (reply to Ping) */
#define CLUSTERMSG_TYPE_MEET 2          /* Meet "let's join" message */
#define CLUSTERMSG_TYPE_FAIL 3          /* Mark node xxx as failing */
#define CLUSTERMSG_TYPE_PUBLISH 4       /* Pub/Sub Publish propagation */
#define CLUSTERMSG_TYPE_FAILOVER_AUTH_REQUEST 5 /* May I failover? */
#define CLUSTERMSG_TYPE_FAILOVER_AUTH_ACK 6     /* Yes, you have my vote */
#define CLUSTERMSG_TYPE_UPDATE 7        /* Another node slots configuration */
#define CLUSTERMSG_TYPE_MFSTART 8       /* Pause clients for manual failover */
#define CLUSTERMSG_TYPE_MODULE 9        /* Module cluster API message. */
#define CLUSTERMSG_TYPE_COUNT 10        /* Total number of message types. */
```

集群键通信有以下几种报文格式

| 报文格式            | 作用 | 描述       |
| ------------------- | ---- | ---------- |
| PING, MEET and PONG |      | Gossip协议 |
| FAIL                |      |            |
| PUBLISH             |      |            |
| UPDATE              |      |            |
| MODULE              |      |            |

报文格式：

从以下报文格式可以看出一些元数据信息，包括epoch等，节点状态，日志偏移，clusterMsgData是个联合体，根据不同的报文类型进行选择

```c
typedef struct {
    char sig[4];        /* Signature "RCmb" (Redis Cluster message bus). */
    uint32_t totlen;    /* Total length of this message */
    uint16_t ver;       /* Protocol version, currently set to 1. */
    uint16_t port;      /* TCP base port number. */
    uint16_t type;      /* Message type */
    uint16_t count;     /* Only used for some kind of messages. */
    uint64_t currentEpoch;  /* The epoch accordingly to the sending node. */
    uint64_t configEpoch;   /* The config epoch if it's a master, or the last
                               epoch advertised by its master if it is a
                               slave. */
    uint64_t offset;    /* Master replication offset if node is a master or
                           processed replication offset if node is a slave. */
    char sender[CLUSTER_NAMELEN]; /* Name of the sender node */
    unsigned char myslots[CLUSTER_SLOTS/8];
    char slaveof[CLUSTER_NAMELEN];
    char myip[NET_IP_STR_LEN];    /* Sender IP, if not all zeroed. */
    char notused1[34];  /* 34 bytes reserved for future usage. */
    uint16_t cport;      /* Sender TCP cluster bus port */
    uint16_t flags;      /* Sender node flags */
    unsigned char state; /* Cluster state from the POV of the sender */
    unsigned char mflags[3]; /* Message flags: CLUSTERMSG_FLAG[012]_... */
    union clusterMsgData data;
} clusterMsg;
```

子类型格式如下：

```c
union clusterMsgData {
    /* PING, MEET and PONG */
    struct {
        /* Array of N clusterMsgDataGossip structures */
        clusterMsgDataGossip gossip[1];
    } ping;

    /* FAIL */
    struct {
        clusterMsgDataFail about;
    } fail;

    /* PUBLISH */
    struct {
        clusterMsgDataPublish msg;
    } publish;

    /* UPDATE */
    struct {
        clusterMsgDataUpdate nodecfg;
    } update;

    /* MODULE */
    struct {
        clusterMsgModule msg;
    } module;
};

typedef struct {
    char nodename[CLUSTER_NAMELEN];
    uint32_t ping_sent;
    uint32_t pong_received;
    char ip[NET_IP_STR_LEN];  /* IP address last time it was seen */
    uint16_t port;              /* base port last time it was seen */
    uint16_t cport;             /* cluster port last time it was seen */
    uint16_t flags;             /* node->flags copy */
    uint32_t notused1;
} clusterMsgDataGossip;
typedef struct {
    char nodename[CLUSTER_NAMELEN];
} clusterMsgDataFail;

typedef struct {
    uint32_t channel_len;
    uint32_t message_len;
    unsigned char bulk_data[8]; /* 8 bytes just as placeholder. */
} clusterMsgDataPublish;

typedef struct {
    uint64_t configEpoch; /* Config epoch of the specified instance. */
    char nodename[CLUSTER_NAMELEN]; /* Name of the slots owner. */
    unsigned char slots[CLUSTER_SLOTS/8]; /* Slots bitmap. */
} clusterMsgDataUpdate;

typedef struct {
    uint64_t module_id;     /* ID of the sender module. */
    uint32_t len;           /* ID of the sender module. */
    uint8_t type;           /* Type from 0 to 255. */
    unsigned char bulk_data[3]; /* 3 bytes just as placeholder. */
} clusterMsgModule;

union clusterMsgData {
    /* PING, MEET and PONG */
    struct {
        /* Array of N clusterMsgDataGossip structures */
        clusterMsgDataGossip gossip[1];
    } ping;

    /* FAIL */
    struct {
        clusterMsgDataFail about;
    } fail;

    /* PUBLISH */
    struct {
        clusterMsgDataPublish msg;
    } publish;

    /* UPDATE */
    struct {
        clusterMsgDataUpdate nodecfg;
    } update;

    /* MODULE */
    struct {
        clusterMsgModule msg;
    } module;
};

typedef struct {
    char nodename[CLUSTER_NAMELEN];
    uint32_t ping_sent;
    uint32_t pong_received;
    char ip[NET_IP_STR_LEN];  /* IP address last time it was seen */
    uint16_t port;              /* base port last time it was seen */
    uint16_t cport;             /* cluster port last time it was seen */
    uint16_t flags;             /* node->flags copy */
    uint32_t notused1;
} clusterMsgDataGossip;
typedef struct {
    char nodename[CLUSTER_NAMELEN];
} clusterMsgDataFail;

typedef struct {
    uint32_t channel_len;
    uint32_t message_len;
    unsigned char bulk_data[8]; /* 8 bytes just as placeholder. */
} clusterMsgDataPublish;

typedef struct {
    uint64_t configEpoch; /* Config epoch of the specified instance. */
    char nodename[CLUSTER_NAMELEN]; /* Name of the slots owner. */
    unsigned char slots[CLUSTER_SLOTS/8]; /* Slots bitmap. */
} clusterMsgDataUpdate;

typedef struct {
    uint64_t module_id;     /* ID of the sender module. */
    uint32_t len;           /* ID of the sender module. */
    uint8_t type;           /* Type from 0 to 255. */
    unsigned char bulk_data[3]; /* 3 bytes just as placeholder. */
} clusterMsgModule;
```



## 常用数据结构
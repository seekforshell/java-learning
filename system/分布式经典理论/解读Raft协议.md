# Raft修炼手册

本文的目的在于解读Raft协议算法。

## 副本状态机

![image-20200809115026424](images\image-20200809115026424.png)

概述：

副本状态机用来解决分布式系统中各种容错问题。副本状态机通常通过日志的形式来实现，日志中记录客户端请求的有序命令集合。每个集群节点通过**一致性算法**会得到相同的日志副本，每份日志包含相同顺序且数量相同的命令集合，状态机读取日志，按顺序执行命令便得到了相同的结果来实现**数据的一致性**。

比如一个简单的redis写请求的执行步骤大致如下：

1. 首先客户端发送请求(比如写请求）到集群（如果不是leader是否会路由到leader?），集群节点通过key计算hash值，通过哈希槽和节点的映射关系将命令转发到对应的leader节点
2. leader写本地日志，然后广播到所有follower节点，如果超过半数则提交更新commitIndex
3. 节点检测到commitIndex>lastApplied，会通过state machine读取日志执行命令更新数据到内存中，更新lastApplied
4. 上面步骤完成后返回给客户端

## 原理详述

### 节点

节点分为三种状态：leader,follower,candidate

![image-20200809173837870](images\image-20200809173837870.png)

**leader:**

The leader handles all client requests (if a client contacts a follower, the follower redirects it to the leader).

**follower:**

they issue no requests on their own but simply respond to requests from leaders and candidates.

集群启动阶段都是follower状态。

**candidate:**

candidate is used to elect a new leader

### 选举流程



```
Arguments:
term candidate’s term
candidateId candidate requesting vote
lastLogIndex index of candidate’s last log entry (§5.4)
lastLogTerm term of candidate’s last log entry (§5.4)
Results:
term currentTerm, for candidate to update itself
voteGranted true means candidate received vote
Receiver implementation:
1. Reply false if term < currentTerm (§5.1)
2. If votedFor is null or candidateId, and candidate’s log is at
least as up-to-date as receiver’s log, grant vote (§5.2, §5.4)
```

如下图所示，在进行选举的过程当中，可能会出现分裂的情况，这个时候term递增重新进行选举。

![image-20200809174225131](images\image-20200809174225131.png)



### 日志复制流程

```
Arguments:
term leader’s term
leaderId so follower can redirect clients
prevLogIndex index of log entry immediately preceding
new ones
prevLogTerm term of prevLogIndex entry
entries[] log entries to store (empty for heartbeat;
may send more than one for efficiency)
leaderCommit leader’s commitIndex
Results:
term currentTerm, for leader to update itself
success true if follower contained entry matching
prevLogIndex and prevLogTerm
Receiver implementation:
1. Reply false if term < currentTerm (§5.1)
2. Reply false if log doesn’t contain an entry at prevLogIndex
whose term matches prevLogTerm (§5.3)
3. If an existing entry conflicts with a new one (same index
but different terms), delete the existing entry and all that
follow it (§5.3)
4. Append any new entries not already in the log
5. If leaderCommit > commitIndex, set commitIndex =
min(leaderCommit, index of last new entry)
```



## 数据结构

所有节点拥有的数据结构：

```c
struct state {
	long currentTerm, /* latest term server has seen (initialized to 0 on first boot, increases monotonically) */
	long votedFor, /* candidateId that received vote in current term (or null if none) */
	char *log[], /* log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1) */
	long commitIndex, /* index of highest log entry known to be committed (initialized to 0, increases
monotonically) */
	long lastApplied, /* index of highest log entry applied to state machine (initialized to 0, increases monotonically) */
}
```

leader节点的数据结构

其中nextIndex和matchIndex是leader节点才有的数据结构：

```c
struct state {
    long currentTerm,
    long votedFor,
    char *log[],
    long commitIndex,
    long lastApplied,
    long nextIndex[], /* for each server, index of the next log entry to send to that server (initialized to leader last log index + 1) */
	long matchIndex[] /* for each server, index of highest log entry known to be replicated on server (initialized to 0, increases monotonically) */
    
}
```



| 列          | 描述                                                         |
| ----------- | ------------------------------------------------------------ |
| lastApplied | 应用到状态机的日志索引                                       |
| commitIndex | 已经提交，但是尚未提交到状态机；如果此参数大于lastApplied则通过状态机执行命令 |
| nextIndex   | 初始值为last log index + 1，如果last log index > nextIndex，则leader向follower发送append日志。<br>成功则更新leader数据<br>失败则减小nextIndex的值进行重试 |


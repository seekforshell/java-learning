# Java同步机制

## 概述

可以分为内置锁、显示锁（lock）、volatile及原子变量
同步的重点在于保证共享对象的内存可见性、对共享操作的原子性及有序性

## volatile

用来确保变量的更新操作及时通知到其他线程



## Synchronized

### 作用

实现并发场景下的内码块和方法同步。

可以修改函数（静态及实例方法）及代码块

### 原理



监视器锁的本质是通过监视器对象完成。

### 锁的类型和等级



| 锁类型 | 定义                                    | 使用                                                         |
| ------ | --------------------------------------- | ------------------------------------------------------------ |
| 对象锁 | 含synchronized方法/代码库的类的实例对象 |                                                              |
| 方法锁 | 使用synchronized修饰的方法              |                                                              |
| 类锁   | 类锁实际锁的是class对象                 | 一个静态方法被synchronized修饰。此类所有的实例化对象在调用此方法时，共用同一把锁 |



### 使用场景



### 参考



https://blog.csdn.net/carson_ho/article/details/82992269



## AQS



AQS锁是Java同步机制的基石，很多同步类都使用了AQS的同步机制。下面分几种应用场景来分析AQS的实现原理，AQS的实现场景可分为互斥锁、共享锁、条件锁三种情况



### 互斥机制

互斥锁的情况的场景，比如ReentrantLock，

实现这需要继承AbstractQueuedSynchronizer抽象类，并实现两个方法：tryAcquire和tryRelease。

accquire和release是实现互斥机制的主体逻辑，下面将逐个分析：

```java
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
            return true;
        }
        return false;
    }
```

#### acquire

```java
public final void acquire(int arg) {
  	// 获取state资源，
    if (!tryAcquire(arg) &&
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
        selfInterrupt();
}
```
##### tryAcquire

```java
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    int c = getState();
    if (c == 0) {
        if (!hasQueuedPredecessors() &&
            compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(current);
            return true;
        }
    }
    else if (current == getExclusiveOwnerThread()) {
        int nextc = c + acquires;
        if (nextc < 0)
            throw new Error("Maximum lock count exceeded");
        setState(nextc);
        return true;
    }
    return false;
}
```



#### addWaiter



<img src="images/AQS.png" alt="AQS" style="zoom:67%;" />



```java
private Node addWaiter(Node mode) {
    Node node = new Node(Thread.currentThread(), mode);
    // 首先会直接进行一次CAS操作，如果失败（考虑共享锁等情况），则进行enq的自旋操作
    Node pred = tail;
    if (pred != null) {
        node.prev = pred;
        if (compareAndSetTail(pred, node)) {
            pred.next = node;
            return node;
        }
    }
    enq(node);
    return node;
}
```

##### enq

将无法获取到锁的线程入队

```java
private Node enq(final Node node) {
    for (;;) {
        Node t = tail;
      	// 如果列表为空则初始化首节点，否则进行一次CAS操作，失败后重试
      	// 首节点的waitStatus=0
        if (t == null) { // Must initialize
            if (compareAndSetHead(new Node()))
                tail = head;
        } else {
            node.prev = t;
            if (compareAndSetTail(t, node)) {
                t.next = node;
                return t;
            }
        }
    }
}
```



#### release

```java
public final boolean release(int arg) {
  	// tryRelease的操作一般是进行state的设置
    if (tryRelease(arg)) {
      	// 从CLH队列的头部开始唤醒后继等待节点
        Node h = head;
        if (h != null && h.waitStatus != 0)
            unparkSuccessor(h);
        return true;
    }
    return false;
}
```



##### tryRelease

以ReentrantLock为例。这里有个疑问：state的操作是不是原子的？

A:是，因为在accquire的时候state是volatile的，而且是CAS操作的，再加上线程内部的多次调用必然是异步的过程所以不会出现state多加或者多减的现象。

```java
protected final boolean tryRelease(int releases) {
    int c = getState() - releases;
    // 只有当前线程才可以进行释放锁的操作
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException();
    boolean free = false;
  	// 如果为0则进行释放锁的操作，否则只是更新state的状态
    if (c == 0) {
        free = true;
        setExclusiveOwnerThread(null);
    }
    setState(c);
    return free;
}
```

##### unparkSuccessor

唤醒后续节点

```java
private void unparkSuccessor(Node node) {
    /*
     * If status is negative (i.e., possibly needing signal) try
     * to clear in anticipation of signalling.  It is OK if this
     * fails or if status is changed by waiting thread.
     */
    int ws = node.waitStatus;
    if (ws < 0)
        compareAndSetWaitStatus(node, ws, 0);

    /*
     * Thread to unpark is held in successor, which is normally
     * just the next node.  But if cancelled or apparently null,
     * traverse backwards from tail to find the actual
     * non-cancelled successor.
     */
  	// 注意这里用next的原因是因为CLH队列在初始化的时候设置队列头部为空节点
    // 如果第一个等待节点被取消掉，那么从尾部进行遍历查找到最前的第一个有效节点
    Node s = node.next;
    if (s == null || s.waitStatus > 0) {
        s = null;
        for (Node t = tail; t != null && t != node; t = t.prev)
            if (t.waitStatus <= 0)
                s = t;
    }
    if (s != null)
        LockSupport.unpark(s.thread);
}
```



### 共享机制

AQS的共享锁机制主要由两个接口实现，继承者需要适量两个抽象接口:tryAcquireShared和tryReleaseShared



```java
public final void acquireShared(int arg) {
    if (tryAcquireShared(arg) < 0)
        doAcquireShared(arg);
}

public final boolean releaseShared(int arg) {
    if (tryReleaseShared(arg)) {
        doReleaseShared();
        return true;
    }
    return false;
}
```



##### 读写锁

以读写锁为例分析

读写锁巧妙设计了state，高16bit用于读锁，低16bit用于写锁。



##### tryAcquireShared

```java
protected final int tryAcquireShared(int unused) {
    /*
     * Walkthrough:
     * 1. If write lock held by another thread, fail.
     * 2. Otherwise, this thread is eligible for
     *    lock wrt state, so ask if it should block
     *    because of queue policy. If not, try
     *    to grant by CASing state and updating count.
     *    Note that step does not check for reentrant
     *    acquires, which is postponed to full version
     *    to avoid having to check hold count in
     *    the more typical non-reentrant case.
     * 3. If step 2 fails either because thread
     *    apparently not eligible or CAS fails or count
     *    saturated, chain to version with full retry loop.
     */
    Thread current = Thread.currentThread();
    int c = getState();
  	// 如果有写锁或者不是独占线程不是当前线程则返回-1
    if (exclusiveCount(c) != 0 &&
        getExclusiveOwnerThread() != current)
        return -1;
    int r = sharedCount(c);
    if (!readerShouldBlock() &&
        r < MAX_COUNT &&
        compareAndSetState(c, c + SHARED_UNIT)) {
        if (r == 0) {
            firstReader = current;
            firstReaderHoldCount = 1;
        } else if (firstReader == current) {
            firstReaderHoldCount++;
        } else {
            HoldCounter rh = cachedHoldCounter;
            if (rh == null || rh.tid != getThreadId(current))
                cachedHoldCounter = rh = readHolds.get();
            else if (rh.count == 0)
                readHolds.set(rh);
            rh.count++;
        }
        return 1;
    }
    return fullTryAcquireShared(current);
}
```

##### doAcquireShared

这里是获取不到锁，进行入队的流程。

```java
private void doAcquireShared(int arg) {
  	// 注意：与互斥锁不同的是这里node的nextWaiter字段为SHARED
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
        boolean interrupted = false;
        for (;;) {
            final Node p = node.predecessor();
          	// 第一次或者被unpark之后检查前置节点是否为head
            if (p == head) {
              	// 如果获取到锁，则r>=0，否则进行park操作
                // 读写锁返回1表示需要广播
                int r = tryAcquireShared(arg);
                if (r >= 0) {
                    setHeadAndPropagate(node, r);
                    p.next = null; // help GC
                    if (interrupted)
                        selfInterrupt();
                    failed = false;
                    return;
                }
            }
          	// 设置前驱节点状态为signal，成功后Park否则自旋操作
            if (shouldParkAfterFailedAcquire(p, node) &&
                parkAndCheckInterrupt())
                interrupted = true;
        }
    } finally {
        if (failed)
            cancelAcquire(node);
    }
}
```



###### shouldParkAfterFailedAcquire

检查前驱节点的节点状态，如果不是signal置为signal

```java
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;
    if (ws == Node.SIGNAL)
        /*
         * This node has already set status asking a release
         * to signal it, so it can safely park.
         */
        return true;
    if (ws > 0) {
        /*
         * Predecessor was cancelled. Skip over predecessors and
         * indicate retry.
         */
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
    } else {
        /*
         * waitStatus must be 0 or PROPAGATE.  Indicate that we
         * need a signal, but don't park yet.  Caller will need to
         * retry to make sure it cannot acquire before parking.
         */
        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    }
    return false;
}
```

### 互质机制



### 公平与非公平




[toc]



# Java FAQ

- HashMap结构，什么对象能做key?

hashmap是数组+单链表/红黑树的形式，key可以为空

具体实现原理参考数据结构章节



- StringBuilder和StringBuffer?

StringBuffer是线程安全的，因为其方法添加了synchronized类。

```java
@Override
public synchronized StringBuffer append(String str) {
    toStringCache = null;
    super.append(str);
    return this;
}
```

- synchronized实现原理

偏向锁、轻量级锁、重量级锁是如何过渡的？

监视器是什么？监视器和对象头的关系？监视器实现原理是什么？



- 对象的深拷贝与浅拷贝？

**浅拷贝**：创建一个新对象，然后将当前对象的非静态字段复制到该对象，如果字段类型是值类型（基本类型）的，那么对该字段进行复制；如果字段是引用类型的，则只复制该字段的引用而不复制引用指向的对象。此时新对象里面的引用类型字段相当于是原始对象里面引用类型字段的一个副本，原始对象与新对象里面的引用字段指向的是同一个对象。

**深拷贝**
了解了浅拷贝，那么深拷贝是什么也就很清楚了。即将引用类型的属性内容也拷贝一份新的。

那么，**实现深拷贝我这里收集到两种方式：**

第一种是给需要拷贝的引用类型也实现Cloneable接口并覆写clone方法；

第二种则是利用序列化。

- countdownlatch await方法是线程安全的吗？

是。内部使用了volatile和cas机制保证了原子性和可见性。但是可能会出现其他线程没有countdown导致线程

一直等待的情形，所以最好调用带超时时间的接口

```java
public boolean await(long timeout, TimeUnit unit)	throws InterruptedException
```

- Unsafe用途？



![img](images/unsafe.png)

参考文档：

https://www.jianshu.com/p/db8dce09232d



# 数据结构

## 队列

### 同步队列

#### 基本原理

SynchronousQueue是一个很有意思的队列，号称实现无锁同步，内部不存储任何元素，仅仅借助节点的状态和cas操作实现同步，假如没有消费者消费，也会造成生产者睡眠

下图是我示例程序head的一个镜像信息，从这里可以看出同步队里并不是不存信息，而是放到了栈中或者队列中。

```java
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;

/**
 * @author: yujingzhi
 * Version: 1.0
 */
public class TaskExecutor {
    private SynchronousQueue<String> queue = new SynchronousQueue();
    private static ExecutorService executorService = Executors.newFixedThreadPool(2);
    private List<String> data = Arrays.asList("a", "b");

    public void doAction() {
        for (int i =0; i<2; i++) {
            executorService.submit(new TaskEntry(i));
        }
    }

    class TaskEntry implements Runnable {
        private int loop;
        TaskEntry (int i) {
            loop = i;
        }
        public void run() {
            try {
                queue.put(data.get(loop));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
```

<img src="images/image-20201014102753802.png" alt="image-20201014102753802" style="zoom: 50%;" />

#### 实现原理

同步队列的take和put的操作区别在于传入对数据，一个是null一个是真实的数据e，transfer方法根据其来决定是REQUEST模式或者DATA模式。

tansferer有两种实现。一种TransferStack(LIFO)，一种是TransferQueue(FIFO)

```java
// take
public E take() throws InterruptedException {
  E e = transferer.transfer(null, false, 0);
  if (e != null)
    return e;
  Thread.interrupted();
  throw new InterruptedException();
}
// put
public void put(E e) throws InterruptedException {
    if (e == null) throw new NullPointerException();
    if (transferer.transfer(e, false, 0) == null) {
        Thread.interrupted();
        throw new InterruptedException();
    }
}
```

下面以TransferStack的实现来进行分析，TransferQueue原理类似。

transfer代码的分析，需要理解FULFILLING的概念，当栈中有数据，当前的take操作则为fullfiling，之前的put则awaitFulfill，反之亦然。

也就是说生产数据和消费数据的线程在同步队列中互为顾客，都有需求和满足需求的角色。

栈/队列中node的三种角色。

```java
/* Modes for SNodes, ORed together in node fields */
/** Node represents an unfulfilled consumer */
static final int REQUEST    = 0;
/** Node represents an unfulfilled producer */
static final int DATA       = 1;
/** Node is fulfilling another unfulfilled DATA or REQUEST */
static final int FULFILLING = 2;
```

核心代码逻辑transfer实现流程分析：

```java
/**
 * Puts or takes an item.
 */
@SuppressWarnings("unchecked")
E transfer(E e, boolean timed, long nanos) {
    /*
     * Basic algorithm is to loop trying one of three actions:
     *
     * 1. If apparently empty or already containing nodes of same
     *    mode, try to push node on stack and wait for a match,
     *    returning it, or null if cancelled.
     *
     * 2. If apparently containing node of complementary mode,
     *    try to push a fulfilling node on to stack, match
     *    with corresponding waiting node, pop both from
     *    stack, and return matched item. The matching or
     *    unlinking might not actually be necessary because of
     *    other threads performing action 3:
     *
     * 3. If top of stack already holds another fulfilling node,
     *    help it out by doing its match and/or pop
     *    operations, and then continue. The code for helping
     *    is essentially the same as for fulfilling, except
     *    that it doesn't return the item.
     */

    SNode s = null; // constructed/reused as needed
  	// 如果是take则mode为request,put则为data
    int mode = (e == null) ? REQUEST : DATA;

    for (;;) {
        SNode h = head;
      	// 1.如果栈中为空或者栈顶都为put或者request操作：此情形适用于连续take或者put的场景。
      	// 2.条件不成立时，说明之前的请求模式必然和自己的不同，也就是说上一个请求必然是'顾客'
        if (h == null || h.mode == mode) {  // empty or same-mode
            if (timed && nanos <= 0) {      // can't wait
                if (h != null && h.isCancelled())
                    casHead(h, h.next);     // pop cancelled node
                else
                    return null;
            } else if (casHead(h, s = snode(s, e, h, mode))) {
                SNode m = awaitFulfill(s, timed, nanos);
                if (m == s) {               // wait was cancelled
                    clean(s);
                    return null;
                }
                if ((h = head) != null && h.next == s)
                    casHead(h, s.next);     // help s's fulfiller
                return (E) ((mode == REQUEST) ? m.item : s.item);
            }
         
        } // 此步骤的主要作用是设置当前节点为 FULFILLING，不过需要首选判断下当前head是否已经是fulfill状态（考虑并发情况，如果是则先帮其设置matcher）
      	else if (!isFulfilling(h.mode)) { // try to fulfill
            if (h.isCancelled())            // already cancelled
                casHead(h, h.next);         // pop and retry
          	// 入栈，并设置mode为FULFILLING，使‘顾客’在awaitFulfill时得到满足
            else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                for (;;) { // loop until matched or waiters disappear
                    SNode m = s.next;       // m is s's match
                    if (m == null) {        // all waiters are gone
                        casHead(s, null);   // pop fulfill node
                        s = null;           // use new node next time
                        break;              // restart main loop
                    }
                    SNode mn = m.next;
                  	// 设置'顾客'匹配到的matcher：数据，如果'顾客'超时进行了park则unpark
                    if (m.tryMatch(s)) {
                        casHead(s, mn);     // pop both s and m
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    } else                  // lost match
                        s.casNext(m, mn);   // help unlink
                }
            }
        } else {                            // help a fulfiller
            SNode m = h.next;               // m is h's match
            if (m == null)                  // waiter is gone
                casHead(h, null);           // pop fulfilling node
            else {
                SNode mn = m.next;
                if (m.tryMatch(h))          // help match
                    casHead(h, mn);         // pop both h and m
                else                        // lost match
                    h.casNext(m, mn);       // help unlink
            }
        }
    }
}
```

tryMatch

```java
boolean tryMatch(SNode s) {
    if (match == null &&
        UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
        Thread w = waiter;
        if (w != null) {    // waiters need at most one unpark
            waiter = null;
            LockSupport.unpark(w);
        }
        return true;
    }
    return match == s;
}
```

等待满足的生成者出现，返回数据，自旋一定时间后睡眠。

```java

SNode awaitFulfill(SNode s, boolean timed, long nanos) {
    final long deadline = timed ? System.nanoTime() + nanos : 0L;
    Thread w = Thread.currentThread();
    int spins = (shouldSpin(s) ?
                 (timed ? maxTimedSpins : maxUntimedSpins) : 0);
    for (;;) {
        if (w.isInterrupted())
            s.tryCancel();
      	// 有匹配到的matcher则返回
        SNode m = s.match;
        if (m != null)
            return m;
        if (timed) {
            nanos = deadline - System.nanoTime();
            if (nanos <= 0L) {
                s.tryCancel();
                continue;
            }
        }
      	// 先自旋，自旋一定次数后park
        if (spins > 0)
            spins = shouldSpin(s) ? (spins-1) : 0;
        else if (s.waiter == null)
            s.waiter = w; // establish waiter so can park next iter
        else if (!timed)
            LockSupport.park(this);
        else if (nanos > spinForTimeoutThreshold)
            LockSupport.parkNanos(this, nanos);
    }
}
```













### 阻塞队列

想要了解什么是阻塞队列，其实现了什么功能？最好了解下其实现的接口定义：

| 方法                                                         | 是否阻塞 | 说明                 |
| ------------------------------------------------------------ | -------- | -------------------- |
| boolean add(E e)                                             | N        | 违反空间大小时会报错 |
| boolean offer(E e)                                           | N        |                      |
| boolean offer(E e, long timeout, TimeUnit unit)<br/>        throws InterruptedException | N        | 超时机制             |
| void put(E e) throws InterruptedException                    | Y        | 添加队列元素         |
| E take() throws InterruptedException                         | Y        | 获取队列元素         |
| E poll(long timeout, TimeUnit unit)<br/>        throws InterruptedException | N        | 支持轮询             |



```
public interface BlockingQueue<E> extends Queue<E> {

    boolean add(E e);

    boolean offer(E e);

    /** 添加时阻塞
     * Inserts the specified element into this queue, waiting if necessary
     * for space to become available.
     *
     * @param e the element to add
     * @throws InterruptedException if interrupted while waiting
     * @throws ClassCastException if the class of the specified element
     *         prevents it from being added to this queue
     * @throws NullPointerException if the specified element is null
     * @throws IllegalArgumentException if some property of the specified
     *         element prevents it from being added to this queue
     */
    void put(E e) throws InterruptedException;

    /**
    	添加，支持超时机制
     * Inserts the specified element into this queue, waiting up to the
     * specified wait time if necessary for space to become available.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return {@code true} if successful, or {@code false} if
     *         the specified waiting time elapses before space is available
     * @throws InterruptedException if interrupted while waiting
     * @throws ClassCastException if the class of the specified element
     *         prevents it from being added to this queue
     * @throws NullPointerException if the specified element is null
     * @throws IllegalArgumentException if some property of the specified
     *         element prevents it from being added to this queue
     */
    boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException;

    /** 阻塞获取
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     */
    E take() throws InterruptedException;

    /** 超时获取
     * Retrieves and removes the head of this queue, waiting up to the
     * specified wait time if necessary for an element to become available.
     *
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is available
     * @throws InterruptedException if interrupted while waiting
     */
    E poll(long timeout, TimeUnit unit)
        throws InterruptedException;

    /**
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking, or {@code Integer.MAX_VALUE} if there is no intrinsic
     * limit.
     *
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting {@code remainingCapacity}
     * because it may be the case that another thread is about to
     * insert or remove an element.
     *
     * @return the remaining capacity
     */
    int remainingCapacity();

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     * @throws ClassCastException if the class of the specified element
     *         is incompatible with this queue
     *         (<a href="../Collection.html#optional-restrictions">optional</a>)
     * @throws NullPointerException if the specified element is null
     *         (<a href="../Collection.html#optional-restrictions">optional</a>)
     */
    boolean remove(Object o);

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     * @throws ClassCastException if the class of the specified element
     *         is incompatible with this queue
     *         (<a href="../Collection.html#optional-restrictions">optional</a>)
     * @throws NullPointerException if the specified element is null
     *         (<a href="../Collection.html#optional-restrictions">optional</a>)
     */
    public boolean contains(Object o);
		...
}
```





## Map



### HashMap

hashmap是数组+单链表/红黑树的形式

map节点数据结构：

```java
static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    V value;
    Node<K,V> next;
  Node(int hash, K key, V value, Node<K,V> next) {
    this.hash = hash;
    this.key = key;
    this.value = value;
    this.next = next;
  }
  ...
}  
  
```

红黑树节点数据结构

```java
static final class TreeNode<K,V> extends LinkedHashMap.Entry<K,V> {
    TreeNode<K,V> parent;  // red-black tree links
    TreeNode<K,V> left;
    TreeNode<K,V> right;
    TreeNode<K,V> prev;    // needed to unlink next upon deletion
    boolean red;
    TreeNode(int hash, K key, V val, Node<K,V> next) {
        super(hash, key, val, next);
    }
  ...
}    
```

#### put/putVal

```java
public V put(K key, V value) {
  
	return putVal(hash(key), key, value, false, true);
}

final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
               boolean evict) {
    Node<K,V>[] tab;  // 当前map数组
  	Node<K,V> p; 			// 当前key所在的槽位
  	int n, i;
  	// map为空进行第一次拓容
    if ((tab = table) == null || (n = tab.length) == 0)
        n = (tab = resize()).length;
  	// 取模后如果为空则生成一个新的节点
    if ((p = tab[i = (n - 1) & hash]) == null)
        tab[i] = newNode(hash, key, value, null);
    else {
        Node<K,V> e; K k;
      	// 如果hash并且key相同则覆盖
        if (p.hash == hash &&
            ((k = p.key) == key || (key != null && key.equals(k))))
            e = p;
      	// 如果是红黑树则往树中插入子节点
        else if (p instanceof TreeNode)
            e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
        else {
          	// 当前hash槽小于7时进行链表操作，大于等于7时进行红黑树化
            for (int binCount = 0; ; ++binCount) {
                if ((e = p.next) == null) {
                    p.next = newNode(hash, key, value, null);
                  	// 槽位节点数大于等于7时进行红黑树化
                    if (binCount >= TREEIFY_THRESHOLD - 1) // -1 for 1st
                        treeifyBin(tab, hash);
                    break;
                }
                if (e.hash == hash &&
                    ((k = e.key) == key || (key != null && key.equals(k))))
                    break;
                p = e;
            }
        }
        if (e != null) { // existing mapping for key
            V oldValue = e.value;
            if (!onlyIfAbsent || oldValue == null)
                e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
    }
  	// 修改次数
    ++modCount;
    // 当map的大小大于指定阈值则进行拓容；map拓容以指数的形式拓展
    if (++size > threshold)
        resize();
    afterNodeInsertion(evict);
    return null;
}
```



#### resize

拓容分以下几种情况：

1.初始化拓容

2.node容量达到阈值需要拓容

```java
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    int newCap, newThr = 0;
  	/*
  	 * a.如果老的数组大小大于0则进行2*oldCap指数拓容
  	 * b.否则存在oldThr大于0则新的数据大小设为oldThr
  	 * c.最后取初始化大小16，阈值为16*0.75
  	 *
  	**/
    if (oldCap > 0) {
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return oldTab;
        }
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                 oldCap >= DEFAULT_INITIAL_CAPACITY)
            newThr = oldThr << 1; // double threshold
    }
    else if (oldThr > 0) // initial capacity was placed in threshold
        newCap = oldThr;
    else {               // zero initial threshold signifies using defaults
        newCap = DEFAULT_INITIAL_CAPACITY;
        newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
    }
  
    if (newThr == 0) {
        float ft = (float)newCap * loadFactor;
        newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                  (int)ft : Integer.MAX_VALUE);
    }
    threshold = newThr;
    @SuppressWarnings({"rawtypes","unchecked"})
  	// 生成新的数组
    Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
    table = newTab;
    if (oldTab != null) {
      	// 遍历老数组进行取新的数组大小进行重hash
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e;
            if ((e = oldTab[j]) != null) {
                oldTab[j] = null;
                if (e.next == null)
                    newTab[e.hash & (newCap - 1)] = e;
                else if (e instanceof TreeNode)
                    ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                else { // preserve order
                    Node<K,V> loHead = null, loTail = null;
                    Node<K,V> hiHead = null, hiTail = null;
                    Node<K,V> next;
                    do {
                        next = e.next;
                        if ((e.hash & oldCap) == 0) {
                            if (loTail == null)
                                loHead = e;
                            else
                                loTail.next = e;
                            loTail = e;
                        }
                        else {
                            if (hiTail == null)
                                hiHead = e;
                            else
                                hiTail.next = e;
                            hiTail = e;
                        }
                    } while ((e = next) != null);
                    if (loTail != null) {
                        loTail.next = null;
                        newTab[j] = loHead;
                    }
                    if (hiTail != null) {
                        hiTail.next = null;
                        newTab[j + oldCap] = hiHead;
                    }
                }
            }
        }
    }
    return newTab;
}
```

### LinkedHashMap



Linked相对于HashMap实现了访问有序和插入访问有序。

其主要设计思想有两个方便：

一、在于内部封装了node结构体，使其可以成为双链形式，以便达到访问时有序。

```java
static class Entry<K,V> extends HashMap.Node<K,V> {
    Entry<K,V> before, after;
    Entry(int hash, K key, V value, Node<K,V> next) {
        super(hash, key, value, next);
    }
}
```



二、继承hashmap在生成新节点时，覆盖了newNode方法，使其可以生成有序链表

比如：

put -> putVal -> newNode

```java
Node<K,V> newNode(int hash, K key, V value, Node<K,V> e) {
    LinkedHashMap.Entry<K,V> p =
        new LinkedHashMap.Entry<K,V>(hash, key, value, e);
    linkNodeLast(p);
    return p;
}
```



### ConcurrentHashMap





```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode());
    int binCount = 0;
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null,
                         new Node<K,V>(hash, key, value, null)))
                break;                   // no lock when adding to empty bin
        }
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);
        else {
            V oldVal = null;
            synchronized (f) {
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            K ek;
                            if (e.hash == hash &&
                                ((ek = e.key) == key ||
                                 (ek != null && key.equals(ek)))) {
                                oldVal = e.val;
                                if (!onlyIfAbsent)
                                    e.val = value;
                                break;
                            }
                            Node<K,V> pred = e;
                            if ((e = e.next) == null) {
                                pred.next = new Node<K,V>(hash, key,
                                                          value, null);
                                break;
                            }
                        }
                    }
                    else if (f instanceof TreeBin) {
                        Node<K,V> p;
                        binCount = 2;
                        if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                                       value)) != null) {
                            oldVal = p.val;
                            if (!onlyIfAbsent)
                                p.val = value;
                        }
                    }
                }
            }
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD)
                    treeifyBin(tab, i);
                if (oldVal != null)
                    return oldVal;
                break;
            }
        }
    }
    addCount(1L, binCount);
    return null;
}
```

# 多线程

## 线程

下面以java中的Thread.java来进行源码的剖析，通过此类的解读来了解java线程的机制和原理。

### yield

只是出让cpu，线程状态处于RUNNABLE状态

### join

join通常用来等待某个线程执行完毕后的结果，因为线程结束会调用nofity通知

```java
    public final synchronized void join(long millis)
    throws InterruptedException {
        long base = System.currentTimeMillis();
        long now = 0;

        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (millis == 0) {
          	// 当线程退出后会继续
            while (isAlive()) {
                wait(0);
            }
        } else {
            while (isAlive()) {
                long delay = millis - now;
                if (delay <= 0) {
                    break;
                }
                wait(delay);
                now = System.currentTimeMillis() - base;
            }
        }
    }
```

### 线程状态



![img](images/thread-status.png)

```java
public enum State {
    /**
     * 还未启动
     */
    NEW,

    /**
     * jvm中处于可以运行的状态，但是可能并没有实际执行；可能在等待cpu资源
     */
    RUNNABLE,

    /**
     * 主要是等待synchronized才会出现
     * Thread state for a thread blocked waiting for a monitor lock.
     * A thread in the blocked state is waiting for a monitor lock
     * to enter a synchronized block/method or
     * reenter a synchronized block/method after calling
     * {@link Object#wait() Object.wait}.
     */
    BLOCKED,

    /**
     * Thread state for a waiting thread.
     * A thread is in the waiting state due to calling one of the
     * following methods:
     * <ul>
     *   <li>{@link Object#wait() Object.wait} with no timeout</li>
     *   <li>{@link #join() Thread.join} with no timeout</li>
     *   <li>{@link LockSupport#park() LockSupport.park}</li>
     * </ul>
     *
     * <p>A thread in the waiting state is waiting for another thread to
     * perform a particular action.
     *
     * For example, a thread that has called <tt>Object.wait()</tt>
     * on an object is waiting for another thread to call
     * <tt>Object.notify()</tt> or <tt>Object.notifyAll()</tt> on
     * that object. A thread that has called <tt>Thread.join()</tt>
     * is waiting for a specified thread to terminate.
     */
    WAITING,

    /**
     * Thread state for a waiting thread with a specified waiting time.
     * A thread is in the timed waiting state due to calling one of
     * the following methods with a specified positive waiting time:
     * <ul>
     *   <li>{@link #sleep Thread.sleep}</li>
     *   <li>{@link Object#wait(long) Object.wait} with timeout</li>
     *   <li>{@link #join(long) Thread.join} with timeout</li>
     *   <li>{@link LockSupport#parkNanos LockSupport.parkNanos}</li>
     *   <li>{@link LockSupport#parkUntil LockSupport.parkUntil}</li>
     * </ul>
     */
    TIMED_WAITING,

    /**
     * Thread state for a terminated thread.
     * The thread has completed execution.
     */
    TERMINATED;
}
```



## ThreadPoolExecutor



### 综述



```java
protected ThreadPoolExecutor createExecutor(
      int corePoolSize, int maxPoolSize, int keepAliveSeconds, BlockingQueue<Runnable> queue,
      ThreadFactory threadFactory, RejectedExecutionHandler rejectedExecutionHandler) {

   return new ThreadPoolExecutor(corePoolSize, maxPoolSize,
         keepAliveSeconds, TimeUnit.SECONDS, queue, threadFactory, rejectedExecutionHandler);
}
```



接口线程工厂：可以自定义线程创建过程，比如设置线程优先级、线程组、是否守护线程等属性

```java
public interface ThreadFactory {

    /**
     * Constructs a new {@code Thread}.  Implementations may also initialize
     * priority, name, daemon status, {@code ThreadGroup}, etc.
     *
     * @param r a runnable to be executed by new thread instance
     * @return constructed thread, or {@code null} if the request to
     *         create a thread is rejected
     */
    Thread newThread(Runnable r);
}
```



自定义拒接信息收集：比如抛出一个特定异常和相关信息

```java
public interface RejectedExecutionHandler {

    /**
     * Method that may be invoked by a {@link ThreadPoolExecutor} when
     * {@link ThreadPoolExecutor#execute execute} cannot accept a
     * task.  This may occur when no more threads or queue slots are
     * available because their bounds would be exceeded, or upon
     * shutdown of the Executor.
     *
     * <p>In the absence of other alternatives, the method may throw
     * an unchecked {@link RejectedExecutionException}, which will be
     * propagated to the caller of {@code execute}.
     *
     * @param r the runnable task requested to be executed
     * @param executor the executor attempting to execute this task
     * @throws RejectedExecutionException if there is no remedy
     */
    void rejectedExecution(Runnable r, ThreadPoolExecutor executor);
}
```

综述：

ctl关于状态位的描述：

clt将高3bit保留为状态位，表示线程池的状态，初始化为RUNNING。低29bit表示能够提交的线程个数。

![image-20200923095805364](images/ctl.png)

```java
private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
private static final int COUNT_BITS = Integer.SIZE - 3;
private static final int CAPACITY   = (1 << COUNT_BITS) - 1;
 // runState is stored in the high-order bits
private static final int RUNNING    = -1 << COUNT_BITS; // 高三比特位：111
private static final int SHUTDOWN   =  0 << COUNT_BITS; // 000
private static final int STOP       =  1 << COUNT_BITS; // 001
private static final int TIDYING    =  2 << COUNT_BITS; // 010
private static final int TERMINATED =  3 << COUNT_BITS; // 011
```



#### 线程状态解读

| 状态       | 描述                                                   |      |
| ---------- | ------------------------------------------------------ | ---- |
| RUNNING    | 接受新任务提交，处理工作队列中的任务                   |      |
| SHUTDOWN   | 不接受新任务提交，处理工作队列中的任务                 |      |
| STOP       | 不接受新任务提交，不处理队列中的任务，中断处理中的任务 |      |
| TIDYING    | 所有任务已经停止，workcount=0                          |      |
| TERMINATED | 线程池终止                                             |      |

ThreadPoolExecutor主要实现了以下接口，包括提交任务、停止任务等。下面会逐个分析：

```java
package java.util.concurrent;
import java.util.List;
import java.util.Collection;

public interface ExecutorService extends Executor {

  	// 停止任务
    void shutdown();

    List<Runnable> shutdownNow();

    boolean isShutdown();

    boolean isTerminated();

    boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException;

    <T> Future<T> submit(Callable<T> task);

    <T> Future<T> submit(Runnable task, T result);

    Future<?> submit(Runnable task);

    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException;

    <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException;

    <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                    long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;
}

public interface Executor {

    /**
     * Executes the given command at some time in the future.  The command
     * may execute in a new thread, in a pooled thread, or in the calling
     * thread, at the discretion of the {@code Executor} implementation.
     *
     * @param command the runnable task
     * @throws RejectedExecutionException if this task cannot be
     * accepted for execution
     * @throws NullPointerException if command is null
     */
    void execute(Runnable command);
}
```



#### 设计思想



### 源代码

#### 数据结构

Worker类继承了AbstractQueuedSynchronizer，实现了互斥锁的功能。主要是控制线程为否可以被中断。firstTask为空可以表示当前线程是否空闲。runWorker主体流程是线程处理的主体流程

```java
private final class Worker
    extends AbstractQueuedSynchronizer
    implements Runnable
{
    /** Thread this worker is running in.  Null if factory fails. */
    final Thread thread;
    /** Initial task to run.  Possibly null. */
    Runnable firstTask;
    /** Per-thread task counter */
    volatile long completedTasks;

    Worker(Runnable firstTask) {
        setState(-1); // inhibit interrupts until runWorker
        this.firstTask = firstTask;
        this.thread = getThreadFactory().newThread(this);
    }

    /** Delegates main run loop to outer runWorker  */
    public void run() {
        runWorker(this);
    }
		...
}
```



#### execute

执行实现Runnable的线程。

```java
public void execute(Runnable command) {
    if (command == null)
        throw new NullPointerException();
    // 如果工作线程数小于核心线程数则添加工作线程
    int c = ctl.get();
    if (workerCountOf(c) < corePoolSize) {
        if (addWorker(command, true))
            return;
        c = ctl.get();
    }
    if (isRunning(c) && workQueue.offer(command)) {
        int recheck = ctl.get();
        if (! isRunning(recheck) && remove(command))
            reject(command);
        else if (workerCountOf(recheck) == 0)
            addWorker(null, false);
    }
    else if (!addWorker(command, false))
        reject(command);
}
```

##### addWorker

添加工作线程主体逻辑。参数core表示是否添加核心线程以外的线程。

这里主体逻辑可以分为两个阶段：

a.workercount计数修改。这里用到了cas机制

b.添加工作线程到hashset中

```java
private boolean addWorker(Runnable firstTask, boolean core) {
    retry:
    for (;;) {
        int c = ctl.get();
        int rs = runStateOf(c);

        // Check if queue empty only if necessary.
        if (rs >= SHUTDOWN &&
            ! (rs == SHUTDOWN &&
               firstTask == null &&
               ! workQueue.isEmpty()))
            return false;

        for (;;) {
            int wc = workerCountOf(c);
            if (wc >= CAPACITY ||
                wc >= (core ? corePoolSize : maximumPoolSize))
                return false;
            if (compareAndIncrementWorkerCount(c))
                break retry;
            c = ctl.get();  // Re-read ctl
            if (runStateOf(c) != rs)
                continue retry;
            // else CAS failed due to workerCount change; retry inner loop
        }
    }

    boolean workerStarted = false;
    boolean workerAdded = false;
    Worker w = null;
    try {
        w = new Worker(firstTask);
        final Thread t = w.thread;
        if (t != null) {
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                // Recheck while holding lock.
                // Back out on ThreadFactory failure or if
                // shut down before lock acquired.
                int rs = runStateOf(ctl.get());

                if (rs < SHUTDOWN ||
                    (rs == SHUTDOWN && firstTask == null)) {
                    if (t.isAlive()) // precheck that t is startable
                        throw new IllegalThreadStateException();
                    workers.add(w);
                    int s = workers.size();
                    if (s > largestPoolSize)
                        largestPoolSize = s;
                    workerAdded = true;
                }
            } finally {
                mainLock.unlock();
            }
            if (workerAdded) {
              	// 运行工作线程 见runWorker流程
                t.start();
                workerStarted = true;
            }
        }
    } finally {
        if (! workerStarted)
            addWorkerFailed(w);
    }
    return workerStarted;
}
```

##### runWorker

运行工作线程主体逻辑。如果worker中的task为空，则从队列中取，在lock之前线程是可以被打断的。

```java
final void runWorker(Worker w) {
    Thread wt = Thread.currentThread();
    Runnable task = w.firstTask;
    w.firstTask = null;
    w.unlock(); // allow interrupts
    boolean completedAbruptly = true;
    try {
        while (task != null || (task = getTask()) != null) {
            w.lock();
            // If pool is stopping, ensure thread is interrupted;
            // if not, ensure thread is not interrupted.  This
            // requires a recheck in second case to deal with
            // shutdownNow race while clearing interrupt
            if ((runStateAtLeast(ctl.get(), STOP) ||
                 (Thread.interrupted() &&
                  runStateAtLeast(ctl.get(), STOP))) &&
                !wt.isInterrupted())
                wt.interrupt();
            try {
                beforeExecute(wt, task);
                Throwable thrown = null;
                try {
                    task.run();
                } catch (RuntimeException x) {
                    thrown = x; throw x;
                } catch (Error x) {
                    thrown = x; throw x;
                } catch (Throwable x) {
                    thrown = x; throw new Error(x);
                } finally {
                    afterExecute(task, thrown);
                }
            } finally {
                task = null;
                w.completedTasks++;
                w.unlock();
            }
        }
        completedAbruptly = false;
    } finally {
        processWorkerExit(w, completedAbruptly);
    }
}
```

# 引用

## 分类

Java引用可分为强引用，软引用，弱引用和虚引用。

为甚要用这种划分呢？是为了能够使java对象的内存回收变得更加灵活、可控。

### 强引用



普通的java对象都是强引用，比如new一个新对象。

**强引用**是使用最普遍的引用。如果一个对象具有强引用，那**垃圾回收器**绝不会回收它。如下：



### 软引用

**不够才会收。**

如果一个对象只具有**软引用**，则**内存空间充足**时，**垃圾回收器**就**不会**回收它；如果**内存空间不足**了，就会**回收**这些对象的内存。

### 弱引用

**够不够都回收。**

弱引用与软引用的区别在于：只具有弱引用的对象拥有更短暂的生命周期。在垃圾回收器线程扫描它所管辖的内存区域的过程中，一旦发现了只具有弱引用的对象，不管当前内存空间足够与否，都会回收它的内存。不过，由于垃圾回收器是一个优先级很低的线程，因此不一定会很快发现那些只具有弱引用的对象。

### 虚引用

**虚引用**顾名思义，就是**形同虚设**。与其他几种引用都不同，**虚引用**并**不会**决定对象的**生命周期**。如果一个对象**仅持有虚引用**，那么它就和**没有任何引用**一样，在任何时候都可能被垃圾回收器回收。





# Java同步机制

## 概述

可以分为内置锁、显示锁（lock）、volatile及原子变量
同步的重点在于保证共享对象的内存可见性、对共享操作的原子性及有序性

## ThreadLocal

原理：

Thread类内部有个字段threadLocals可以以键值对的方式存储线程独立的变量或值。ThreadLocalMap内部是一个数组用来缓存变量(类似hashmap)。Entry

```java
// Thread.java里的字段
ThreadLocal.ThreadLocalMap threadLocals = null;
```

Entry的key是一个软引用，value是强引用，key如果被回收掉，value会造成gc无法回收，因为存在Thread->threadLocals->value这样的强引用，索引对于Key为null的情况需要对value进行处理；

但是jdk内部在get或者set会定时清理key为null的情况，比如getEntryAfterMiss等方法的调用；

但是这样无法完全保证能清理干净，因为如果提前找到相应的key或者很久不去操作就又会有内存泄漏的可能。

```java
// ThreadLocal.java里的ThreadLocalMap实现
// 初始化Entry数组，不过这里需要注意的是entry是一个弱引用
ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
  table = new Entry[INITIAL_CAPACITY];
  int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
  table[i] = new Entry(firstKey, firstValue);
  size = 1;
  setThreshold(INITIAL_CAPACITY);
}
```

get	

```java
public T get() {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        ThreadLocalMap.Entry e = map.getEntry(this);
        if (e != null) {
            @SuppressWarnings("unchecked")
            T result = (T)e.value;
            return result;
        }
    }
  	// 初始化threadlocalmap
    return setInitialValue();
}

private T setInitialValue() {
  T value = initialValue();
  Thread t = Thread.currentThread();
  ThreadLocalMap map = getMap(t);
  if (map != null)
    map.set(this, value);
  else
    // 创建threadlocal
    createMap(t, value);
  return value;
}

void createMap(Thread t, T firstValue) {
  t.threadLocals = new ThreadLocalMap(this, firstValue);
}

static class Entry extends WeakReference<ThreadLocal<?>> {
  /** The value associated with this ThreadLocal. */
  Object value;

  Entry(ThreadLocal<?> k, Object v) {
    super(k);
    value = v;
  }
}
```

set	

```java
public void set(T value) {
    Thread t = Thread.currentThread();
    ThreadLocalMap map = getMap(t);
    if (map != null)
        map.set(this, value);
    else
        createMap(t, value);
}
```

可能出现的问题：

a.initValue为空的情况。

b.内存泄漏

我们能够认识到ThreadLocal事实上是与线程绑定的一个变量，如此就会出现一个问题：假设没有将ThreadLocal内的变量删除（remove）或替换，它的生命周期将会与线程共存,如果不remove掉，很可能会出现**内存泄漏**的问题。

参考：

https://blog.csdn.net/vicoqi/article/details/79743112

https://www.cnblogs.com/huxipeng/p/9289191.html

## volatile

用来确保变量的更新操作及时通知到其他线程



## Synchronized

### 作用

实现并发场景下的内码块和方法同步。可以修饰函数（静态及实例方法）及代码块；synchronized是**非公平**的可重入锁。

### 原理

监视器锁的本质是通过监视器对象完成。

### 锁的类型和等级

| 锁类型 | 定义                                    | 使用                                                         |
| ------ | --------------------------------------- | ------------------------------------------------------------ |
| 对象锁 | 含synchronized方法/代码库的类的实例对象 |                                                              |
| 方法锁 | 使用synchronized修饰的方法              |                                                              |
| 类锁   | 类锁实际锁的是class对象                 | 一个静态方法被synchronized修饰。此类所有的实例化对象在调用此方法时，共用同一把锁 |

### 锁的升级

对于synchronized锁而言，锁的升级是通过对象头markword中的锁标记位和是否偏向锁来升级的。

**无锁 -> 偏向锁 -> 轻量级锁 -> 重量级锁**

如果对象没有被锁，则处于无锁状态。

#### 偏向锁

- 是否默认开启

    默认开启。

- jvm标记

    开启偏向锁：-XX:+UseBiasedLocking -XX:BiasedLockingStartupDelay=0

    关闭偏向锁：-XX:-UseBiasedLocking

    

它的作用主要用来优化同一个线程重复获取锁的情况，当线程访问synchronized时会设置偏向锁的标记位和线程id。当有多个线程同时竞争时，偏向锁会撤销（但是需要等到全局安全点并stw）撤销偏向锁后恢复到未锁定（标志位为“01”）或轻量级锁（标志位为“00”）的状态。

**对于类的synchronized，其markword是在哪里存储的？**



#### 轻量级锁

这个时候，多线程竞争锁，未得到锁的线程在用户态进行自旋操作获取锁。自旋到一定次数之后会升级为重量级锁，线程睡眠（需要进行内核态的系统调用）

JVM对于自旋次数的选择，jdk1.5默认为10次，在1.6引入了**适应性自旋锁**，适应性自旋锁意味着自旋的时间不在是固定的了，而是由前一次在同一个锁上的自旋时间以及锁的拥有者的状态来决定，基本认为一个线程上下文切换的时间是最佳的一个时间。

#### 锁的粗化与消除

JIT编译器在执行动态编译时，若发现**前后相邻的synchronized块使用的是同一个锁对象**，那么它就会把这几个synchronized块给**合并**为一个较大的同步块，这样做的好处在于线程在执行这些代码时，就无需频繁申请与释放锁了，从而达到申请与释放锁一次，就可以执行完全部的同步代码块，从而提升了性能。

> https://www.yuque.com/wanghuaihoho/gobbon/mmpdby

#### 死锁活锁

##### 死锁

死锁是必然发生在多操作者（M>=2个）情况下，争夺多个资源（N>=2个，且N<=M）才会发生这种情况。

> 死锁的发生必须具备以下四个必要条件。 
>
> 1. **互斥条件**：指进程对所分配到的资源进行排它性使用，即在一段时间内某资源只由一个进程占用。如果此时还有其它进程请求资源，则请求者只能等待，直至占有资源的进程用毕释放。
>
> 2. **请求和保持条件**：指进程已经保持至少一个资源，但又提出了新的资源请求，而该资源已被其它进程占有，此时请求进程阻塞，但又对自己已获得的其它资源保持不放。
>
> 3. **不剥夺条件**：指进程已获得的资源，在未使用完之前，不能被剥夺，只能在使用完时由自己释放。
>
> 4. **环路等待条件**：指在发生死锁时，必然存在一个进程——资源的环形链，即进程集合{P0，P1，P2，···，Pn}中的P0正在等待一个P1占用的资源；P1正在等待P2占用的资源，……，Pn正在等待已被P0占用的资源。
>
>     
>
> ​    理解了死锁的原因，尤其是产生死锁的四个必要条件，就可以最大可能地避免、预防和解除死锁。只要打破四个必要条件之一就能有效预防死锁的发生：
>    打破互斥条件：改造独占性资源为虚拟资源，大部分资源已无法改造。
>    打破不可抢占条件：当一进程占有一独占性资源后又申请一独占性资源而无法满足，则退出原占有的资源。
>    打破占有且申请条件：采用资源预先分配策略，即进程运行前申请全部资源，满足则运行，不然就等待，这样就不会占有且申请。
>    打破循环等待条件：实现资源有序分配策略，对所有设备实现分类编号，所有进程只能采用按序号递增的形式申请资源。
>
>    
>
> 避免死锁常见的算法有有序资源分配法、银行家算法



##### 活锁 

两个线程在尝试拿锁的机制中，发生多个线程之间互相谦让，不断发生同一个线程总是拿到同一把锁，在尝试拿另一把锁时因为拿不到，而将本来已经持有的锁释放的过程。

解决办法：每个线程休眠随机数，错开拿锁的时间。

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
  	/* 获取state资源，如果没有获取成功则以互斥的方式加入等待队列
  	 */
    if (!tryAcquire(arg) &&
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
        selfInterrupt();
}
```

##### tryAcquire

以具体实现类为准，这里以ReentrantLock为例

```java
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread();
    /* 获取当前state
     * a.如果为0表示没有线程占有锁进行获取锁的操作：如果CLH队列中没有前驱节点则进行CAS操作将state置为1
     * b.如果不为0但是持有锁的线程是当前线程，则对state进行+1操作，此步骤是为了这次可重入
     */
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



<img src="C:/Users/ykongfu/Desktop/images/AQS.png" alt="AQS" style="zoom:67%;" />



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

##### acquireQueued



```java
final boolean acquireQueued(final Node node, int arg) {
    boolean failed = true;
    try {
        boolean interrupted = false;
        for (;;) {
            final Node p = node.predecessor();
            // 当头节点是head时
            if (p == head && tryAcquire(arg)) {
                // CLH是通过设置head出队，也就是FIFO的方式
                setHead(node);
                p.next = null; // help GC
                failed = false;
                return interrupted;
            }
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

这里是获取不到锁，进行入队的流程。和互斥模式的acquireQueued一样都有一个自旋的操作，获取不到锁就设置前驱节点状态然后park；不同的地方在于共享模式下会进行广播操作，通知CLH队列中的等待者。

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
                  	// 通知共享节点
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

###### setHeadAndPropagate

```java
private void setHeadAndPropagate(Node node, int propagate) {
    Node h = head; // Record old head for check below
    // 出队列
    setHead(node);
    /*
     * Try to signal next queued node if:
     *   Propagation was indicated by caller,
     *     or was recorded (as h.waitStatus either before
     *     or after setHead) by a previous operation
     *     (note: this uses sign-check of waitStatus because
     *      PROPAGATE status may transition to SIGNAL.)
     * and
     *   The next node is waiting in shared mode,
     *     or we don't know, because it appears null
     *
     * The conservatism in both of these checks may cause
     * unnecessary wake-ups, but only when there are multiple
     * racing acquires/releases, so most need signals now or soon
     * anyway.
     */
    if (propagate > 0 || h == null || h.waitStatus < 0 ||
        (h = head) == null || h.waitStatus < 0) {
        Node s = node.next;
        // 这里判断s是否是共享的node节点，区分互斥节点
        if (s == null || s.isShared())
            doReleaseShared();
    }
}
```



##### releaseShared

```
public final boolean releaseShared(int arg) {
	// 设置state成功后
    if (tryReleaseShared(arg)) {
        doReleaseShared();
        return true;
    }
    return false;
}
```



###### tryReleaseShared

```java
protected final boolean tryReleaseShared(int unused) {
    Thread current = Thread.currentThread();
    if (firstReader == current) {
        // assert firstReaderHoldCount > 0;
        if (firstReaderHoldCount == 1)
            firstReader = null;
        else
            firstReaderHoldCount--;
    } else {
        HoldCounter rh = cachedHoldCounter;
        if (rh == null || rh.tid != getThreadId(current))
            rh = readHolds.get();
        int count = rh.count;
        if (count <= 1) {
            readHolds.remove();
            if (count <= 0)
                throw unmatchedUnlockException();
        }
        --rh.count;
    }
    for (;;) {
        int c = getState();
        int nextc = c - SHARED_UNIT;
        if (compareAndSetState(c, nextc))
            // Releasing the read lock has no effect on readers,
            // but it may allow waiting writers to proceed if
            // both read and write locks are now free.
            return nextc == 0;
    }
}
```

doReleaseShared

```java
private void doReleaseShared() {
    /*
     * Ensure that a release propagates, even if there are other
     * in-progress acquires/releases.  This proceeds in the usual
     * way of trying to unparkSuccessor of head if it needs
     * signal. But if it does not, status is set to PROPAGATE to
     * ensure that upon release, propagation continues.
     * Additionally, we must loop in case a new node is added
     * while we are doing this. Also, unlike other uses of
     * unparkSuccessor, we need to know if CAS to reset status
     * fails, if so rechecking.
     */
    for (;;) {
        Node h = head;
        if (h != null && h != tail) {
            int ws = h.waitStatus;
            if (ws == Node.SIGNAL) {
                if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                    continue;            // loop to recheck cases
                unparkSuccessor(h);
            }
            // 如果ws为0则设为广播状态，方便setHeadAndPropagate可以广播
            // 有可能该节点是最后一个节点，没有后继节点，这样它的状态初始化为0，但是它也需要在广播中被通知到
            else if (ws == 0 &&
                     !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                continue;                // loop on failed CAS
        }
        if (h == head)                   // loop if head changed
            break;
    }
}
```

### 条件锁

条件锁的实现主要是AQS当中的内部类ConditionObject实现，其实现接口Condition

```java
public interface Condition {

    void await() throws InterruptedException;

    void awaitUninterruptibly();

    boolean await(long time, TimeUnit unit) throws InterruptedException;

    boolean awaitUntil(Date deadline) throws InterruptedException;

    void signal();

    void signalAll();
}

```



这里我们可以看到有两种基本操作await和signal。



需要说明的是ConditionObject维护着一张单链表，链表头是firstWaiter，然后通过Node中的nextWaiter字段实现链表关系维护；

await就是往该单链表中加节点，signal就是讲该节点加入到CLH的同步队列中去。

```
private transient Node firstWaiter;
```

#### await



```java
public final void await() throws InterruptedException {
    if (Thread.interrupted())
        throw new InterruptedException();
    // 生成一个node，添加到单链表
    Node node = addConditionWaiter();
    int savedState = fullyRelease(node);
    int interruptMode = 0;
    // 如果该节点不在CLH同步队列中则park
    while (!isOnSyncQueue(node)) {
        LockSupport.park(this);
        if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
            break;
    }
    // 下面的流程是signal流程unlock之后才会走到这里来
    // accquireQueue是互斥锁的流程：不断自旋获取到锁就出队，获取不到就park等待再次唤醒
    if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
        interruptMode = REINTERRUPT;
    if (node.nextWaiter != null) // clean up if cancelled
        unlinkCancelledWaiters();
    if (interruptMode != 0)
        reportInterruptAfterWait(interruptMode);
}
```



#### addConditionWaiter

```java
private Node addConditionWaiter() {
    Node t = lastWaiter;
    // If lastWaiter is cancelled, clean out.
    if (t != null && t.waitStatus != Node.CONDITION) {
        unlinkCancelledWaiters();
        t = lastWaiter;
    }
    Node node = new Node(Thread.currentThread(), Node.CONDITION);
    if (t == null)
        firstWaiter = node;
    else
        t.nextWaiter = node;
    lastWaiter = node;
    return node;
}
```

#### signal

```java
public final void signal() {
    // 首先判断是否获取到锁，比如可重入锁的lock有没有成功
    if (!isHeldExclusively())
        throw new IllegalMonitorStateException();
    Node first = firstWaiter;
    if (first != null)
        // doSignal的操作是将节点加入到同步队列中
        doSignal(first);
}
```





### 公平与非公平



## CAS



### 概述

cas是一种乐观锁，jdk内部有很多使用此机制实现同步的类和工具，Unsafe类是cas的主体实现类。基于unsafe类实现锁的场景有很多，比如java.util.concurrent.atomic.AtomicInteger/java.util.concurrent.atomic.AtomicReference等工具类。



### ABA问题及解决方案

加版本号的方法。



## FutureTask



todo

futureTask是一种异步等待线程执行结果的机制。






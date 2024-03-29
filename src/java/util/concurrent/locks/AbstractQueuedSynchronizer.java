/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 *
 *
 * otz:
 *  可中断和不可中断：
 *      参考 {@link #acquireQueued} 和 {@link #doAcquireInterruptibly} 两个方法，其实唯一区别就是
 *      前者阻塞唤醒后没有抛出异常，而是继续进入 for 循环获取锁，如果获取失败还是会被阻塞的，虽然说是不可中断，其实还是终端被唤醒了，只不过又进入了循环获取锁而已
 *      后者阻塞唤醒，如果是因为调用 .interrupt 方法，那么直接抛出异常，所以当前线程就被释放出来了
 *
 *  共享和独占模式获取锁最大区别：
 *      重新设置CLH队列头的方法不一样。{@link #doAcquireShared} 方法调用 {@link #setHeadAndPropagate} 方法，
 *      而 {@link #acquireQueued} 方法调用 {@link #setHead} 方法
 *  独占锁：
 *      1 独占锁是只有头节点获取锁，其余节点的线程继续等待，等待锁被释放后，才会唤醒下一个节点的线程；
 *      2 独占锁的同步状态state值在0和1之间切换，保证同一时间只能有一个线程是处于活动的，其他线程都被阻塞，参考ReentranLock。
 *      3 独占锁是一种悲观锁
 *  共享锁：
 *      1 共享锁是只要头节点获取锁成功，就在唤醒自身节点对应的线程的同时，继续唤醒AQS队列中的下一个节点的线程，
 *        每个节点在唤醒自身的同时还会唤醒下一个节点对应的线程，以实现共享状态的“向后传播”，从而实现共享功能。
 *      2 共享锁的同步状态state值在整数区间内（自定义实现），如果state值<0则阻塞，否则不阻塞。
 *        参考ReadWriteLock、Semphore、CountDownLautch等。
 *      3 共享锁是一种乐观锁，允许多个线程同时访问共享资源
 *
 *
 *
 *
 *
 *
 */
@SuppressWarnings("all")
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */
    static final class Node {
        /** 共享模式的标记 */
        static final Node SHARED = new Node();
        /** 独占模式的标记 */
        static final Node EXCLUSIVE = null;

        /* 状态大于0，只有一种情况，那就是被取消了 */

        /** 该状态表明当期线程已经被取消 */
        static final int CANCELLED =  1;
        /** 该状态表明需要唤醒下一个线程 (用于独占锁) */
        static final int SIGNAL    = -1;
        /** 该状态表明线程正因为等待一个 condition 条件阻塞 (用于Condition的await等待) */
        static final int CONDITION = -2;
        /** 标志着下一个acquireShared方法线程应该被允许 (用于共享锁) */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block.
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.)
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *   0:          None of the above
         *
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         *
         * 标记着当前节点的状态，默认状态是0, 小于0的状态都是有特殊作用，大于0的状态表示已取消
         */
        volatile int waitStatus;

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */
        volatile Node prev;

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */
        volatile Node next;

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */
        volatile Thread thread;

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         *
         * 可能有两种作用：
         *  1. 表示下一个在Condition条件上等待的节点
         *  2. 表示是共享模式或者独占模式，注意第一种情况节点一定是共享模式
         *
         *  如果是 SHARED，表示当前节点是共享模式，如果是null，当前节点是独占模式，
         *  如果是其他值，当前节点也是独占模式，不过这个值也是Condition队列的下一个节点
         */
        Node nextWaiter;

        /**
         * Note：通过 Node 我们可以实现两个队列，
         *  一是通过 prev 和 next 实现队列(线程同步队列,双向队列)，
         *  二是 nextWaiter 实现 Condition 条件上的等待线程队列(单向队列)，后一个我们在Condition中介绍
         */


        /**
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        /**
         * 用于创建链表头head，或者共享模式SHARED
         */
        Node() {    // Used to establish initial head or SHARED marker
        }

        /**
         * 使用在addWaiter方法中
         */
        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        /**
         * 使用在Condition条件中
         */
        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     *
     * 等待队列头指针，懒加载初始化，也就是第一次是 null
     * head 值改变只能通过方法 {@link #setHead(Node)},并且规定如果头节点存在，那么它的 waitStatus 值不能为 CANCELLED
     *
     * 如果有等待队列，那么一定有一个空的头结点，它的相关属性为 null
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     *
     * 等待队列尾指针，懒加载初始化，也就是第一次是 null
     * tail 值改变只能通过方法 {@link #enq(Node)}，也就是添加 node 到等待队列的时候
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual value was not equal to the expected value.
     *
     * 修改同步组件 state 属性值
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor   返回的节点是添加节点的 前驱节点
     *
     * 死循环通过 cas 将参数 node 添加到阻塞线程队列尾部
     *
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            if (t == null) {
                /** 第一次添加的时候，头尾都是没有节点，所以为空，需要初始化 */
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

    /**
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared 当前线程阻塞的模式，只有互斥或者共享两种
     * @return the new node
     *
     * 通过给定的模式mode(独占或者共享)为当前线程创建新节点，并插入队列中
     */
    private Node addWaiter(Node mode) {
        /** 将当前线程和指定的模式封装成一个 node 对象 */
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        /** 这里采用快速尝试方式，直接将这个节点添加到队列尾部 */
        Node pred = tail;
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        /** 添加失败原因：1 tail 尾部为空，说明是第一次添加节点 node； 2 cas 操作失败，说明有线程此时在并发操作 */
        enq(node);
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * 将指定的参数 node 设置为头节点
     * 同时将头结点的线程和钱指针设置为 null
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     *
     * 唤醒node节点的下一个非取消状态的节点所在线程(即waitStatus<=0)
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus;
        /** 如果小于0，就将状态重新设置为0，表示这个node节点已经完成了 */
        if (ws < 0) {
            compareAndSetWaitStatus(node, ws, 0);
        }

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         */
        Node s = node.next;
        /** 如果下一个节点为null，或者状态是已取消，那么就要寻找下一个非取消状态的节点 */
        if (s == null || s.waitStatus > 0) {
            /** 先将s设置为null，s不是非取消状态的节点 */
            s = null;
            /** 从tail向后遍历以找到实际的非取消后继节点，一直找到 node 的下一个节点 */
            for (Node t = tail; t != null && t != node; t = t.prev)
                /** 因为是从后向前遍历，所以不断覆盖找到的值，这样才能得到node节点后下一个非取消状态的节点 */
                if (t.waitStatus <= 0)
                    s = t;
        }
        /** 如果s不为null，表示存在非取消状态的节点。那么调用LockSupport.unpark方法，唤醒这个节点的线程 */
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * Release action for shared mode -- signals successor and ensures propagation.
     * (Note: For exclusive mode, release just amounts to calling unparkSuccessor of head if it needs signal.)
     *
     * 会唤醒等待共享锁的线程
     */
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

        /**
         * 1 进入第一个if语句，表示后继节点需要被唤醒，采用CAS循环操作直到成功
         * 2 进入else if语句，表示暂时不需要唤醒，将状态传递
         * 3 最后判断头节点是否变化，没有变化则退出循环；如果头结点发生变化，
         *   比如说其他线程获取到了锁，为了使自己的唤醒动作可以传递，必须进行重试
         */

        for (;;) {
            /** 将同步队列头赋值给节点h */
            Node h = head;
            /** 头节点不为空，并且不等于尾节点，说明有等待节点 */
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                /** 如果状态是Node.SIGNAL，就要唤醒节点h后继节点的线程 */
                if (ws == Node.SIGNAL) {
                    /** 将节点h的状态设置成0，如果设置失败，就继续循环，再试一次 */
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
                        continue;            // loop to recheck cases
                    }
                    /** 唤醒节点h后继节点的线程 */
                    unparkSuccessor(h);
                }
                /** 如果节点h的状态是0，就设置ws的状态是PROPAGATE */
                else if (ws == 0 && !compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) {
                    continue;                // loop on failed CAS
                }
            }
            /**
             * 如果同步队列头head节点发生改变，继续循环
             * 如果没有改变，就跳出循环
             */
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * Sets head of que0.ue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * 设置队列的头部，并检查后续队列是否在共享模式下等待，如果是，则在传播> 0或
     * 设置了PROPAGATE状态
     *
     * @param node 当献线程所封装的节点
     * @param propagate 当前线程已经获取到资源的返回值，分别是 大于|小于|等于 0
     *
     * 重新设置CLH队列头，如果CLH队列头的下一个节点为null或者共享模式，那么就要唤醒共享锁上等待的线程
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        /** 设置新的同步队列头head */
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before..0
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
        /**
         * 1 根据 propagate 的值判断，propagate >0时，表示可以唤醒后继节点，执行doReleaseShared()方法；
         *   而当 propagate = 0 时，不会直接执行doReleaseShared()方法，而是进入第二种情况继续判断
         * 2 头节点后面的节点需要被唤醒（waitStatus<0），不论是老的头结点还是新的头结点
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 || (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            /** 如果节点s是空或者共享模式节点，那么就要唤醒共享锁上等待的线程 */
            if (s == null || s.isShared()) {
                doReleaseShared();
            }
        }
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     *
     * 将node节点的状态设置成CANCELLED，表示node节点所在线程已取消，不需要唤醒了
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        node.thread = null;

        /** 跳过那些已取消的节点，在队列中找到在node节点前面的第一次状态不是已取消的节点 */
        Node pred = node.prev;
        while (pred.waitStatus > 0) {
            node.prev = pred = pred.prev;
        }

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        /** 记录pred原来的下一个节点，用于CAS函数更新时使用 */
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        /** 将node节点状态设置为已取消Node.CANCELLED */
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        /** 如果node节点是队列尾节点，那么就将pred节点设置为新的队列尾节点 */
        if (node == tail && compareAndSetTail(node, pred)) {
            /** 并且设置pred节点的下一个节点next为null */
            compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        }
    }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     *
     * 参数 pred 是参数 node 的前置节点
     * 这个方法其实就是判断前置节点 pred 是否是 SIGNAL，如果是则 ture，否则返回 false
     * true：表明当前线程应该被阻塞
     * false：表明当前线程不应该被阻塞
     *
     *
     * 这个方法就是用来根据前一个 node 来判断当前线程是否需要被阻塞；
     * 并且前一个 node 状态的修改也是通过这个方法来执行的
     *
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        /** 如果已经是 SIGNAL，则直接返回 true，表示当前线程应该被阻塞*/
        if (ws == Node.SIGNAL) {
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;
        }

        if (ws > 0) {
            /**
             * 如果前一个节点状态是Node.CANCELLED(大于0就是CANCELLED)，
             * 表示前一个节点所在线程已经被唤醒了，要从CLH队列中移除CANCELLED的节点。
             * 所以从pred节点一直向前查找直到找到不是CANCELLED状态的节点，并把它赋值给node.prev，
             * 表示node节点的前一个节点已经改变
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
            /**
             * 此时前一个节点pred的状态只能是0或者PROPAGATE，不可能是CONDITION状态
             * CONDITION(这个是特殊状态，只在condition列表中节点中存在，CLH队列中不存在这个状态的节点)
             * 将前一个节点pred的状态设置成Node.SIGNAL，这样在下一次循环时，就是直接阻塞当前线程
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     *
     * 发出当前线程终端信号
     * 也就是下一次进入阻塞的时候，会立马被唤醒
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {

        /**
         * 1 执行这段代码，线程就会挂起，不会执行下面的 return 语句
         * 2 被唤醒的方式：
         *      1 LockSupport.unpark 唤醒当前线程
         *      2 当期线程被调用了 .interrupt() 方法，需要注意的是，这种方式是不会抛出异常，而是正常执行下面流程
         */
        LockSupport.park(this);

        /**
         * 执行这段代码，说明当前线程已经被唤醒
         * 返回被唤醒的方式
         *  true：说明是终端方式唤醒；
         *  false：说明是 unpark 方式唤醒
         */
        return Thread.interrupted();
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     *
     * 1 首先参数 node 已经是在等待队列中了
     * 2 参数 arg 表示当前参数 node 这个线程需要申请的状态个数
     * 3 这个方法就是线程真正阻塞的方法，除非获取到了锁，否则阻塞自己
     * 4 true：表示当前线程阻塞过，并且唤醒的方式是调用线程 .interrupt() 方法
     *   其余方式都是 false：包括线程没有阻塞，或者线程阻塞过但是通过 unpark 方式唤醒
     *
     *
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                /**
                 * 如果参数 node 的前一个节点是 head，并且尝试一次获取锁资源，
                 * 如果都成功说明当前运行的线程已经获取到了锁，不需要阻塞自己了，所以将自己设置成头节点，并且设置相关属性为 null
                 */
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                /**
                 * 1 判断前一个节点状态是否是 SIGNAL，如果是返回 ture，其余情况都返回 false
                 * 2 第一个方法返回 true，说明当前线程应该被 park 挂起，因为前一个 node 状态是 SIGNAL，会主动唤醒这个线程的
                 */
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    interrupted = true;
                }
            }
        } finally {
            if (failed) {
                cancelAcquire(node);
            }
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     *
     * 以独占中断模式获取锁资源
     * 和 {@link #acquireQueued} 方法唯一不同的是：被唤醒的方式应该是调用了 .interrupt 方法，因为这个方法会抛出异常
     */
    private void doAcquireInterruptibly(int arg) throws InterruptedException {
        /** 添加一个独占模式节点到等待队列中 */
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                /** 和 {@link #acquireQueued} 相比，这里也可以抛出异常直接结束这个 for 循环 */
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     *
     * 指定一个等待时间，以独占模式获取锁资源
     * 如果获取成功则返回 true
     *
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L) {
            return false;
        }
        /** 计算等待到期时间 */
        final long deadline = System.nanoTime() + nanosTimeout;
        /** 将当前线程以独占模式添加到等待队列中 */
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                /** 如果是头节点，说明当前只有这个线程在等待队列中，结构为: Node(head） -> (ConcurrentTherad)Node */
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }

                /** 这里重新计算一次到期时间，因为上面的 if 浪费了一些的时间 */
                nanosTimeout = deadline - System.nanoTime();

                /** 如果时间到了直接返回 false，说明没有获取到锁 */
                if (nanosTimeout <= 0L) {
                    return false;
                }

                /**
                 * 判断1：寻找头结点，为 SIGNAL 的节点
                 * 判断2：是所以这里有这么一个判断，因为当前时间单位是 纳秒，如果过期时间都小于 1毫秒了，那么没有必要阻塞沉睡了
                 *        因为这些沉睡操作花的时间可能比这个 1毫秒还多，所以直接等到下一次循环因为到期时间到了返回 false 即可
                 */
                if (shouldParkAfterFailedAcquire(p, node) && nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }

                /** 如果线程被提前中断了，那么直接抛出中断异常 */
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     *
     * 获取共享锁，获取失败，则会阻塞当前线程，直到获取共享锁返回，不可中断
     *
     */
    private void doAcquireShared(int arg) {
        /** 以共享模式添加等待队列中 */
        final Node node = addWaiter(Node.SHARED);

        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                /** 如果节点node前一个节点是同步队列头节点。就会调用tryAcquireShared方法尝试获取共享锁 */
                if (p == head) {
                    /** 调用具体的子实现类方法来获取 */
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        /**
                         * 设置头节点
                         * 唤醒后继 node 线程
                         */
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted) {
                            selfInterrupt();
                        }
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
                    /** 没有抛出异常，所以是不可中断的阻塞 */
                    interrupted = true;
                }
            }
        } finally {
            /**
             * failed为true，表示发生异常
             * 则将node节点的状态设置成CANCELLED，表示node节点所在线程已取消，不需要唤醒了
             */
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     *
     * 以共享可中断模式获取
     */
    private void doAcquireSharedInterruptibly(int arg) throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    /** 调用具体的子实现类方法来获取 */
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        /**
                         * 设置头节点
                         * 唤醒后继 node 线程
                         */
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()){
                    /** 抛出异常，所以是可中断的阻塞 */
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L) {
            return false;
        }
        /** 计算到期时间 */
        final long deadline = System.nanoTime() + nanosTimeout;
        /** 添加共享模式的 node 到等待队列中 */
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                /** 重新计算过期时间，因为上面 if 浪费了一些时间 */
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) {
                    return false;
                }
                /**
                 * 判断1：寻找上一个结点，为 SIGNAL 的节点
                 * 判断2：是所以这里有这么一个判断，因为当前时间单位是 纳秒，如果过期时间都小于 1毫秒了，那么没有必要阻塞沉睡了
                 *        因为这些沉睡操作花的时间可能比这个 1毫秒还多，所以直接等到下一次循环因为到期时间到了返回 false 即可
                 */
                if (shouldParkAfterFailedAcquire(p, node) && nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }

                /** 可中断等待 */
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     *
     * 尝试去获取独占锁，立即返回。如果返回true表示获取锁成功
     *
     * 如果子类想实现独占锁，则必须重写这个方法，否则抛出异常。这个方法的作用是当前线程尝试获取锁，
     * 如果获取到锁，就会返回true，并更改锁资源。没有获取到锁返回false
     * Note：这个方法是立即返回的，不会阻塞当前线程
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     *
     * 尝试去释放当前线程持有的独占锁，立即返回。如果返回true表示释放锁成功
     *
     * 如果子类想实现独占锁，则必须重写这个方法，否则抛出异常。作用是释放当前线程持有的锁，
     * 返回true表示已经完全释放锁资源，返回false，表示还持有锁资源
     * 之所以这么说是因为 lock 是可重入锁，释放也必须是多次
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     *
     * 尝试去获取共享锁，立即返回。返回值大于等于0，表示获取共享锁成功
     *
     * 如果子类想实现共享锁，则必须重写这个方法，否则抛出异常。作用是尝试获取共享锁，返回值大于等于0，表示获取共享锁成功
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     *
     * 如果子类想实现共享锁，则必须重写这个方法，否则抛出异常。
     * 作用是释放当前线程持有的锁，返回true表示已经完全释放锁资源，返回false，表示还持有锁资源
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     *
     * 判断当前线程是否持有锁  看 {@link ReentrantLock.FairSync#isHeldExclusively()} 实现就知道
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     *
     * 获取独占锁。如果没有获取到，线程就会阻塞等待，直到获取锁。不会响应中断异常
     */
    public final void acquire(int arg) {
        /**
         * 1 先调用具体的子类 tryAcquire 方法来获取锁资源，如果获取成功，则没有后续操作了
         * 2 如果获取失败，则调用 addWaiter 方法将当前线程以互斥模式添加到等待队列中
         * 3 然后调用acquireQueued方法去获取锁，如果不成功，就会让当前线程阻塞，当锁释放时才会被唤醒。
         *   acquireQueued方法返回值表示在线程等待过程中，是否有另一个线程调用该线程的interrupt方法，发起中断
         */
        if (!tryAcquire(arg) && acquireQueued(addWaiter(Node.EXCLUSIVE), arg)) {
            /**
             * 进入这里说明线程阻塞过，并且被唤醒的方式是调用 .interrupt 方法
             */
            selfInterrupt();
        }
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     *
     * 可中断方式获取资源
     */
    public final void acquireInterruptibly(int arg) throws InterruptedException {
        /** 如果线程已经被终端信号标注，那么直接异常返回 */
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        /** 尝试获取资源，如果获取失败则进入可中断阻塞方法 */
        if (!tryAcquire(arg)) {
            doAcquireInterruptibly(arg);
        }
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        /** 如果线程已经被终端信号标注，那么直接异常返回 */
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        /**
         * 1 获取锁资源成功，则直接返回
         * 2 获取所失败，则进入 doAcquireNanos 方法
         */
        return tryAcquire(arg) || doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     *
     * 在独占锁模式下，释放锁的操作
     */
    public final boolean release(int arg) {
        /** 调用子实现类来释放锁资源 */
        if (tryRelease(arg)) {
            Node h = head;
            /**
             * 如果队列头节点的状态不是0，那么队列中就可能存在需要唤醒的等待节点。
             * 还记得在 {@link #acquireQueued} 获取锁的方法中，如果节点node没有获取到锁，
             * 那么我们会将节点node的前一个节点状态设置为Node.SIGNAL，然后调用parkAndCheckInterrupt方法将节点node所在线程阻塞。
             * 在这里就是通过unparkSuccessor方法，进而调用LockSupport.unpark(s.thread)方法，唤醒被阻塞的线程
             */
            if (h != null && h.waitStatus != 0) {
                unparkSuccessor(h);
            }
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     *
     * 获取共享锁
     */
    public final void acquireShared(int arg) {
        /** 尝试去获取共享锁，如果返回值小于0表示获取共享锁失败，大于等于0则表示获取锁成功 */
        if (tryAcquireShared(arg) < 0) {
            /** 小于0 表示获取失败 */
            doAcquireShared(arg);
        }
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg) throws InterruptedException {
        /** 判中断端信号 */
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        /** 调用子类获取锁资源，如果小于0 则失败，进入 阻塞方法 */
        if (tryAcquireShared(arg) < 0) {
            doAcquireSharedInterruptibly(arg);
        }
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
        /** 判中断端信号 */
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        /**
         * 调用子类获取锁资源
         * 如果失败则进入 doAcquireSharedNanos 方法
         */
        return tryAcquireShared(arg) >= 0 || doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     *
     * 释放共享锁
     */
    public final boolean releaseShared(int arg) {
        /** 调用子类释放共享资源 */
        if (tryReleaseShared(arg)) {
            /** 唤醒等待共享锁的线程 */
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     *
     * 查询是否有等待 node 线程
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     *
     * 查询是否有线程获取锁而被阻塞过，因为初始化 head 为 null，只要有阻塞那么 head 就不会为空
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or {@code null} if no threads are currently queued
     *
     *
     *
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        /** 从头开始获取下一个节点的 node 所对应的线程 */
        if (((h = head) != null && (s = h.next) != null && s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null && s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        /** 从尾部开始往上查找 head 后面的第一个 node 所对应的线程 */
        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     *
     * 查找阻塞队列的线程中是否有包含参数 thread 这个线程
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread == thread) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     *
     * 这个方法在 {@link ReentrantReadWriteLock} 中被调用
     * 如果等待队列中第一个阻塞的node 线程是排他模式，则返回 true，其余情况返回 false
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
                (s = h.next)  != null &&
                !s.isShared()         &&
                s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        /**
         * 如果当前线程之前有一个排队的线程，则为true;如果当前线程位于队列的头部或队列为空，则为false
         */
        return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     *
     * 获取整个阻塞队列的长度
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null) {
                ++n;
            }
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     *
     * 获取整个阻塞队列的线程
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     *
     * 获取整个阻塞队列的排他模式线程
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     *
     * 获取整个阻塞队列的共享模式线程
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        /**
         * 这里查找的是等待队列，而不是查找 条件等待 队列，
         * 如果 waitStatus = CONDITION，那么这个 node 肯定不在等待队列中，而是在条件等待队列中
         */
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;

        /** 如果参数 node 有后继节点，那么肯定是在 阻塞队列中 */
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        return findNodeFromTail(node);
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     *
     * 从尾部开始查找参数 node 是否在阻塞队列中，该方法仅在 {@link #isOnSyncQueue}
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was cancelled before signal)
     *
     * 将节点从条件队列转移到同步队列
     * 返回true表示节点node插入到同步队列中，返回false表示节点node没有插入到同步队列中
     */
    final boolean transferForSignal(Node node) {
        /**
         * 如果节点node的状态不是Node.CONDITION，或者更新状态失败，
         * 说明该node节点已经插入到同步队列中，所以直接返回false
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            return false;
        }

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        /**  将节点node插入到同步队列中，p是原先同步队列尾节点，也是node节点的前一个节点 */
        Node p = enq(node);
        int ws = p.waitStatus;
        /**
         * 如果前一个节点是已取消状态，或者不能将它设置成Node.SIGNAL状态。
         * 就说明节点p之后也不会发起唤醒下一个node节点线程的操作，
         * 所以这里直接调用 LockSupport.unpark(node.thread)方法，唤醒节点node所在线程
         */
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL)) {
            LockSupport.unpark(node.thread);
        }
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        /** 将 node 节点的状态从 CONDITION 改为 0 */
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            /** 成功则添加到 等待同步队列中 */
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     *
     * 释放当前线程占有的锁，并唤醒等待同步队列中的一个线程
     * 参数就是当前线程封装的 node 节点，为了防止释放唤醒失败，如果发生则取消当前节点
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            /** 获取当前同步状态值，因为条件队列使用必须是互斥模型，如果信号其实就是 0 和 1 */
            int savedState = getState();
            /** 这里的释放其实就是类似 unlock 方法，都是调用 release 方法释放锁和唤醒线程 */
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            /**
             * 如果释放失败就表示当前节点出现异常，需要取消
             */
            if (failed) {
                node.waitStatus = Node.CANCELLED;
            }
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     *
     * Condition 是为了实现线程之间相互等待的问题。
     * 注意 Condition 对象只能在独占锁中才能使用
     *
     *  1 首先内部存在一个Condition队列，存储着所有在此Condition条件等待的线程。
     *  2 await系列方法：让当前持有锁的线程释放锁，并唤醒一个在CLH队列上等待锁的线程，再为当前线程创建一个node节点，
     *    插入到Condition队列(注意不是插入到CLH队列中)
     *  3 signal系列方法：其实这里没有唤醒任何线程，而是将Condition队列上的等待节点插入到CLH队列中，
     *    所以当持有锁的线程执行完毕释放锁时，就会唤醒CLH队列中的一个线程，这个时候才会唤醒线程。
     *
     * Note：
     *  条件队列必须是独占模式
     *
     *
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. 首个等待节点 */
        private transient Node firstWaiter;
        /** Last node of condition queue.  最后一个等待节点 */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         * @return its new wait node
         *
         * 将当前线程封装成 node 添加到条件队列尾部
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            /** 清除cancell态的节点 */
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                /** t指向最后一个状态正确的节点 */
                t = lastWaiter;
            }
            /** 新建一个条件等待 node，添加到条件队列中 */
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null) {
                firstWaiter = node;
            }
            else {
                t.nextWaiter = node;
            }
            lastWaiter = node;
            return node;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         *
         * 该方法只会在 {@link #ConditionObject()#signal} 中调用，并且参数就是 {@link #ConditionObject()#firstWaiter}
         * 就是将参数节点 node 移动到同步队列中，并且在条件队列中删除
         */
        private void doSignal(Node first) {

            /**
             * 为什么使用while循环，因为只有是Node.CONDITION状态的节点才能插入CLH队列，
             * 如果不是这个状态，那么循环Condition队列下一个节点
             */

            do {
                /**
                 * 原先的Condition队列头节点取消，所以重新赋值Condition队列头节点
                 * 如果新的Condition队列头节点为null，表示Condition队列为空了，
                 * 所以也要设置Condition队列尾lastWaiter为null
                 */
                if ((firstWaiter = first.nextWaiter) == null) {
                    lastWaiter = null;
                }
                /** 取消first节点nextWaiter引用 */
                first.nextWaiter = null;
            }
            /**
             * 1 从条件队列将节点转移到等待同步队列
             * 2 如果转移失败，则默认开始转移条件队列中的下一个节点
             */
            while (!transferForSignal(first) && (first = firstWaiter) != null);
        }

        /**
         * Removes and transfers all nodes.
         *
         * 将条件队列中所有节点移动到同步等待队列中，并且删除所有条件队列
         *
         */
        private void doSignalAll(Node first) {
            /** 表示将condition队列设置为空 */
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
                /** 循环遍历condition队列中所有的节点 */
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         *
         * 清除 Condition 队列中状态不是 Node.CONDITION 的节点
         */
        private void unlinkCancelledWaiters() {
            /** condition 队列头赋值给 t */
            Node t = firstWaiter;
            /** 这个trail节点，只是起辅助作用 */
            Node trail = null;
            while (t != null) {
                /** 得到下一个节点 next。当节点是 condition 时候，nextWaiter 表示 condition 队列的下一个节点 */
                Node next = t.nextWaiter;
                /** 如果节点t的状态不是 CONDITION，那么该节点就要从 condition 队列中移除 */
                if (t.waitStatus != Node.CONDITION) {
                    /** 将节点 t 的 nextWaiter 设置为 null */
                    t.nextWaiter = null;
                    /**  如果trail为null，表示原先的condition队列头节点实效，需要设置新的condition队列头 */
                    if (trail == null) {
                        firstWaiter = next;
                    }
                    else{
                        /** 将节点 t 从 condition 队列中移除，因为改变了引用的指向，从 condition 队列中已经找不到节点t了 */
                        trail.nextWaiter = next;
                    }
                    /** 如果next为null，表示原先的condition队列尾节点也实效，重新设置队列尾节点 */
                    if (next == null)
                        lastWaiter = trail;
                }
                /** 遍历到的有效节点 */
                else {
                    trail = t;
                }
                /**  将next赋值给t，遍历完整个condition队列 */
                t = next;
            }
        }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively} returns {@code false}
         *
         * 如果 condition 队列不为空，将 condition 队列头节点插入到同步队列中
         *
         * 调用这个这个方法的线程已经获取到锁，调用这个方法仅仅是将条件队列第一个节点加入到同步队列中，
         * 这个方法并没有释放锁或者唤醒线程功能，调用完这个方法后都是在 finally 里面调用 .unlock 方法
         */
        public final void signal() {
            /** 如果当前线程不是独占锁线程，就抛出IllegalMonitorStateException异常 */
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            Node first = firstWaiter;
            if (first != null) {
                /** 将 Condition 队列中的 first 节点插入到同步等待队列中 */
                doSignal(first);
            }
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively} returns {@code false}
         *
         * 将 condition 队列中所有的节点都插入到同步队列中
         *
         * 调用这个这个方法的线程已经获取到锁，调用这个方法仅仅是将条件队列第一个节点加入到同步队列中，
         * 这个方法并没有释放锁或者唤醒线程功能，调用完这个方法后都是在 finally 里面调用 .unlock 方法
         */
        public final void signalAll() {
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            Node first = firstWaiter;
            if (first != null) {
                doSignalAll(first);
            }
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /** Mode meaning to reinterrupt on exit from wait */
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        private static final int THROW_IE    = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }


        /**
         * 如果interruptMode是THROW_IE，就抛出InterruptedException异常
         * 如果interruptMode是REINTERRUPT，则当前线程再发出中断请求
         * 否则就什么都不做
         */
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            /** 将当前线程封装成 Node 加入到条件队列中 */
            Node node = addConditionWaiter();

            /**
             * 释放当前线程占有的锁，并唤醒 CLH 队列一个等待线程
             * 这个逻辑很明显需要当前线程去唤醒，然后才阻塞，不然先阻塞如何唤醒？
             * 只要保证唤醒前释放锁就可以
             */
            int savedState = fullyRelease(node);

            /* 流程到了这里，可以理解现在已经唤醒了别的线程 */

            int interruptMode = 0;
            /**
             * 1 判断当前节点是否已经在同步等待队列中，如果是则退出循环，如果不是就阻塞当前线程
             * 2 其他线程如果发出了signal信号之后，会把等待队列的线程移入同步队列，此时就会退出循环，进入下面的重新获取锁的 acquireQueued
             */
            while (!isOnSyncQueue(node)) {
                /**
                 * 阻塞当前线程,那么怎么唤醒这个线程呢？
                 * 首先我们必须调用signal或者signalAll将这个节点node加入到同步队列。
                 * 只有这样unparkSuccessor(Node node)方法，才有可能唤醒被阻塞的线程
                 */
                LockSupport.park(this);
                /** 如果当前线程产生中断请求，就跳出循环 */
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
            }
            /**
             * 如果节点 node 已经在同步队列中了，获取同步锁，只有得到锁才能继续执行，否则线程继续阻塞等待
             */
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            /** 清除 Condition 队列中状态不是 Node.CONDITION 的节点 */
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters();
            }
            /** 是否要抛出异常，或者发出中断请求 */
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout) throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline) throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit) throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node, int expect, int update) {
        /** 修改指定 node 的 waitStatus 属性字段 */
        return unsafe.compareAndSwapInt(node, waitStatusOffset, expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node, Node expect, Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 阻塞同步队列核心就是一个 lock 和两个 condition
 * 不管是生产环节还是消费环节，都必须获取锁
 * 具体流程看下面注释
 *
 * Note:
 *  获取锁有两处：
 *      1 lock 方法
 *      2 线程在 await 阻塞后被唤醒，隐含的也会获取锁
 *  释放锁：
 *      1 unlock 方法
 *      2 await 方法也会释放锁
 *
 */
@SuppressWarnings("all")
public class ArrayBlockingQueueS<E> {

    private final List<E> list;
    private final int limit;
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();//非空
    private final Condition notFull = lock.newCondition();//没满

    public ArrayBlockingQueueS(int limit){
        this.limit = limit;
        this.list = new ArrayList<E>();
    }

    /**
     * 假设线程A 操作该方法
     *
     */
    public void put(E e){
        /* 5 线程A开始生产资源 */
        try {
            /* 6 获取锁，如果线程B 没有阻塞，这里是获取不到锁 */
            lock.lock();
            /* 7 发现资源队列里面资源没有满，则跳过 while 循环 */
            while(list.size() >= limit){
                notFull.await();
            }
            /* 8 加入资源，这里很安全，因为这里获取了锁，不可能会发生并发问题 */
            list.add(e);
            /*
             * 9 notEmpty 不管有没有阻塞 node，这里都进行唤醒一次，源码也是这么操作的
             *   如果有阻塞 node，那么就会将 notEmpty 中的一个 node 转移到 同步阻塞队列中，
             *   如果没有阻塞的 node，那么什么都不做
             *   这里并不会释放锁!!
             */
            notEmpty.signal();
        } catch (Exception e2) {
        }finally{
            /* 10 这里才是真正的释放锁，然后唤醒同步队列中的第一个 node */
            lock.unlock();
        }
    }

    /**
     * 假设线程B 操作该方法
     */
    public E take(){
        /* 1 假设B线程首先获取资源，进入该方法 */
        try {
            /* 2 成功获取锁 */
            lock.lock();
            /* 3 发现资源队列为空，进入 while 循环*/
            while(list.size() == 0){
                /*
                 *  4 这里有两个操作，一是释放锁，二是将自己线程 B 加入到 notEmpty 条件队列中
                 * 此时就会阻塞在这里，知道被唤醒
                 *
                 * 11 这个时候，线程B 封装的node 已经被转移到了 同步队列中，并且经过步骤 10 已经被唤醒
                 *    被唤醒后会重需要新判断 while 条件，因为消费者可能有多个，很有可能还有B1线程在消费，
                 *    导致资源队列又为空
                 */
                notEmpty.await();
            }
            /* 12 消费一个资源 */
            E e = list.remove(0);
            /* 13 这里跟生产者一样，看看有没有生产者需要被唤醒 */
            notFull.signal();
            return e;
        } catch (Exception e2) {
            return null;
        }finally{
            /* 14 释放锁 */
            lock.unlock();
        }
    }

}

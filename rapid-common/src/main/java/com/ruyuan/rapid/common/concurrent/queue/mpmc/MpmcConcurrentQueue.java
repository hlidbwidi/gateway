package com.ruyuan.rapid.common.concurrent.queue.mpmc;

/**
 * <B>主类名称：</B>MpmcConcurrentQueue<BR>
 * <B>概要说明：</B>multi producer multi consumer Concurrent Queue<BR>
 * @author JiFeng
 * @since 2021年12月7日 上午11:26:31
 */
public class MpmcConcurrentQueue<E> implements ConcurrentQueue<E> {

    protected final int size;    // 实际队列容量

    //	mask    16 - 1 = 15       10000  01111
    final long mask;     // 位掩码

    // buffer： 环形缓冲区
    //	a ring buffer representing the queue
    final Cell<E>[] buffer;

    //	head: 头部的计数器   队列头部指针：记录当前可消费的位置
    final ContendedAtomicLong head = new ContendedAtomicLong(0L);

    //	tail: 尾部的计数器   队列尾部指针：记录当前可生产的位置
    final ContendedAtomicLong tail = new ContendedAtomicLong(0L);

    /**
     * <B>构造方法</B>MPMCConcurrentQueue<BR>
     * @param capacity Construct a blocking queue of the given fixed capacity, capacity maximum capacity of this queue
     */
    @SuppressWarnings("unchecked")
	public MpmcConcurrentQueue(final int capacity) {
        // 容量计算与对齐  将容量调整为≥输入值的最小2的幂次方
        // capacity of at least 2 is assumed
        int c = 2;
        //	capacity = 15
        while(c < capacity) {
        	c <<=1;
        }
        // 成员变量初始化
        size = c;    // 实际容量
        mask = size - 1L;   // 位掩码
        // 缓冲区初始化
        buffer = new Cell[size];
        //	缓存的预加载
        for(int i = 0; i < size; i ++) {
            buffer[i] = new Cell<E>(i);
        }
    }

    // offer 方法： MPMC（多生产者多消费者）并发队列的核心入队操作，实现了线程安全的数据插入功能
    /**
     * <B>方法名称：</B>offer<BR>
     * <B>概要说明：</B>并发offer, 加入其尾部<BR>
     * @author  JiFeng
     * @since 2021年12月7日 上午11:27:27
     * @see com.ruyuan.rapid.common.concurrent.queue.mpmc.ConcurrentQueue#offer(java.lang.Object)
     */
    @Override
    public boolean offer(E e) {
        Cell<E> cell;
        long tail = this.tail.get();
        for(;;) {
        	//	取得当前的cell
            cell = buffer[(int)(tail & mask)];
            //	获取当前cell的seq
            final long seq = cell.seq.get();
            //	获取dif差值
            final long dif = seq - tail;
            //	正常情况下dif为0
            if(dif == 0) {
            	//	cas tail + 1
                if(this.tail.compareAndSet(tail, tail + 1)) {
                    break;
                }
            } else if(dif < 0) {
                return false;
            } 
            //	异常情况下（并发可能造成 seq > tail）, 那么就以this.tail的值为准, 后面更新cell的seq
            else {
                tail = this.tail.get();
            }
        }
        //	设置元素
        cell.entry = e;
        //	cell的seq自增
        cell.seq.set(tail + 1);
        return true;
    };

    /**
     * <B>方法名称：</B>poll<BR>
     * <B>概要说明：</B>并发poll, 从头部获取<BR>
     * @author  JiFeng
     * @since 2021年12月7日 上午11:27:37
     * @see com.ruyuan.rapid.common.concurrent.queue.mpmc.ConcurrentQueue#poll()
     */
    @Override
    public E poll() {
        Cell<E> cell;
        //	0
        long head = this.head.get();
        for(;;) {
            cell = buffer[(int)(head & mask)];
            long seq = cell.seq.get();
            final long dif = seq - (head + 1L);
            //	正常情况下 是走 dif = 0
            if(dif == 0) {
                if(this.head.compareAndSet(head, head + 1)) {
                    break;
                }
            } else if(dif < 0) {
                return null;
            } else {
            	//	在并发情况下 可能出现 seq > head + 1 那么就需要重新设置Cell
                head = this.head.get();
            }
        }

        try {
            return cell.entry;
        } finally {
        	//	disruptor: 一致的
            cell.entry = null;
            //	比如：0 + 15 + 1  下一次获取该元素(Cell)时, 该元素的seq也就是下标应该是绕过该环形队列(整个buffer的长度)
            cell.seq.set(head + mask + 1L);
        }
    }

    @Override
    public final E peek() {
        return buffer[(int)(head.get() & mask)].entry;
    }


    @Override
    // drain the whole queue at once
    public int remove(final E[] e) {
        int nRead = 0;
        while(nRead < e.length && !isEmpty()) {
            final E entry = poll();
            if(entry != null) {
                e[nRead++] = entry;
            }
        }
        return nRead;
    }

    @Override
    public final int size() {
        return (int)Math.max((tail.get() - head.get()), 0);
    }

    @Override
    public int capacity() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return head.get() == tail.get();
    }

    @Override
    public void clear() {
        while(!isEmpty()) poll();
    }

    @Override
    public final boolean contains(Object o) {
        for(int i=0; i<size(); i++) {
            final int slot = (int)((head.get() + i) & mask);
            if(buffer[slot].entry != null && buffer[slot].entry.equals(o)) return true;
        }
        return false;

    }

    /**
     * <B>主类名称：</B>Cell<BR>
     * <B>概要说明：</B>消除伪共享对象<BR>
     * @author JiFeng
     * @since 2021年12月7日 上午11:27:49
     */
    protected static final class Cell<R> {
    	
    	//	序列号计数器   采用ContendedAtomicLong避免伪共享
        final ContendedAtomicLong seq = new ContendedAtomicLong(0L);

//        public long p1, p2, p3, p4, p5, p6, p7;
        //	数据存储域：存储实际业务数据
        R entry;

//        public long a1, a2, a3, a4, a5, a6, a7, a8;

        Cell(final long s) {
            seq.set(s);  // 初始化序列号
            entry = null; // 清空数据引用
        }

//        public long sumToAvoidOptimization() {
//            return p1+p2+p3+p4+p5+p6+p7+a1+a2+a3+a4+a5+a6+a7+a8;
//        }

    }

}
package com.ruyuan.rapid.common.concurrent.queue.mpmc;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * <B>主类名称：</B>ContendedAtomicLong<BR>
 * <B>概要说明：</B>Avoid false cache line sharing<BR>
 * @author JiFeng
 * @since 2021年12月7日 上午11:25:13
 */
public class ContendedAtomicLong extends Contended {

	//	一个缓存行需要多少个Long元素的填充：（64/8=8）
    private static final int CACHE_LINE_LONGS = CACHE_LINE / Long.BYTES;

    // 原子数组声明
    private final AtomicLongArray contendedArray;

    // 构造方法
    //	77
    ContendedAtomicLong(final long init)
    {
        // 数组大小：2个缓存行长度（通常16个long）
        contendedArray = new AtomicLongArray(2 * CACHE_LINE_LONGS);
        set(init);
    }

    // 内存布局策略    值放在数组中间位置
    void set(final long l) {
        contendedArray.set(CACHE_LINE_LONGS, l);
    }

    long get() {
        return contendedArray.get(CACHE_LINE_LONGS);
    }

    public String toString() {
        return Long.toString(get());
    }

    // 原子操作实现    操作位置：固定在中部槽位（CACHE_LINE_LONGS）
    public boolean compareAndSet(final long expect, final long l) {
        return contendedArray.compareAndSet(CACHE_LINE_LONGS, expect, l);
    }
}

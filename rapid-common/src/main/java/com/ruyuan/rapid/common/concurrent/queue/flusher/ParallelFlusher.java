package com.ruyuan.rapid.common.concurrent.queue.flusher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.WorkerPool;
import com.lmax.disruptor.dsl.ProducerType;

/**
 * <B>主类名称：</B>ParallelFlusher<BR>
 * <B>概要说明：</B>并行的Flusher多生产者多消费者工具类，基于disruptor<BR>
 * @author JiFeng
 * @since 2021年12月7日 上午1:42:55
 */
public class ParallelFlusher<E> implements Flusher<E> {

	// 声明核心组件（骨架搭建）：
	private RingBuffer<Holder> ringBuffer;      // 核心存储结构

	private EventListener<E> eventListener;      // 事件回调接口

	private WorkerPool<Holder> workerPool;     // 消费者线程池

	private ExecutorService executorService;    // 线程池

	private EventTranslatorOneArg<Holder, E> eventTranslator; // 事件转换器

	// 构造方法：
	private ParallelFlusher(Builder<E> builder) {
		// 1. 初始化线程池（命名格式：ParallelFlusher-[前缀]-pool-[序号]）
		this.executorService = Executors.newFixedThreadPool(
				builder.threads,
				new ThreadFactoryBuilder()
						.setNameFormat("ParallelFlusher-" + builder.namePrefix + "-pool-%d")
						.build());

		// 2. 设置业务监听器和转换器
		this.eventListener = builder.listener;
		this.eventTranslator = new HolderEventTranslator();

		// 3. 创建RingBuffer（核心组件初始化）
		this.ringBuffer = RingBuffer.create(
				builder.producerType,   // 生产者类型
				new HolderEventFactory(), // 事件工厂
				builder.bufferSize,      // 缓冲区大小
				builder.waitStrategy);   // 等待策略

		// 4. 创建序列屏障（协调消费顺序）
		SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();

		// 5. 初始化消费者数组  创建消费者数组，大小等于配置的线程数    为每个消费者创建HolderWorkHandler实例
		WorkHandler<Holder>[] workHandlers = new WorkHandler[builder.threads];
		for(int i = 0; i < workHandlers.length; i++) {
			workHandlers[i] = new HolderWorkHandler(); // 每个handler独立处理
		}

		// 6. 构建消费者工作池
		this.workerPool = new WorkerPool<>(
				ringBuffer,              // 事件来源
				sequenceBarrier,         // 顺序控制
				new HolderExceptionHandler(), // 异常处理
				workHandlers);           // 消费者数组

		// 7. 设置消费序列门控
		ringBuffer.addGatingSequences(workerPool.getWorkerSequences());
		
		this.workerPool = workerPool;
		
	}

	// 生命周期管理方法：
	// 启动方法
	@Override
	public void start() {
		this.ringBuffer = workerPool.start(executorService);  // 通过workerPool.start()启动所有消费者线程
	}

	@Override
	public boolean isShutdown() {
		return ringBuffer == null;
	}

	// 关闭方法
	@Override
	public void shutdown() {
		RingBuffer<Holder> temp = ringBuffer;  // 暂存引用
		ringBuffer = null;  // 置空防止新事件进入

		if(temp == null) return;  // 已关闭则直接返回

		// 1. 停止消费者（处理完存量事件）
		if(workerPool != null) {
			workerPool.drainAndHalt();
		}

		// 2. 关闭线程池
		if(executorService != null) {
			executorService.shutdown();
		}
	}

	// 单个事件异常处理方法
	private static <E> void process(EventListener<E> listener,
			Throwable e, E event) {
		
		listener.onException(e, -1, event);   // 序号-1表示系统异常
	}

	// 批量事件异常处理方法
	private static <E> void process(EventListener<E> listener, 
			Throwable e, @SuppressWarnings("unchecked") E... events) {
		
		for(E event : events) {
			process(listener, e, event);   // 批量异常回调
		}
	}

	// 事件添加方法：
	// 1. 阻塞式添加（单个）
	@Override
	public void add(E event) {
		// 1. 获取当前ringBuffer快照（避免NPE）
		final RingBuffer<Holder> temp = ringBuffer;

		// 2. 检查服务状态
		if(temp == null) {
			// 3. 服务已关闭时的处理
			process(this.eventListener, new IllegalStateException("ParallelFlusher is closed"), event);
			return;
		}

		try { // 4. 发布事件到RingBuffer
			ringBuffer.publishEvent(this.eventTranslator, event);
		} catch (NullPointerException e) {
			// 5. 并发关闭情况处理
			process(this.eventListener, new IllegalStateException("ParallelFlusher is closed"), event);
		}
	}

	// 阻塞式添加（批量事件）
	@Override
	public void add(@SuppressWarnings("unchecked") E... events) {
		final RingBuffer<Holder> temp = ringBuffer;
		if(temp == null) {
			// 批量回调
			process(this.eventListener, new IllegalStateException("ParallelFlusher is closed"), events);
			return;
		}
		try {
			// 批量发布接口
			ringBuffer.publishEvents(this.eventTranslator, events);
		} catch (NullPointerException e) {
			process(this.eventListener, new IllegalStateException("ParallelFlusher is closed"), events);
		}
	}

	// 非阻塞尝试添加（单个事件）
	@Override
	public boolean tryAdd(E event) {
		final RingBuffer<Holder> temp = ringBuffer;
		// 快速失败检查
		if(temp == null) {
			return false;
		}
		try {   // 非阻塞发布
			return ringBuffer.tryPublishEvent(this.eventTranslator, event);
		} catch (NullPointerException e) {
			return false;
		}
	}

	// 非阻塞尝试添加（批量事件）
	@Override
	public boolean tryAdd(@SuppressWarnings("unchecked") E... events) {
		final RingBuffer<Holder> temp = ringBuffer;
		if(temp == null) {
			return false;
		}
		try {   // 批量尝试发布
			return ringBuffer.tryPublishEvents(this.eventTranslator, events);
		} catch (NullPointerException e) {
			return false;
		}
	}
	
	public interface EventListener<E> {   // <E>：泛型参数，表示监听的事件类型（如String/自定义对象）

		// 正常事件处理     E event：要处理的事件对象
		void onEvent(E event) throws Exception;

		// 异常事件处理     Throwable ex：发生的异常    sequence：事件序号（用于追踪）    E event：出错的原始事件
		void onException(Throwable ex, long sequence, E event) ;
		
	}

	//  构建器模式实现（配置入口）：
	/**
	 * <B>主类名称：</B>Builder<BR>
	 * <B>概要说明：</B>建造者模型, 目的就是为了设置真实对象的属性，在创建真实对象的时候透传过去<BR>
	 * @author JiFeng
	 * @since 2021年12月7日 上午2:07:01
	 */
	public static class Builder<E> {
		
		private ProducerType producerType = ProducerType.MULTI;   // 生产者类型：多生产者
		
		private int bufferSize = 16 * 1024;    // 环形缓冲区大小
		
		private int threads = 1;    // 处理线程数
		
		private String namePrefix = "";    // 线程名前缀
		
		private WaitStrategy waitStrategy = new BlockingWaitStrategy();   // 等待策略
		
		//	消费者监听：
		private EventListener<E> listener;    // 事件监听器

		// 配置方法：
		public Builder<E> setProducerType(ProducerType producerType) {
			Preconditions.checkNotNull(producerType);
			this.producerType = producerType;
			return this;
		}
		
		public Builder<E> setThreads(int threads) {   // 设置线程数（消费者数量）
			Preconditions.checkArgument(threads > 0);  // 至少1个线程
			this.threads = threads;
			return this;
		}
		
		public Builder<E> setBufferSize(int bufferSize) {
			Preconditions.checkArgument(Integer.bitCount(bufferSize) == 1);  // 必须2的幂次方
			this.bufferSize = bufferSize;
			return this;
		}
		
		public Builder<E> setNamePrefix(String namePrefix) {
			Preconditions.checkNotNull(namePrefix);
			this.namePrefix = namePrefix;
			return this;
		}
		
		public Builder<E> setWaitStrategy(WaitStrategy waitStrategy) {
			Preconditions.checkNotNull(waitStrategy);
			this.waitStrategy = waitStrategy;
			return this;
		}
		
		public Builder<E> setEventListener(EventListener<E> listener) {
			Preconditions.checkNotNull(listener);
			this.listener = listener;
			return this;
		}

		// 构建方法：
		public ParallelFlusher<E> build() {
			return new ParallelFlusher<>(this);
		}
	}

	// 内部类实现（从属到核心）：
	private class Holder {
		
		private E event;
		
		public void setValue(E event) {
			this.event = event;
		}
		
		public String toString() {
			return "Holder event=" + event;
		} 
		
	}

	// Holder对象的工厂 ：按需创建新的Holder实例
	private class HolderEventFactory implements EventFactory<Holder> {
		@Override
		public ParallelFlusher<E>.Holder newInstance() {
			return new Holder();
		}
	}

	// 实际的事件处理器 ：从RingBuffer取出Holder并处理其中的事件
	private class HolderWorkHandler implements WorkHandler<Holder> {

		@Override
		public void onEvent(ParallelFlusher<E>.Holder event) throws Exception {
			eventListener.onEvent(holder.event);    // 处理业务事件
			holder.setValue(null);              // 清空引用
		}
		
	}

	// 异常处理器 统一处理三类异常：事件处理异常（主要）、 启动异常 、关闭异常
	private class HolderExceptionHandler implements ExceptionHandler<Holder> {

		@Override
		public void handleEventException(Throwable ex, long sequence, ParallelFlusher<E>.Holder event) {
			Holder holder = (Holder)event;  // 类型转换
			try {
				// 回调业务监听器
				eventListener.onException(ex, sequence, holder.event);
			} catch (Exception e) {
				// 忽略监听器自身的异常
			} finally {
				holder.setValue(null);  // 确保资源释放
			}
		}

		@Override
		public void handleOnStartException(Throwable ex) {
			throw new UnsupportedOperationException(ex);
		}

		@Override
		public void handleOnShutdownException(Throwable ex) {
			throw new UnsupportedOperationException(ex);
		}
		
	}

	// 完成 E -> Holder的类型转换
	private class HolderEventTranslator implements EventTranslatorOneArg<Holder, E> {

		@Override
		public void translateTo(ParallelFlusher<E>.Holder holder, long sequence, E event) {
			holder.setValue(event);
		}
		
	} 

}

package com.ruyuan.rapid.core.netty.processor;

import com.lmax.disruptor.dsl.ProducerType;
import com.ruyuan.rapid.common.concurrent.queue.flusher.ParallelFlusher;
import com.ruyuan.rapid.common.enums.ResponseCode;
import com.ruyuan.rapid.core.RapidConfig;
import com.ruyuan.rapid.core.context.HttpRequestWrapper;
import com.ruyuan.rapid.core.helper.ResponseHelper;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * <B>主类名称：</B>NettyBatchEventProcessor<BR>
 * <B>概要说明：</B>flusher缓冲队列的核心实现, 最终调用的方法还是要回归到NettyCoreProcessor<BR>
 * @author JiFeng
 * @since 2021年12月5日 下午10:11:16
 */
@Slf4j
public class NettyBatchEventProcessor implements NettyProcessor {
	
	private static final String THREAD_NAME_PREFIX = "rapid-flusher-";
	
	private RapidConfig rapidConfig;
	
	private NettyCoreProcessor nettyCoreProcessor;
	
	private ParallelFlusher<HttpRequestWrapper> parallelFlusher;
	
	public NettyBatchEventProcessor(RapidConfig rapidConfig, NettyCoreProcessor nettyCoreProcessor) {
		// 1. 注入依赖
		this.rapidConfig = rapidConfig;
		this.nettyCoreProcessor = nettyCoreProcessor;

		// 2. 构建ParallelFlusher
		ParallelFlusher.Builder<HttpRequestWrapper> builder = new ParallelFlusher.Builder<HttpRequestWrapper>()
				.setBufferSize(rapidConfig.getBufferSize())       // 缓冲区大小
				.setThreads(rapidConfig.getProcessThread())       // 处理线程数
				.setProducerType(ProducerType.MULTI)               // 多生产者模式
				.setNamePrefix(THREAD_NAME_PREFIX)                // 线程名前缀
				.setWaitStrategy(rapidConfig.getATureWaitStrategy());   // 等待策略

		// 3. 设置事件监听器
		BatchEventProcessorListener batchEventProcessorListener = new BatchEventProcessorListener();
		builder.setEventListener(batchEventProcessorListener);

		// 4. 构建flusher实例
		this.parallelFlusher = builder.build();
	}

	// 核心方法实现：
	// 1. 请求处理方法
	@Override
	// Netty IO线程接收请求，包装为HttpRequestWrapper，非阻塞提交到Disruptor队列，返回继续接收新请求
	public void process(HttpRequestWrapper httpRequestWrapper) {
		this.parallelFlusher.add(httpRequestWrapper);  // 提交到Disruptor队列
	}

	// 2. 启动方法
	@Override
	public void start() {
		this.nettyCoreProcessor.start();    // 启动业务处理器
		this.parallelFlusher.start();       // 启动Disruptor
	}

	// 3. 关闭方法
	@Override
	public void shutdown() {
		this.nettyCoreProcessor.shutdown();
		this.parallelFlusher.shutdown();
	}

	// 事件监听器实现
	/**
	 * <B>主类名称：</B>BatchEventProcessorListener<BR>
	 * <B>概要说明：</B>监听事件的处理核心逻辑<BR>
	 * @author JiFeng
	 * @since 2021年12月8日 下午9:46:30
	 */
	public class BatchEventProcessorListener implements ParallelFlusher.EventListener<HttpRequestWrapper> {

		// 正常事件处理
		@Override
		public void onEvent(HttpRequestWrapper event) throws Exception {
			nettyCoreProcessor.process(event);
		}

		// 异常事件处理
		@Override
		public void onException(Throwable t, long sequence, HttpRequestWrapper event) {
			// 1. 获取原始请求和上下文
			HttpRequest request = event.getFullHttpRequest();
			ChannelHandlerContext ctx = event.getCtx();
			try {   // 2. 记录错误日志
				log.error("#BatchEventProcessorListener# onException 请求处理失败, request: {}. errorMessage: {}",
						request, t.getMessage(), t);

				//	首先构建响应对象
				FullHttpResponse fullHttpResponse = ResponseHelper.getHttpResponse(ResponseCode.INTERNAL_ERROR);
				//	判断是否保持连接
				if(!HttpUtil.isKeepAlive(request)) {
					// 非Keep-Alive连接：响应后关闭
					ctx.writeAndFlush(fullHttpResponse).addListener(ChannelFutureListener.CLOSE);
				} else {
					//	如果保持连接, 则需要设置一下响应头：key: CONNECTION,  value: KEEP_ALIVE
					fullHttpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
					ctx.writeAndFlush(fullHttpResponse);
				}

			} catch (Exception e) {
				//	ignore    // 5. 兜底异常处理
				log.error("#BatchEventProcessorListener# onException 请求回写失败, request: {}. errorMessage: {}",
						request, e.getMessage(), e);
			}
		}
	}

	public RapidConfig getRapidConfig() {
		return rapidConfig;
	}
	
	
	
}

package com.ruyuan.rapid.core.netty.processor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ruyuan.rapid.common.concurrent.queue.mpmc.MpmcBlockingQueue;
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
 * <B>主类名称：</B>NettyMpmcProcessor<BR>
 * <B>概要说明：</B>mpmc的核心实现处理器, 最终我们还是要使用NettyCoreProcessor<BR>
 * @author JiFeng
 * @since 2021年12月5日 下午10:13:33
 */
@Slf4j
public class NettyMpmcProcessor implements NettyProcessor {
	
	private RapidConfig rapidConfig;
	
	private NettyCoreProcessor nettyCoreProcessor;
	
	private MpmcBlockingQueue<HttpRequestWrapper> mpmcBlockingQueue;
	
	private boolean usedExecutorPool;
	
	private ExecutorService executorService;
	
	private volatile boolean isRunning = false;
	
	private Thread consumerProcessorThread;

	public NettyMpmcProcessor(RapidConfig rapidConfig, NettyCoreProcessor nettyCoreProcessor, boolean usedExecutorPool) {
		this.rapidConfig = rapidConfig;
		this.nettyCoreProcessor = nettyCoreProcessor;
		this.mpmcBlockingQueue = new MpmcBlockingQueue<>(rapidConfig.getBufferSize());
		this.usedExecutorPool = usedExecutorPool;
	}
	
	@Override
	public void process(HttpRequestWrapper httpRequestWrapper) throws Exception {
		this.mpmcBlockingQueue.put(httpRequestWrapper);
	}

	@Override
	public void start() {
		this.isRunning = true;
		this.nettyCoreProcessor.start();
		if(usedExecutorPool) {
			this.executorService = Executors.newFixedThreadPool(rapidConfig.getProcessThread());
			for(int i = 0; i < rapidConfig.getProcessThread(); i ++) {
				this.executorService.submit(new ConsumerProcessor());
			}
		} else {
			this.consumerProcessorThread = new Thread(new ConsumerProcessor());
			this.consumerProcessorThread.start();
		}
	}

	@Override
	public void shutdown() {
		this.isRunning = false;
		this.nettyCoreProcessor.shutdown();
		if(usedExecutorPool) {
			this.executorService.shutdown();
		} 
	}
	
	/**
	 * <B>主类名称：</B>ConsumerProcessor<BR>
	 * <B>概要说明：</B>消费者核心实现类<BR>
	 * @author JiFeng
	 * @since 2021年12月9日 上午12:18:40
	 */
	public class ConsumerProcessor implements Runnable {

		@Override
		public void run() {
			while(isRunning) {
				HttpRequestWrapper event = null;
				try {
					event = mpmcBlockingQueue.take();
					nettyCoreProcessor.process(event);
				} catch (Throwable t) {
					if(event != null) {
						HttpRequest request = event.getFullHttpRequest();
						ChannelHandlerContext ctx = event.getCtx();
						try {
							log.error("#ConsumerProcessor# onException 请求处理失败, request: {}. errorMessage: {}", 
									request, t.getMessage(), t);
							
							//	首先构建响应对象
							FullHttpResponse fullHttpResponse = ResponseHelper.getHttpResponse(ResponseCode.INTERNAL_ERROR);
							//	判断是否保持连接
							if(!HttpUtil.isKeepAlive(request)) {
								ctx.writeAndFlush(fullHttpResponse).addListener(ChannelFutureListener.CLOSE);
							} else {
								//	如果保持连接, 则需要设置一下响应头：key: CONNECTION,  value: KEEP_ALIVE
								fullHttpResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
								ctx.writeAndFlush(fullHttpResponse);
							}
							
						} catch (Exception e) {
							//	ignore
							log.error("#ConsumerProcessor# onException 请求回写失败, request: {}. errorMessage: {}", 
									request, e.getMessage(), e);
						}						
					} else {
						log.error("#ConsumerProcessor# onException event is Empty errorMessage: {}",  t.getMessage(), t);
					}
				} 
			}
		}
	}

}

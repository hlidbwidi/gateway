package com.ruyuan.rapid.core.netty;

import java.net.InetSocketAddress;

import com.ruyuan.rapid.common.util.RemotingHelper;
import com.ruyuan.rapid.common.util.RemotingUtil;
import com.ruyuan.rapid.core.LifeCycle;
import com.ruyuan.rapid.core.RapidConfig;
import com.ruyuan.rapid.core.netty.processor.NettyProcessor;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * <B>主类名称：</B>NettyHttpServer<BR>
 * <B>概要说明：</B>承接所有网络请求的核心类<BR>
 * @author JiFeng
 * @since 2021年12月5日 下午6:38:14
 */
@Slf4j
public class NettyHttpServer implements LifeCycle {
	
	private final RapidConfig rapidConfig;  // 网关配置中心

	private int port = 8888;  // 默认端口

	private ServerBootstrap serverBootstrap;  // Netty服务启动器

	private EventLoopGroup eventLoopGroupBoss;  // 接收连接的线程组

	private EventLoopGroup eventLoopGroupWork;  // 处理业务的线程组

	private NettyProcessor nettyProcessor;  // 核心业务处理器
	
	public NettyHttpServer(RapidConfig rapidConfig, NettyProcessor nettyProcessor) {
		this.rapidConfig = rapidConfig;
		this.nettyProcessor = nettyProcessor;
		if(rapidConfig.getPort() > 0 && rapidConfig.getPort() < 65535) {  // 如果是正常的端口号：
			this.port = rapidConfig.getPort();
		}
		//	初始化NettyHttpServer
		init();   // 立即构建Netty核心组件
	}

	/**
	 * <B>方法名称：</B>init<BR>
	 * <B>概要说明：</B>初始化方法<BR>
	 * @author  JiFeng
	 * @since 2021年12月5日 下午6:42:55
	 * @see com.ruyuan.rapid.core.LifeCycle#init()
	 */
	@Override
	public void init() {
		// 1. 创建ServerBootstrap：  Netty服务启动的入口，用于配置服务端参数和启动
		this.serverBootstrap = new ServerBootstrap();
		// 2. 选择IO模型（Epoll/NIO）
		if(useEPoll()) {   // 检查是否使用Epoll模式
			// Epoll模式初始化（Linux专属）
			this.eventLoopGroupBoss = new EpollEventLoopGroup(rapidConfig.getEventLoopGroupBossNum(), 
					new DefaultThreadFactory("NettyBossEPoll"));
			this.eventLoopGroupWork = new EpollEventLoopGroup(rapidConfig.getEventLoopGroupWorkNum(), 
					new DefaultThreadFactory("NettyWorkEPoll"));
		} else {    // 普通NIO模式初始化
			this.eventLoopGroupBoss = new NioEventLoopGroup(rapidConfig.getEventLoopGroupBossNum(), 
					new DefaultThreadFactory("NettyBossNio"));
			this.eventLoopGroupWork = new NioEventLoopGroup(rapidConfig.getEventLoopGroupWorkNum(), 
					new DefaultThreadFactory("NettyWorkNio"));
		}
	}
	
	/**
	 * <B>方法名称：</B>useEPoll<BR>
	 * <B>概要说明：</B>判断是否支持EPoll<BR>
	 * @author JiFeng
	 * @since 2021年12月5日 下午6:46:01
	 * @return
	 */
	public boolean useEPoll() {
		// return true 如果满足以下所有条件：
		// 1. 配置要求使用 Epoll     2. 当前系统是 Linux     3. Epoll 可用
		return rapidConfig.isUseEPoll() && RemotingUtil.isLinuxPlatform() && Epoll.isAvailable();
	}

	/**
	 * <B>方法名称：</B>start<BR>
	 * <B>概要说明：</B>服务器启动方法<BR>
	 * @author  JiFeng
	 * @since 2021年12月5日 下午6:50:42
	 * @see com.ruyuan.rapid.core.LifeCycle#start()
	 */
	@Override
	public void start() {
		ServerBootstrap handler = this.serverBootstrap
		 		.group(eventLoopGroupBoss, eventLoopGroupWork)  // 线程模型配置
				// 选择网络通信实现方式
				// Epoll：Linux高性能模式（相当于VIP快速通道）       NIO：跨平台标准模式（普通通道）
		 		.channel(useEPoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
				// 设置等待连接队列大小      比喻：餐厅门口的等待区有1024个座位
		 		.option(ChannelOption.SO_BACKLOG, 1024)			//	sync + accept = backlog
				// 允许端口复用            场景：服务器重启后可以立即使用相同端口
		 		.option(ChannelOption.SO_REUSEADDR, true)   	//	tcp端口重绑定
				// 禁用TCP保活机制
		 		.option(ChannelOption.SO_KEEPALIVE, false)  	//  如果在两小时内没有数据通信的时候，TCP会自动发送一个活动探测数据报文
				// 禁用Nagle算法 ：    小数据包立即发送，减少延迟
		 		.childOption(ChannelOption.TCP_NODELAY, true)   //	该参数的左右就是禁用Nagle算法，使用小数据传输时合并
		 		.childOption(ChannelOption.SO_SNDBUF, 65535)	//	设置发送数据缓冲区大小
		 		.childOption(ChannelOption.SO_RCVBUF, 65535)	//	设置接收数据缓冲区大小
				// 指定服务器监听端口       如果port=8888，就是监听0.0.0.0:8888
		 		.localAddress(new InetSocketAddress(this.port))
				// 配置了Netty服务器的请求处理管道(Pipeline)，相当于定义了HTTP请求从接收到响应的完整处理流程
		 		.childHandler(new ChannelInitializer<Channel>() {

					@Override
					protected void initChannel(Channel ch) throws Exception {
						ch.pipeline().addLast(
								new HttpServerCodec(),
								new HttpObjectAggregator(rapidConfig.getMaxContentLength()),
								new HttpServerExpectContinueHandler(),
								new NettyServerConnectManagerHandler(),
								new NettyHttpServerHandler(nettyProcessor)
								);
					}
				});
		
		if(rapidConfig.isNettyAllocator()) {
			handler.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
		}
		
		try {
			this.serverBootstrap.bind().sync();
			log.info("< ============= Rapid Server StartUp On Port: " + this.port + "================ >");
		} catch (Exception e) {
			throw new RuntimeException("this.serverBootstrap.bind().sync() fail!", e);
		}
	}
	
	/**
	 * <B>方法名称：</B>shutdown<BR>
	 * <B>概要说明：</B>关闭<BR>
	 * @author  JiFeng
	 * @since 2021年12月5日 下午7:26:43
	 * @see com.ruyuan.rapid.core.LifeCycle#shutdown()
	 */
	@Override
	public void shutdown() {
		if(eventLoopGroupBoss != null) {
			eventLoopGroupBoss.shutdownGracefully();
		}
		if(eventLoopGroupWork != null) {
			eventLoopGroupWork.shutdownGracefully();
		}
	}
	
	/**
	 * <B>方法名称：</B>getEventLoopGroupWork<BR>
	 * <B>概要说明：</B>获取NettyHttpServer的EventLoopGroupWork<BR>
	 * @author JiFeng
	 * @since 2021年12月5日 下午10:47:42
	 * @return EventLoopGroup
	 */
	public EventLoopGroup getEventLoopGroupWork() {
		return eventLoopGroupWork;
	}


	/**
	 * <B>主类名称：</B>NettyServerConnectManagerHandler<BR>
	 * <B>概要说明：</B>连接管理器<BR>
	 * @author JiFeng
	 * @since 2021年12月5日 下午7:10:04
	 */
	static class NettyServerConnectManagerHandler extends ChannelDuplexHandler {
		
	    @Override
	    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
			// 解析客户端IP和端口（如"192.168.1.1:54321"）
	    	final String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
	    	log.debug("NETTY SERVER PIPLINE: channelRegistered {}", remoteAddr);
	    	super.channelRegistered(ctx);
	    }

	    @Override
	    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
	    	final String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
	    	log.debug("NETTY SERVER PIPLINE: channelUnregistered {}", remoteAddr);
	    	super.channelUnregistered(ctx);
	    }

	    @Override
	    public void channelActive(ChannelHandlerContext ctx) throws Exception {
	    	final String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
	    	log.debug("NETTY SERVER PIPLINE: channelActive {}", remoteAddr);
	    	super.channelActive(ctx);
	    }

	    @Override
	    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
	    	final String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
	    	log.debug("NETTY SERVER PIPLINE: channelInactive {}", remoteAddr);
	    	super.channelInactive(ctx);
	    }
		
	    @Override    // 处理用户自定义事件的核心方法
	    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
	    	if(evt instanceof IdleStateEvent) {  // instanceof：检查事件是否为IdleStateEvent类型
                                                  // 目的：只处理空闲状态事件，忽略其他类型事件
	    		IdleStateEvent event = (IdleStateEvent)evt;
	    		if(event.state().equals(IdleState.ALL_IDLE)) {  // 检查是否读写都空闲
	    	    	final String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
	    	    	log.warn("NETTY SERVER PIPLINE: userEventTriggered: IDLE {}", remoteAddr);
	    	    	ctx.channel().close();
	    		}
	    	}
	    	ctx.fireUserEventTriggered(evt);
	    }

	    @Override
	    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
	            throws Exception {
	    	final String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
	    	log.warn("NETTY SERVER PIPLINE: remoteAddr： {}, exceptionCaught {}", remoteAddr, cause);
	    	ctx.channel().close();
	    }
		
	}

}

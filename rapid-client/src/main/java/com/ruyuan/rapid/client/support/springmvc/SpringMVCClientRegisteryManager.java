package com.ruyuan.rapid.client.support.springmvc;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.ruyuan.rapid.client.core.AbstractClientRegisteryManager;
import com.ruyuan.rapid.client.core.RapidAnnotationScanner;
import com.ruyuan.rapid.client.core.autoconfigure.RapidProperties;
import com.ruyuan.rapid.common.config.ServiceDefinition;
import com.ruyuan.rapid.common.config.ServiceInstance;
import com.ruyuan.rapid.common.constants.BasicConst;
import com.ruyuan.rapid.common.constants.RapidConst;
import com.ruyuan.rapid.common.util.NetUtils;
import com.ruyuan.rapid.common.util.TimeUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * <B>主类名称：</B>SpringMVCClientRegisteryManager<BR>
 * <B>概要说明：</B>Http请求的客户端注册管理器<BR>
 * @author JiFeng
 * @since 2021年12月19日 上午10:38:55
 */
@Slf4j
public class SpringMVCClientRegisteryManager extends AbstractClientRegisteryManager implements ApplicationListener<ApplicationEvent>, ApplicationContextAware  {

	ApplicationContext applicationContext;
	
	@Autowired
	private ServerProperties serverProperties;
	
	private static final Set<Object> uniqueBeanSet = new HashSet<>();
	
	public SpringMVCClientRegisteryManager(RapidProperties rapidProperties) throws Exception {
		super(rapidProperties);
	}
	
	@PostConstruct
	private void init() {
		if(!ObjectUtils.allNotNull(serverProperties, serverProperties.getPort())) {
			return;
		}
		//	判断如果当前验证属性都为空 就进行初始化
		whetherStart = true;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if(!whetherStart) {
			return;
		}
		
		if(event instanceof WebServerInitializedEvent ||
				event instanceof ServletWebServerInitializedEvent) {
			try {
				registerySpringMVC();
			} catch (Exception e) {
				log.error("#SpringMVCClientRegisteryManager# registerySpringMVC error", e);
			}
		} else if(event instanceof ApplicationStartedEvent){
			//	START:::
			System.err.println("******************************************");
			System.err.println("**        Rapid SpringMVC Started       **");
			System.err.println("******************************************");
		}
	}

	/**
	 * <B>方法名称：</B>registerySpringMVC<BR>
	 * <B>概要说明：</B>解析SpringMvc的事件，进行注册<BR>
	 * @author JiFeng
	 * @throws Exception 
	 * @since 2021年12月19日 上午10:48:25
	 */
	private void registerySpringMVC() throws Exception {
		Map<String, RequestMappingHandlerMapping> allRequestMappings = BeanFactoryUtils
				.beansOfTypeIncludingAncestors(applicationContext, 
						RequestMappingHandlerMapping.class,
						true,
						false);
		
		for(RequestMappingHandlerMapping handlerMapping : allRequestMappings.values()) {
			Map<RequestMappingInfo, HandlerMethod> map = handlerMapping.getHandlerMethods();
			for(Map.Entry<RequestMappingInfo, HandlerMethod> me : map.entrySet()) {
				HandlerMethod handlerMethod = me.getValue();
				Class<?> clazz = handlerMethod.getBeanType();
				Object bean = applicationContext.getBean(clazz);
				//	如果当前Bean对象已经加载则不需要做任何事
				if(uniqueBeanSet.add(bean)) {
					ServiceDefinition serviceDefinition = RapidAnnotationScanner.getInstance().scanbuilder(bean);
					if(serviceDefinition != null) {
						//	设置环境
						serviceDefinition.setEnvType(getEnv());
						//	注册服务定义
						registerServiceDefinition(serviceDefinition);
						
						//	注册服务实例：
						ServiceInstance serviceInstance = new ServiceInstance();
						String localIp = NetUtils.getLocalIp();
						int port = serverProperties.getPort();
						String serviceInstanceId = localIp + BasicConst.COLON_SEPARATOR + port;
						String address = serviceInstanceId;
						String uniqueId = serviceDefinition.getUniqueId();
						String version = serviceDefinition.getVersion();
						
						serviceInstance.setServiceInstanceId(serviceInstanceId);
						serviceInstance.setUniqueId(uniqueId);
						serviceInstance.setAddress(address);
						serviceInstance.setWeight(RapidConst.DEFAULT_WEIGHT);
						serviceInstance.setRegisterTime(TimeUtil.currentTimeMillis());
						serviceInstance.setVersion(version);
						
						registerServiceInstance(serviceInstance);
					}
				}
			}
		}
	}
	
}

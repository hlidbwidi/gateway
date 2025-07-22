package com.ruyuan.rapid.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import com.ruyuan.rapid.common.util.PropertiesUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * <B>主类名称：</B>RapidConfigLoader<BR>
 * <B>概要说明：</B>网关配置信息加载类<BR>
 * 	网关配置加载规则：优先级顺序如下：高的优先级会覆盖掉低的优先级
 * 		运行参数(最高) ->  jvm参数  -> 环境变量  -> 配置文件  -> 内部RapidConfig对象的默认属性值(最低);
 * @author JiFeng
 * @since 2021年12月5日 下午5:52:31
 */
@Slf4j         // 使用Lombok注解自动生成日志对象
public class RapidConfigLoader {

	private final static String CONFIG_ENV_PREFIEX = "RAPID_";
    // 定义环境变量前缀，只加载RAPID_开头的环境变量（如RAPID_PORT=8080）

	private final static String CONFIG_JVM_PREFIEX = "rapid.";
    // 定义JVM参数前缀，只加载rapid.开头的参数（如-Drapid.port=8080）

	private final static String CONFIG_FILE = "rapid.properties";
    // 默认配置文件名称，放在classpath根目录
	
	private final static RapidConfigLoader INSTANCE = new RapidConfigLoader();
	
	private RapidConfig rapidConfig = new RapidConfig();  // 持有配置对象的实例，所有配置最终都注入到这里
	
	private RapidConfigLoader() {
	}   // 私有构造方法，防止外部实例化
	
	public static RapidConfigLoader getInstance() {
		return INSTANCE;
	}
	
	public static RapidConfig getRapidConfig() {
		return INSTANCE.rapidConfig;
	}
	
	public RapidConfig load(String args[]) {   // args 是标准的Java main方法参数格式，表示从命令行传入的参数数组
		// 核心加载方法
		//	加载逻辑
		
		//	1. 配置文件
		{
			// 从classpath获取配置文件的输入流
			InputStream is = RapidConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
			if(is != null) {   // 检查配置文件是否存在   若is非空，即配了。若is为空，说明没有这个文件，即没配
				Properties properties = new Properties();  // 创建Properties对象存储键值对
				try {
					properties.load(is);  // 把配置文件内容写入这个"空白记事本"
					PropertiesUtils.properties2Object(properties, rapidConfig);
					// 将属性映射到RapidConfig对象
				} catch (IOException e) {
					log.warn("#RapidConfigLoader# load config file: {} is error", CONFIG_FILE, e);
					// 捕获并记录IO异常（如文件损坏），但不中断程序
				} finally {
					if(is != null) {
						try {
							is.close();  // 确保关闭输入流
						} catch (IOException e) {
							// ignore  静默处理关闭异常
						}
					}
				}
			}
		}
		
		//	2. 环境变量
		{
			Map<String, String> env = System.getenv();  // 获取所有环境变量
			Properties properties = new Properties();
			properties.putAll(env);  // 全部放入Properties
			// 只处理RAPID_前缀的环境变量，并注入配置对象
			PropertiesUtils.properties2Object(properties, rapidConfig, CONFIG_ENV_PREFIEX);
		}

		//	3. jvm参数
		{
			// 获取JVM参数（-D指定的参数）      只处理rapid.前缀的参数
			Properties properties = System.getProperties();
			PropertiesUtils.properties2Object(properties, rapidConfig, CONFIG_JVM_PREFIEX);
		}

		//	4. 运行参数: --xxx=xxx --enable=true  --port=1234
		{
			if(args != null && args.length > 0) {
				Properties properties = new Properties();
				for(String arg : args) {
					if(arg.startsWith("--") && arg.contains("=")) {
						properties.put(arg.substring(2, arg.indexOf("=")), arg.substring(arg.indexOf("=") + 1));
					}
				}
				PropertiesUtils.properties2Object(properties, rapidConfig);
			}
		}

		return rapidConfig;
	}

}

package com.ruyuan.rapid.core.netty.processor.filter;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.ruyuan.rapid.common.config.Rule.FilterConfig;
import com.ruyuan.rapid.common.constants.BasicConst;
import com.ruyuan.rapid.common.util.JSONUtil;
import com.ruyuan.rapid.core.context.Context;
import com.ruyuan.rapid.core.netty.processor.cache.DefaultCacheManager;

import lombok.extern.slf4j.Slf4j;

/**
 * <B>主类名称：</B>AbstractEntryProcessorFilter<BR>
 * <B>概要说明：</B>抽象的Filter 用于真正的Filter进行继承的<BR>
 * @author JiFeng
 * @since 2021年12月16日 下午11:34:26
 */
@Slf4j
public abstract class AbstractEntryProcessorFilter<FilterConfigClass> extends AbstractLinkedProcessorFilter<Context> {

	protected Filter filterAnnotation;
	
	protected Cache<String, FilterConfigClass> cache;
	
	protected final Class<FilterConfigClass> filterConfigClass;
	
	public AbstractEntryProcessorFilter(Class<FilterConfigClass> filterConfigClass) {
		this.filterAnnotation = this.getClass().getAnnotation(Filter.class);
		this.filterConfigClass = filterConfigClass;
		this.cache = DefaultCacheManager.getInstance().create(DefaultCacheManager.FILTER_CONFIG_CACHE_ID);
	}
	
	@Override
	public boolean check(Context ctx) throws Throwable {
		return ctx.getRule().hashId(filterAnnotation.id());
	}
	
	@Override
	public void transformEntry(Context ctx, Object... args) throws Throwable {
		FilterConfigClass filterConfigClass = dynamicLoadCache(ctx, args);
		super.transformEntry(ctx, filterConfigClass);
	}

	/**
	 * <B>方法名称：</B>dynamicLoadCache<BR>
	 * <B>概要说明：</B>动态加载缓存：每一个过滤器的具体配置规则<BR>
	 * @author JiFeng
	 * @since 2021年12月16日 下午11:40:51
	 * @param ctx
	 * @param args
	 */
	private FilterConfigClass dynamicLoadCache(Context ctx, Object[] args) {
		//	通过上下文对象拿到规则，再通过规则获取到指定filterId的FilterConfig
		FilterConfig filterConfig = ctx.getRule().getFilterConfig(filterAnnotation.id());
		
		//	定义一个cacheKey：
		String ruleId = ctx.getRule().getId();
		String cacheKey = ruleId + BasicConst.DOLLAR_SEPARATOR + filterAnnotation.id();
		
		FilterConfigClass fcc = cache.getIfPresent(cacheKey);
		if(fcc == null) {
			if(filterConfig != null && StringUtils.isNotEmpty(filterConfig.getConfig())) {
				String configStr = filterConfig.getConfig();
				try {
					fcc = JSONUtil.parse(configStr, filterConfigClass);
					cache.put(cacheKey, fcc);					
				} catch (Exception e) {
					log.error("#AbstractEntryProcessorFilter# dynamicLoadCache filterId: {}, config parse error: {}",
							filterAnnotation.id(),
							configStr,
							e);
				}
			}
		}
		return fcc;
	}


}

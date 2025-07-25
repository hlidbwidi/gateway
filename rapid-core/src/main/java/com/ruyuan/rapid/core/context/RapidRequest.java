package com.ruyuan.rapid.core.context;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;

import com.google.common.collect.Lists;
import com.jayway.jsonpath.JsonPath;
import com.ruyuan.rapid.common.constants.BasicConst;
import com.ruyuan.rapid.common.util.TimeUtil;

import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * <B>主类名称：</B>RapidRequest<BR>
 * <B>概要说明：</B>核心请求自定义实现<BR>
 * @author JiFeng
 * @since 2021年12月9日 下午12:28:35
 */
@Slf4j
public class RapidRequest implements RapidRequestMutable {
	
	/**
	 * 	FullHttpRequest: 在header里面必须要有该属性：uniqueId
	 * 	表示服务的唯一性ID: serviceId:version
	 */
	@Getter
	private final String uniqueId;
	
	/**
	 * 	一次请求进入网关的开始时间戳
	 */
	@Getter
	private final long beginTime;
	
	@Getter
	private final Charset charset;
	
	/**
	 * 	客户端的ip地址：流控、黑白名单
	 */
	@Getter
	private final String clientIp;
	
	/**
	 * 	请求的地址：ip:port
	 */
	@Getter
	private final String host;
	
	/**
	 * 	请求的路径：/xxx/xx/xxx
	 */
	@Getter
	private final String path;
	
	/**
	 * uri: /xxx/xx/xxx?attr1=value1&attr2=value2
	 */
	@Getter
	private final String uri;
	
	/**
	 * 	请求的方式：get/post/put...
	 */
	@Getter
	private final HttpMethod method;
	
	/**
	 * 	请求的格式
	 */
	@Getter
	private final String contentType;
	
	/**
	 * 	请求头信息
	 */
	@Getter
	private final HttpHeaders headers;
	
	/**
	 * 	参数解析器
	 */
	@Getter
	private final QueryStringDecoder queryDecoder;
	
	/**
	 *	FullHttpRequest 	
	 */
	@Getter
	private final FullHttpRequest fullHttpRequest;	
	
	/**
	 * 	请求体
	 */
	private String body;
	
	/**
	 * 	请求对象里面的cookie：
	 */
	private Map<String, Cookie> cookieMap;
	
	/**
	 * 	请求的时候定义的post参数集合
	 */
	private Map<String, List<String>> postParameters;
	
	/***************** RapidRequestMutable:可修改的请求变量 	**********************/
	
	/**
	 * 	可修改的scheme：默认为 http://
	 */
	private String modifyScheme;
	
	/**
	 * 	可修改的host
	 */
	private String modifyHost;
	
	/**
	 * 	可修改的path
	 */
	private String modifyPath;
	
	/**
	 * 	构建下游请求时的Http请构建器
	 */
	private final RequestBuilder requestBuilder;
	
	/**
	 * <B>构造方法</B>RapidRequest<BR>
	 * @param uniqueId
	 * @param charset
	 * @param clientIp
	 * @param host
	 * @param uri
	 * @param method
	 * @param contentType
	 * @param headers
	 * @param fullHttpRequest
	 */
	public RapidRequest(String uniqueId, Charset charset, String clientIp, String host,
			String uri, HttpMethod method, String contentType, HttpHeaders headers, FullHttpRequest fullHttpRequest) {
		this.uniqueId = uniqueId;
		this.beginTime = TimeUtil.currentTimeMillis();
		this.charset = charset;
		this.clientIp = clientIp;
		this.host = host;
		this.method = method;
		this.contentType = contentType;
		this.headers = headers;
		this.uri = uri;
		this.queryDecoder = new QueryStringDecoder(uri, charset);
		this.path = queryDecoder.path();
		this.fullHttpRequest = fullHttpRequest;
		
		this.modifyHost = host;
		this.modifyPath = path;
		this.modifyScheme = BasicConst.HTTP_PREFIX_SEPARATOR;
		this.requestBuilder = new RequestBuilder();
		this.requestBuilder.setMethod(getMethod().name());
		this.requestBuilder.setHeaders(getHeaders());
		this.requestBuilder.setQueryParams(queryDecoder.parameters());
		ByteBuf contentBuffer = fullHttpRequest.content();
		if(Objects.nonNull(contentBuffer)) {
			this.requestBuilder.setBody(contentBuffer.nioBuffer());
		}
	}
	
	/**
	 * <B>方法名称：</B>getBody<BR>
	 * <B>概要说明：</B>获取body信息<BR>
	 * @author JiFeng
	 * @since 2021年12月9日 下午1:14:21
	 * @return
	 */
	public String getBody() {
		if(StringUtils.isEmpty(body)) {
			body = fullHttpRequest.content().toString(charset);
		}
		return body;
	}
	
	/**
	 * <B>方法名称：</B>getCookie<BR>
	 * <B>概要说明：</B>获取指定的cookie<BR>
	 * @author JiFeng
	 * @since 2021年12月9日 下午1:17:32
	 * @param name
	 * @return Cookie
	 */
	public Cookie getCookie(String name) {
		if(cookieMap == null) {
			cookieMap = new HashMap<String, Cookie>();
			String cookieStr = getHeaders().get(HttpHeaderNames.COOKIE);
			Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieStr);
			for(Cookie cookie : cookies) {
				cookieMap.put(name, cookie);
			}
		}
		return cookieMap.get(name);
	}
	
	/**
	 * <B>方法名称：</B>getQueryParametersMultiple<BR>
	 * <B>概要说明：</B>获取指定名称的参数值<BR>
	 * @author JiFeng
	 * @since 2021年12月9日 下午1:19:03
	 * @param name 
	 * @return 
	 */
	public List<String> getQueryParametersMultiple(String name){
		return queryDecoder.parameters().get(name);
	}
	
	public List<String> getPostParametersMultiple(String name){
		String body = getBody();
		if(isFormPost()) {
			if(postParameters == null) {
				QueryStringDecoder paramDecoder = new QueryStringDecoder(body, false);
				postParameters = paramDecoder.parameters();
			}
			
			if(postParameters == null || postParameters.isEmpty()) {
				return null;
			} else {
				return postParameters.get(name);
			}
			
		} else if (isJsonPost()) {
			try {
				return Lists.newArrayList(JsonPath.read(body, name).toString());
			} catch (Exception e) {
				//	ignore
				log.error("#RapidRequest# getPostParametersMultiple JsonPath解析失败，jsonPath: {}, body: {}", name, body, e);
			}
		}
		return null;
	}
	
	
	@Override
	public Request build() {
		requestBuilder.setUrl(getFinalUrl());
		return requestBuilder.build();
	}
	
	@Override
	public String getFinalUrl() {
		return modifyScheme + modifyHost + modifyPath;
	}
	

	@Override
	public void setModifyHost(String modifyHost) {
		this.modifyHost = modifyHost;
	}

	@Override
	public String getModifyHost() {
		return modifyHost;		
	}

	@Override
	public void setModifyPath(String modifyPath) {
		this.modifyPath = modifyPath;
	}

	@Override
	public String getModifyPath() {
		return modifyPath;
	}

	@Override
	public void addHeader(CharSequence name, String value) {
		requestBuilder.addHeader(name, value);
	}

	@Override
	public void setHeader(CharSequence name, String value) {
		requestBuilder.setHeader(name, value);
	}

	@Override
	public void addQueryParam(String name, String value) {
		requestBuilder.addQueryParam(name, value);
	}

	@Override
	public void addOrReplaceCookie(org.asynchttpclient.cookie.Cookie cookie) {
		requestBuilder.addOrReplaceCookie(cookie);
	}

	@Override
	public void addFormParam(String name, String value) {
		if(isFormPost()) {
			requestBuilder.addFormParam(name, value);
		}
	}

	@Override
	public void setRequestTimeout(int requestTimeout) {
		requestBuilder.setRequestTimeout(requestTimeout);
	}
	
	public boolean isFormPost() {
		return HttpMethod.POST.equals(method) &&
				(contentType.startsWith(HttpHeaderValues.FORM_DATA.toString()) ||
				contentType.startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString()));
	}
	
	public boolean isJsonPost() {
		return HttpMethod.POST.equals(method) && 
				contentType.startsWith(HttpHeaderValues.APPLICATION_JSON.toString());
	}

}

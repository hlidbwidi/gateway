package com.ruyuan.rapid.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.ServiceConfigurationError;

public final class ServiceLoader<S> implements Iterable<S> {

	//	加载文件目录路径
    private static final String PREFIX = "META-INF/services/";

    // 	The class or interface representing the service being loaded
    //	要加载的接口
    private final Class<S> service;

    // 	The class loader used to locate, load, and instantiate providers
    //	类加载器
    private final ClassLoader loader;

    // The access control context taken when the ServiceLoader is created
    private final AccessControlContext acc;

    // 	Cached providers, in instantiation order
    //	用于缓存已经加载的接口实现类，其中key为实现类的完整类名
    private LinkedHashMap<String,S> providers = new LinkedHashMap<>();

    // 	The current lazy-lookup iterator
    //	用于延迟加载接口的实现类
    private LazyIterator lookupIterator;

    public void reload() {
    	//	清空已经加载的接口实现类
        providers.clear();
        //	实例化LazyIterator进行延迟加载
        lookupIterator = new LazyIterator(service, loader);
    }

    private ServiceLoader(Class<S> svc, ClassLoader cl) {
        service = Objects.requireNonNull(svc, "Service interface cannot be null");
        //	如果cl为空则使用系统类加载器，如果不为空则使用给定的类加载
        loader = (cl == null) ? ClassLoader.getSystemClassLoader() : cl;
        acc = (System.getSecurityManager() != null) ? AccessController.getContext() : null;
        reload();
    }

    private static void fail(Class<?> service, String msg, Throwable cause) throws ServiceConfigurationError
    {
        throw new ServiceConfigurationError(service.getName() + ": " + msg,
                                            cause);
    }

    private static void fail(Class<?> service, String msg) throws ServiceConfigurationError
    {
        throw new ServiceConfigurationError(service.getName() + ": " + msg);
    }

    private static void fail(Class<?> service, URL u, int line, String msg) throws ServiceConfigurationError
    {
        fail(service, u + ":" + line + ": " + msg);
    }

    private int parseLine(Class<?> service, URL u, BufferedReader r, int lc,
                          List<String> names)
        throws IOException, ServiceConfigurationError
    {
        String ln = r.readLine();
        if (ln == null) {
            return -1;
        }
        int ci = ln.indexOf('#');
        if (ci >= 0) ln = ln.substring(0, ci);
        ln = ln.trim();
        int n = ln.length();
        if (n != 0) {
            if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0))
                fail(service, u, lc, "Illegal configuration-file syntax");
            int cp = ln.codePointAt(0);
            if (!Character.isJavaIdentifierStart(cp))
                fail(service, u, lc, "Illegal provider-class name: " + ln);
            for (int i = Character.charCount(cp); i < n; i += Character.charCount(cp)) {
                cp = ln.codePointAt(i);
                if (!Character.isJavaIdentifierPart(cp) && (cp != '.'))
                    fail(service, u, lc, "Illegal provider-class name: " + ln);
            }
            if (!providers.containsKey(ln) && !names.contains(ln))
                names.add(ln);
        }
        return lc + 1;
    }

    private Iterator<String> parse(Class<?> service, URL u)
        throws ServiceConfigurationError
    {
        InputStream in = null;
        BufferedReader r = null;
        ArrayList<String> names = new ArrayList<>();
        try {
            in = u.openStream();
            r = new BufferedReader(new InputStreamReader(in, "utf-8"));
            int lc = 1;
            //	真正解析每一行的核心实现
            while ((lc = parseLine(service, u, r, lc, names)) >= 0);
        } catch (IOException x) {
            fail(service, "Error reading configuration file", x);
        } finally {
            try {
                if (r != null) r.close();
                if (in != null) in.close();
            } catch (IOException y) {
                fail(service, "Error closing configuration file", y);
            }
        }
        return names.iterator();
    }

    /**
     * LazyIterator
     * Private inner class implementing fully-lazy provider lookup
     * @author hezhuo.bai-JiFeng
     * @since 2021年5月26日 下午4:39:41
     */
    private class LazyIterator implements Iterator<S> {
    	
        Class<S> service;
        ClassLoader loader;
        //	加载的URL
        Enumeration<URL> configs = null;
        Iterator<String> pending = null;
        String nextName = null;

        private LazyIterator(Class<S> service, ClassLoader loader) {
            this.service = service;
            this.loader = loader;
        }

        private boolean hasNextService() {
            if (nextName != null) {
                return true;
            }
            if (configs == null) {
                try {
                	//	拼接权限命名
                    String fullName = PREFIX + service.getName();
                    //	如果loader为空则使用系统的ClassLoader对资源进行加载
                    if (loader == null)
                        configs = ClassLoader.getSystemResources(fullName);
                    else
                    //	如果不为空则使用指定传入的loader
                        configs = loader.getResources(fullName);
                } catch (IOException x) {
                    fail(service, "Error locating configuration files", x);
                }
            }
            while ((pending == null) || !pending.hasNext()) {
                if (!configs.hasMoreElements()) {
                    return false;
                }
                pending = parse(service, configs.nextElement());
            }
            nextName = pending.next();
            return true;
        }

        private S nextService() {
            if (!hasNextService())
                throw new NoSuchElementException();
            String cn = nextName;
            nextName = null;
            Class<?> c = null;
            try {
                c = Class.forName(cn, false, loader);
            } catch (ClassNotFoundException x) {
                fail(service,
                     "Provider " + cn + " not found");
            }
            if (!service.isAssignableFrom(c)) {
                fail(service,
                     "Provider " + cn  + " not a subtype");
            }
            try {
            	//	判断对象类型是否转换有误, 若正确则将实例化c对象放入到providers容器中
                S p = service.cast(c.newInstance());
                providers.put(cn, p);
                return p;
            } catch (Throwable x) {
                fail(service,
                     "Provider " + cn + " could not be instantiated",
                     x);
            }
            //	This cannot happen
            throw new Error();          
        }

        public boolean hasNext() {
            if (acc == null) {
                return hasNextService();
            } else {
                PrivilegedAction<Boolean> action = new PrivilegedAction<Boolean>() {
                    public Boolean run() { return hasNextService(); }
                };
                return AccessController.doPrivileged(action, acc);
            }
        }

        public S next() {
            if (acc == null) {
                return nextService();
            } else {
                PrivilegedAction<S> action = new PrivilegedAction<S>() {
                    public S run() { return nextService(); }
                };
                return AccessController.doPrivileged(action, acc);
            }
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    public Iterator<S> iterator() {
        return new Iterator<S>() {

            Iterator<Map.Entry<String,S>> knownProviders
                = providers.entrySet().iterator();

            public boolean hasNext() {
            	//	已经存在providers
                if (knownProviders.hasNext())
                    return true;
                //	调用LazyIterator的hasNext方法
                return lookupIterator.hasNext();
            }

            public S next() {
            	//	已经存在providers
                if (knownProviders.hasNext())
                    return knownProviders.next().getValue();
                //	调用LazyIterator的next方法
                return lookupIterator.next();
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }

        };
    }

    public static <S> ServiceLoader<S> load(Class<S> service, ClassLoader loader)
    {
    	//	返回一个新的ServiceLoader
        return new ServiceLoader<>(service, loader);
    }

    public static <S> ServiceLoader<S> load(Class<S> service) {
    	//	通过当前线程获取到对应的ClassLoader
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return ServiceLoader.load(service, cl);
    }
    
    public static <S> ServiceLoader<S> loadInstalled(Class<S> service) {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        ClassLoader prev = null;
        while (cl != null) {
            prev = cl;
            cl = cl.getParent();
        }
        return ServiceLoader.load(service, prev);
    }

    /**
     * Returns a string describing this service.
     * @return  A descriptive string
     */
    public String toString() {
        return "java.util.ServiceLoader[" + service.getName() + "]";
    }

}

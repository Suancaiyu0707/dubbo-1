/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.*;

/**
 *
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 *
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */

/**
 * 每个拓展接口类型对应一个ExtensionLoader实例
 *
 * ExtensionLoader 考虑到性能和资源的优化，读取拓展配置后，会首先进行缓存。
 * 等到 Dubbo 代码真正用到对应的拓展实现时，进行拓展实现的对象的初始化。并且，初始化完成后，也会进行缓存
 * @param <T>
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    /***
     * 每个拓展类型对应一个ExtensionLoader
     *      key：拓展类型
     *          eg：org.apache.dubbo.common.threadpool.ThreadPool.class
     *      value：ExtensionLoader对象
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();
    /***
     * 对应的拓展类型下的实现类和对应的实例映射关系
     *      key：拓展类型下的实现类的Class对象
     *          eg：org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool.class
     *      value：拓展类型下的实现类的具体实例对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>();
    /***
     * ExtensionLoader 对应的拓展类型
     *      org.apache.dubbo.common.threadpool.ThreadPool.class
     */
    private final Class<?> type;
    /***
     * 用于调用 {@link #injectExtension(Object)} 方法，向拓展对象注入依赖属性。
     *  AdaptiveExtensionFactory$2431
     */
    private final ExtensionFactory objectFactory;
    /**
     * 获得当前拓展类型下的实现类的拓展名称
     *      key: 拓展类的实现类
     *          eg: org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool.class
     *      value: 拓展实现类的名称
     *          eg: fixed
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();
    /**
     * 获得当前拓展类型下的实现类的拓展名称
     *      key: 拓展实现类的名称
     *          eg: fixed
     *      value:拓展类的实现类
     *          eg: org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool.class
     *
     * 不包含如下两种类型：
     *      1. 自适应拓展实现类。例如 AdaptiveExtensionFactory
     *      2. 带唯一参数为拓展接口的构造方法的实现类，或者说拓展 Wrapper 实现类。例如，ProtocolFilterWrapper 。拓展 Wrapper 实现类，会添加到 {@link #cachedWrapperClasses} 中
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();
    /***
     * 缓存当前拓展类下所有带有 Activate 注解的 实现类
     *          KEY：实现类的拓展名称
     *              eg：fixed
     *          VALUE: Activate 对象
     */
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();
    /***
     *  拓展类实现类的实例的缓存
     *      key：拓展实例名
     *          eg: dubbo
     *      value：实例对应的Hodler对象
     *          eg: DubboProtocol
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    /***
     * 当前拓展类型的 自适应的实现实例
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    /**
     * 当前拓展类型的默认的自适应的实现类,一个拓展类型只能有一个默认的自适应的实现类
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    /***
     * 拓展类型对应的默认的实现类型
     */

    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;
    /***
     * 缓存拓展 Wrapper 实现类集合
     * 带唯一参数为拓展接口的构造方法的实现类
     */
    private Set<Class<?>> cachedWrapperClasses;
    /***
     * key: 拓展类实现类的实例名
     * value: 异常
     * 这个是在加载拓展点的配置文件里的实现类的时候出现报错，会把报错的映射关系缓存到exceptions里
     */
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /***
     * 为拓展类型绑定 一个 ExtensionLoader
     * @param type
     * 1、如果当前拓展类型是ExtensionFactory，则对应的ExtensionLoader无需绑定ExtensionFactory。
     * 2、如果当前拓展类型不是ExtensionFactory，则对应的ExtensionLoader需绑定ExtensionFactory，默认实现是AdaptiveExtensionFactory
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null :
                ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /***
     * 根据接口类型，获取对应的拓展类加载器，用于加载拓展类。这步只是为一种拓展类型初始化一个ExtensionLoader并绑定一个ExtensionFactory.
     *      其中ExtensionFactory的目的是用于在初始化拓展实现类实例的时候，为拓展实现类实例注入依赖的拓展点。
     * 接口的拓展类加载器会放到内存里(这一步会是针对CLASSLOADER的缓存)
     * @param type
     * @param <T>
     * @return
     * 1、对type类型进行相应的校验: 类型、是否接口、是否添加了SPI注解
     * 2、检查内存EXTENSION_LOADERS缓存里是否已经存在该拓展类型type对应的类加载器 ExtensionLoader
     * 3、本地内存EXTENSION_LOADERS里会为每一种拓展类型type维护一个独立的 ExtensionLoader实例
     *
     * 这里要注意一个特殊的拓展类型：ExtensionFactory.class
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            //本地内存EXTENSION_LOADERS里会为每一种拓展类型type维护一个独立的 ExtensionLoader实例
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only

    /**
     * 清空缓存里 type对应的ExtensionLoader的缓存
     * @param type
     */
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    /***
     * 销毁所有的实例
     */
    public static void destroyAll() {
        EXTENSION_INSTANCES.forEach((_type, instance) -> {
            if (instance instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) instance;
                try {
                    lifecycle.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + lifecycle, e);
                }
            }
        });
    }

    /***
     * 根据ExtensionLoader.class获得相应的类加载器
     * @return
     */
    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    /***
     * 根据拓展类型的实现类的实例获得 拓展实现类实例的拓展名称，比如dubbo
     * @param extensionInstance
     * @return
     */
    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    /**
     * 根据拓展类型的实现类的类型获得 拓展实现类的拓展名称，比如dubbo
     * @param extensionClass
     * @return
     */
    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *  代码调用： List<RouterFactory> extensionFactories = ExtensionLoader.getExtensionLoader(RouterFactory.class).getActivateExtension(url, "router");
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     *      根据url上的索引key，来激活相应的Activate实现类
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *代码调用： List<RouterFactory> extensionFactories = ExtensionLoader.getExtensionLoader(RouterFactory.class).getActivateExtension(url, "router");
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     * 根据url上的属性key，来激活相应的Activate实现类
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *  代码调用： List<RouterFactory> extensionFactories = ExtensionLoader.getExtensionLoader(RouterFactory.class).getActivateExtension(url, "router");
     * @param url   请求的url
     * @param key   用于从请求url获取指定key的值，再根据这个值获取对应的拓展类 eg: service.filter
     * @param group group eg： provider
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     * 1、从url中获取指定的key的值
     * 2、根据key的值获得激活的拓展类
     */

    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        //根据key的值获得激活的拓展类
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url
     * @param values  point names
     * @param group   eg: provider
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     *  代码调用： List<RouterFactory> extensionFactories = ExtensionLoader.getExtensionLoader(RouterFactory.class).getActivateExtension(url, "router");
     * 根据url上的属性key，来激活相应的Activate实现类
     *      像RouterFactory的一些拓展实现类就会带有Activate这个注解，这些实现类有：TagRouterFactory、ServiceRouterFactory、MockRouterFactory。这些拓展实现类在加载拓展点的时候同时也就会被添加到cachedActivates中
     *
     *  如果values不包含 '-default'
     *      1、遍历所有的values
     *      2、检查每一个Activate的group、value属性值
     *      3、判断group和values是否匹配
     *      4、匹配出来的Activate类标根据order属性进行排序
     *      5、根据适配的名称获得拓展实现类Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<>();
        List<String> names = values == null ? new ArrayList<>(0) : Arrays.asList(values);
        //遍历所有的values
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {//如果names不包含'-default'
            getExtensionClasses();//获得当前拓展类型所有的拓展类
            //在从配置文件加载拓展点的实现类的时候，会判断拓展实现类是否持有注解Activate，如果有的话，会被缓存到cachedActivates里。
            // 遍历当前拓展类下的所有普通拓展类 Activate注解的实现类
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();//Activate的名称
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;
                //分析Activate注解的value和group
                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                //判断group、name是否匹配
                if (isMatchGroup(group, activateGroup)
                        && !names.contains(name)
                        && !names.contains(REMOVE_VALUE_PREFIX + name)
                        && isActive(activateValue, url)) {
                    //获得拓展类实现类的实例
                    exts.add(getExtension(name));
                }
            }
            //进行排序
            exts.sort(ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                if (DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    usrs.add(getExtension(name));
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            // @Active(value="key1:value1, key2:value2")
            String keyValue = null;
            if (key.contains(":")) {
                String[] arr = key.split(":");
                key = arr[0];
                keyValue = arr[1];
            }

            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ((keyValue != null && keyValue.equals(v)) || (keyValue == null && ConfigUtils.isNotEmpty(v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     * 根据拓展类型名称对应的实现类的实例
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        //
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    /***
     * 根据ExtensionLoader的本地缓存cachedInstances是否包含name对应的实现类类型的实例
     * @param name eg：dubbo
     * @return
     * 1、判断ExtensionLoader的本地缓存cachedInstances是否包含name对应的实现类类型的实例。
     *      如果内存里有的话，直接返回。
     *      没有的话，则创建一个新的new Holder关联 name。注意，新建的Holder里的值是null
     */
    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public List<T> getLoadedExtensionInstances() {
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

//    public T getPrioritizedExtensionInstance() {
//        Set<String> supported = getSupportedExtensions();
//
//        Set<T> instances = new HashSet<>();
//        Set<T> prioritized = new HashSet<>();
//        for (String s : supported) {
//
//        }
//
//    }

    /***
     *      根据拓展实现类的名称获得相应的拓展实现类的实例（这里我们要注意，本身每一个ExtensionLoader都绑定了一种拓展类型）
     *      1、判断name是否为空，如果name为true,则返回默认的拓展实现类实现（通过类似@SPI("netty")定义）
     *      2、判断ExtensionLoader的本地缓存cachedInstances是否包含name对应的实现类类型的实例
     *      3、如果name没有对应的实例，则根据name对应的拓展实现类创建并绑定一个Holder对象，同时为Holder绑定一个拓展实现类实例
     * 根据name从cachedInstances集合中获取配置的拓展类
     *        0 = {ConcurrentHashMap$MapEntry@3976} "registry" ->
     *      * 1 = {ConcurrentHashMap$MapEntry@3977} "injvm" ->
     *      * 2 = {ConcurrentHashMap$MapEntry@3978} "dubbo" ->
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {//name：dubbo
        if (StringUtils.isEmpty(name)) {//判断name是否为空
            throw new IllegalArgumentException("Extension name == null");
        }
        //如果name为true,则返回默认的拓展实现类实现（通过类似@SPI("netty")定义）
        if ("true".equals(name)) {
            return getDefaultExtension();
        }//本地内存里为每一类name维护一个Holder
        final Holder<Object> holder = getOrCreateHolder(name);//判断ExtensionLoader的本地缓存cachedInstances是否包含name对应的实现类类型的实例
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);//据name对应的Class创建一个实例并返回
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name 拓展类实例名称，例如dubbo
     * @return non-null
     * 根据name获得对应的拓展类实例，如果没有的话，则获取默认的拓展类实例
     * 1、如果存在name对应的拓展点实现类，则返回这个实现类的实例
     * 2、如果不存在name对应的拓展点实现类，则返回默认的实现类的实例。这个默认的是在@SPI注解里指定的额。比如：@SPI("netty")
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name)  ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     * 获得默认的拓展类实例，如果有的话，则直接返回，这个默认的是在@SPI注解里指定的额。比如：@SPI("netty")
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    /***
     * 判断拓展类实例是否存在
     * @param name 拓展类实例名称，例如dubbo
     * @return
     * 如果不存在name对应的拓展点实现类，则返回false
     */
    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }
    //从ExtensionLoader获得这个拓展类的所有实现类
    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();//从ExtensionLoader获得这个拓展类的所有实现类
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    /**
     * 获得可用的拓展类实例集合
     * @return
     * 遍历每个拓展点的所有实现类，并获取每个拓展点实现类的实例列表
     */
    public Set<T> getSupportedExtensionInstances() {
        Set<T> instances = new HashSet<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        return instances;
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     * 获得默认的拓展点实现类的名称
     * 这个默认的是在@SPI注解里指定的额。比如：@SPI("netty")
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }
        //判断class是否持有Adaptive注解
        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            //拓展类的实例名称不能重复
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            //如果持有Adaptive注解，则判断是否已经有自适应实现了
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     * 根据拓展点的实现类名称，替换掉名称对应的拓展点实现类
     * 注意：
     *      替换的clazz必须也是实现了当前拓展点的一个实现类
     *      替换的时候要保证整个拓展点里只能有一个持有Adaptive注解的实现类
     *      所谓的替换，其实就是更新缓存中的映射关系
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /***
     * 通过ExtensionLoader.getAdaptiveExtension获得当前拓展类对应的 adaptive类型拓展对象（这一步会对Adaptive 实例进行缓存）
     *      一个拓展点只能有一个拓展实现类持有@Adaptive注解。如果有这样的拓展实现类，则直接返回它对应的实例。
     *      如果一个拓展点并未有拓展实现类持有@Adaptive注解，则框架会为拓展点自动生成一个实现类拓展点接口的实现类，格式：type$Adaptive,会通过拓展接口自动生成实现类字符串
     *          1、为接口每个有Adaptive注解的方法生成默认的实现：
     *                  每个有Adaptive注解的方法必须带有URL类型的参数
     *          2、没有Adaptive注解的方法生成的实现里只有一行内容：
     *                "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n"
     *          3、这样每个默认实现会从URL中提取Adaptive参数值，并以此为依据动态加载拓展点
     *
     * @return
     * 1、先双重检查当前拓展类型是否已存在自适应的拓展实现类的实例：AdaptiveExtension
     *      在加载拓展点的实现类的时候，会检查每个拓展实现类是否添加了Adaptive注解：
     *          如果有的话，会把这个拓展实现类缓存到cachedAdaptiveClass中，后续会根据cachedAdaptiveClass创建一个自使用的拓展实现类的实例并缓存到cachedAdaptiveInstance中。
     *          如果没有的话，则会根据拓展点接口根据字节码生成技术创建一个默认的拓展点的自适应的实现类，并缓存到cachedAdaptiveClass中后续会根据cachedAdaptiveClass创建一个自使用的拓展实现类的实例并缓存到cachedAdaptiveInstance中。
     *          如果在上面两步生成实例失败，则会把失败异常保存到createAdaptiveInstanceError中，这样就没必要每次都去尝试 实现类自适应的拓展类实例了。
     * 2、如果缓存里没有，则新建一个自适应的拓展类实现
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                        createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {//创建一个自适应的拓展实例

                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    /***
     * 查找 拓展实现类对应的异常信息
     * @param name 拓展实现类名称
     * @return
     */
    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }
    /***
     *      根据拓展点实现类的名称name对找出对应的拓展实现类，并创建实例（保证了拓展类型下的每种实现类只需要一个实例对象即可）
     *      1、根据name查询ExtensionLoader实例中的本地缓存cachedClasses集合，并获取name对应的拓展类型的实现类的Class实例。如果不存在或抛出异常
     *      2、根据Class对象查询ExtensionLoader实例中的本地缓存EXTENSION_INSTANCES，如果存放拓展类型的实现实例的EXTENSION_INSTANCES已经存在相应的实例，则直接返回该实例，否则就新建一个实例
     *      3、通过ExtensionFactory为实例对象注射初始值
     *      4、如果拓展点下有包装类实现，则实现类的实例包装成一个包装对象
     *      5、循环遍历定义了包含拓展类型的构造方法的实现类，如果存在这样的构造方法，则会优先级使用带有接口类型的构造方法来实例化对象
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        /***
         * 找出对应的拓展实现类
         * 查找本地内存classes，是否已加载对应拓展类型所有的实现类的Class对象
         * @return
         * 1、如果cachedClasses不为空，则表示该拓展类型已加载过，那么就无需在加载了，直接返回
         * 2、如果cachedClasses为空，则根据拓展类型去相应的目录下加载所有的实现类，具体目录：
         *      META-INF/services/、META-INF/dubbo/internal/、META-INF/dubbo/
         */
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            //检查name类型是否存在对应的实例，保证单例
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                //新建一个实例 并放到本地内存 EXTENSION_INSTANCES 中
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);//通过ExtensionFactory为实例对象注入初始值
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            //如果拓展点下有包装类实现，则实现类的实例包装成一个包装对象
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            initExtension(instance);//
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /***
     *向拓展对象注入依赖属性
     * @param instance
     * @return
     * 1、遍历实例里所有的方法
     * 2、获得方法的参数的属性的类型
     * 3、获得方法名
     * 4、获得属性值
     * 5、设置属性值
     */
    private T injectExtension(T instance) {
        //表示是一个ExtensionFactory实例
        if (objectFactory == null) {
            return instance;
        }

        try {
            //遍历实例里所有的方法
            for (Method method : instance.getClass().getMethods()) {
                if (!isSetter(method)) {//如果方法不是set方法，则跳过
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 */
                if (method.getAnnotation(DisableInject.class) != null) {//如果方法上带有 DisableInject 注解，则跳过
                    continue;
                }
                // 获得属性的类型,也就是拓展类型 org.apache.dubbo.rpc.Protocol
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {//如果方法是基本类型,则跳过
                    continue;
                }

                try {
                    // 获得属性的name，也就是拓展类型对应的实现类的name,比如 protocol
                    String property = getSetterProperty(method);
                    // 获得属性值,也就是对应的bean实例
                    Object object = objectFactory.getExtension(pt, property);
                    // 设置属性值
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                            + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }
    //初始化拓展类实例
    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {//如果这个拓展类是有生命周期的
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     *  获得属性
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /***
     * 查找本地内存classes，是否已加载对应拓展类型所有的实现类的Class对象。没有的话加载这个拓展类的所有的实现类
     * @return
     * 1、如果cachedClasses不为空，则表示该拓展类型已加载过，那么就无需在加载了，直接返回
     * 2、如果cachedClasses为空，则根据拓展类型去相应的目录下加载所有的实现类，具体目录：
     *      META-INF/services/、META-INF/dubbo/internal/、META-INF/dubbo/
     *
     *  注意：这一步并未实例化对象
     */
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     *
     * 1、判断拓展类型里是否设置了默认值：比如@SPI("netty")
     * 2、根据拓展类型加载指定目录下的配置文件，查找拓展的实现：
     *      META-INF/services/
     *      META-INF/dubbo/internal/
     *      META-INF/dubbo/
     * */
    private Map<String, Class<?>> loadExtensionClasses() {
        //判断拓展类型里是否设置了默认值：比如@SPI("netty")
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();
        // internal extension load from ExtensionLoader's ClassLoader first
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName(), true);
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"), true);

        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     * 1、检查拓展类型是否有SPI注解
     * 2、判断拓展类型里是否设置了默认值：比如@SPI("netty")
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    /***
     * 根据目录和拓展类型获得所有的拓展实现类并缓存起来(此时还未加载)
     * @param extensionClasses 缓存map
     * @param dir  查找目录
     *             eg: META-INF/services/
     * @param type 拓展类型
     *             eg: org.apache.dubbo.remoting.Transporter
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        loadDirectory(extensionClasses, dir, type, false);
    }

    /**
     *根据目录和拓展类型获得所有的拓展实现类并缓存起来(此时还未加载)
     * @param extensionClasses  用于缓存接收 拓展类的实现类型
     * @param dir 查找路径，
     *            eg: META-INF/services/
     * @param type 拓展类型名称:
     *            eg: org.apache.dubbo.remoting.Transporter
     * @param extensionLoaderClassLoaderFirst 默认是true
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type, boolean extensionLoaderClassLoaderFirst) {
        //获得指定的文件路径 META-INF/dubbo/internal/org.apache.dubbo.common.extension.ExtensionFactory
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            //根据ExtensionLoader从当前线程中获得相应的类加载器
            ClassLoader classLoader = findClassLoader();
            
            // try to load from ExtensionLoader's ClassLoader first
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }
            
            if(urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    //根据文件路径缓存相应的实现类
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /***
     * 获得拓展类型的实现
     * @param extensionClasses 拓展类型
     *          eg：
     * @param classLoader 类加载器
     * @param resourceURL 加载路径
     *          eg：META-INF/dubbo/internal/org.apache.dubbo.common.extension.ExtensionFactory
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /***
     * 获取拓展类型的实现类的class对象
     * @param extensionClasses 拓展类型
     * @param resourceURL
     * @param clazz 拓展类型对应的具体实现类  eg: class org.apache.dubbo.common.extension.factory.SpiExtensionFactory
     * @param name eg：spi
     * @throws NoSuchMethodException
     * 1、如果实现类持有 Adaptive 注解，如果有的话，作为当前拓展类型的默认的自适应的实现类，则该实现类设置为cachedAdaptiveClass
     * 2、如果当前实现类存在以拓展接口作为参数的构造函数，则表示是一个包装类。则添加到 cachedWrapperClasses里
     * 3、如果是一个普通的拓展实现类，则添加到缓存里的cachedNames，同时也会添加到收集的集合 extensionClasses 里
     *      普通的拓展实现类，必须要有一个无参数的构造函数。
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {//判断实现类是否实现了type接口
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        //判断实现类是否持有 Adaptive 注解，如果有的话，作为当前拓展类型的默认的自适应的实现类 ：cachedAdaptiveClass
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz);
        } else if (isWrapperClass(clazz)) {//判断当前实现类是否是一个包装类（存在以接口作为参数的构造函数）
            cacheWrapperClass(clazz);
        } else {
            clazz.getConstructor();//获得当前实现类的构造函数
            if (StringUtils.isEmpty(name)) {
                //获得实现类的名称，可通过Extension注解设置值，没有设置的话用类名
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                //默认缓存激活的拓展实现类，会判断是否有Activate注解
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {//{"spi"}
                    cacheName(clazz, n);
                    saveInExtensionClass(extensionClasses, clazz, n);
                }
            }
        }
    }

    /**
     * cache name
     * 缓存当前拓展类型下的实现类的拓展名称
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     * 将拓展类的实现类的class对象放到extensionClasses，已存在，则报错
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name) {
        Class<?> c = extensionClasses.get(name);
        if (c == null) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {//多个实现类的映射名称是重复的
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     * 检查当前实现类是否持有Activate注解，有的话缓存到 cachedActivates 中
     * 1、判断是否配置了 Activate 注解，有的化，则缓存到cachedActivates
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        //获得 Activate 注解
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {//如果持有Activate注解，则缓存这个激活类
            cachedActivates.put(name, activate);
        } else {//如果没有，判断是否有
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     * 缓存 Adaptive的实现类的class对象
     */
    private void cacheAdaptiveClass(Class<?> clazz) {
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getName()
                    + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     * 缓存当前拓展类型对应的包装类
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     * 判断当前实现类是否是一个包装类（存在以接口作为参数的构造函数）
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            //是否存在以接口作为参数的构造函数
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    /**
     * 创建自适应的Adaptive 拓展对象实例
     * 1、获得 拓展类型的 Adaptive 实现类的实例，如果没有实现类配置了@Adaptive，则要根据拓展类型编译生成一个默认的 type$Adaptive 实现类型，并根据这个类型创建一个实例
     * 2、为拓展类型的Adaptive实现类 注入依赖属性
     */
    private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());//返回默认的自适应类的实例
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /***
     *
     * @return
     * 1、根据当前的拓展类型通过Dubbo spi到指定路径下加载相应的实现类并存放到缓存里 ，如果已加载的到，就无需再加载：
     *       META-INF/services/、META-INF/dubbo/internal/、META-INF/dubbo/
     * 2、通过步骤1加载后，我们去检查是否有实现类配置了@Adaptive
     *      如果有实现类配置了@Adaptive，则作为默认的Adaptive实现，会被缓存到 cachedAdaptiveClass里，并返回
     *      如果没有实现类配置了@Adaptive，则要根据拓展类型编译生成一个默认的 type$Adaptive 实现类型
     */
    private Class<?> getAdaptiveExtensionClass() {
        getExtensionClasses();//加载拓展实现类
        if (cachedAdaptiveClass != null) {//检查这个拓展类是否有默认的自适应类，有的话，直接返回
            return cachedAdaptiveClass;
        }//没有的话，采用字节码增强，生成一个自适应的实现类cachedAdaptiveClass
             /* 1、为接口每个有Adaptive注解的方法生成默认的实现：
              *        每个有Adaptive注解的方法必须带有URL类型的参数
              * 2、没有Adaptive注解的方法生成的实现里只有一行内容：
              *      "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n"
              * 3、这样每个默认实现会从URL中提取Adaptive参数值，并以此为依据动态加载拓展点
              *
              */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /***
     * 通过拓展接口类型生成字节码，并反编译生成 默认的 type$Adaptive 实现类型
     * @return
     *
     */
    private Class<?> createAdaptiveExtensionClass() {
        /***
         * 通过拓展接口自动生成实现类字符串
         * 1、为接口每个有Adaptive注解的方法生成默认的实现：
         *        每个有Adaptive注解的方法必须带有URL类型的参数
         * 2、没有Adaptive注解的方法生成的实现里只有一行内容：
         *      "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n"
         * 3、这样每个默认实现会从URL中提取Adaptive参数值，并以此为依据动态加载拓展点
         */
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        ClassLoader classLoader = findClassLoader();
        //不同的编译器会把字符串编码成自适应的类并返回
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        //反编译生成 默认的 type$Adaptive 实现类型
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}

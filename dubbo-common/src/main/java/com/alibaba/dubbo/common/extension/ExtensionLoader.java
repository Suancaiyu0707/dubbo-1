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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    /***每种拓展类型一个ExtensionLoader实例
     * 0 = {ConcurrentHashMap$MapEntry@3717} "interface com.alibaba.dubbo.validation.Validation" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.validation.Validation]"
     * 1 = {ConcurrentHashMap$MapEntry@3718} "interface com.alibaba.dubbo.remoting.telnet.TelnetHandler" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.remoting.telnet.TelnetHandler]"
     * 2 = {ConcurrentHashMap$MapEntry@3719} "interface com.alibaba.dubbo.rpc.ExporterListener" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.rpc.ExporterListener]"
     * 3 = {ConcurrentHashMap$MapEntry@3720} "interface com.alibaba.dubbo.rpc.cluster.Cluster" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.rpc.cluster.Cluster]"
     * 4 = {ConcurrentHashMap$MapEntry@3721} "interface com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory]"
     * 5 = {ConcurrentHashMap$MapEntry@3722} "interface com.alibaba.dubbo.monitor.MonitorFactory" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.monitor.MonitorFactory]"
     * 6 = {ConcurrentHashMap$MapEntry@3723} "interface com.alibaba.dubbo.rpc.Protocol" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.rpc.Protocol]"
     * 7 = {ConcurrentHashMap$MapEntry@3724} "interface com.alibaba.dubbo.cache.CacheFactory" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.cache.CacheFactory]"
     * 8 = {ConcurrentHashMap$MapEntry@3725} "interface com.alibaba.dubbo.common.extension.ExtensionFactory" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.common.extension.ExtensionFactory]"
     * 9 = {ConcurrentHashMap$MapEntry@3726} "interface com.alibaba.dubbo.rpc.Filter" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.rpc.Filter]"
     * 10 = {ConcurrentHashMap$MapEntry@3727} "interface com.alibaba.dubbo.common.compiler.Compiler" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.common.compiler.Compiler]"
     * 11 = {ConcurrentHashMap$MapEntry@3728} "interface com.alibaba.dubbo.rpc.ProxyFactory" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.rpc.ProxyFactory]"
     * 12 = {ConcurrentHashMap$MapEntry@3729} "interface com.alibaba.dubbo.registry.RegistryFactory" -> "com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.registry.RegistryFactory]"
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();
    /***
     * 0 = {ConcurrentHashMap$MapEntry@3658} "class com.alibaba.dubbo.rpc.filter.TokenFilter" ->
     * 1 = {ConcurrentHashMap$MapEntry@3659} "class com.alibaba.dubbo.rpc.filter.EchoFilter" ->
     * 2 = {ConcurrentHashMap$MapEntry@3660} "class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory" ->
     * 3 = {ConcurrentHashMap$MapEntry@3661} "class com.alibaba.dubbo.common.compiler.support.JavassistCompiler" ->
     * 4 = {ConcurrentHashMap$MapEntry@3662} "class com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol" ->
     * 5 = {ConcurrentHashMap$MapEntry@3663} "class com.alibaba.dubbo.rpc.filter.ContextFilter" ->
     * 6 = {ConcurrentHashMap$MapEntry@3664} "class com.alibaba.dubbo.registry.integration.RegistryProtocol" ->
     * 7 = {ConcurrentHashMap$MapEntry@3665} "class com.alibaba.dubbo.monitor.support.MonitorFilter" ->
     * 8 = {ConcurrentHashMap$MapEntry@3666} "class com.alibaba.dubbo.rpc.filter.AccessLogFilter" ->
     * 9 = {ConcurrentHashMap$MapEntry@3667} "class com.alibaba.dubbo.rpc.protocol.dubbo.filter.TraceFilter" ->
     * 10 = {ConcurrentHashMap$MapEntry@3668} "class com.alibaba.dubbo.rpc.filter.ExceptionFilter" ->
     * 11 = {ConcurrentHashMap$MapEntry@3669} "class com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol" ->
     * 12 = {ConcurrentHashMap$MapEntry@3670} "class com.alibaba.dubbo.rpc.filter.ClassLoaderFilter" ->
     * 13 = {ConcurrentHashMap$MapEntry@3671} "class com.alibaba.dubbo.rpc.filter.GenericFilter" ->
     * 14 = {ConcurrentHashMap$MapEntry@3672} "class com.alibaba.dubbo.rpc.proxy.javassist.JavassistProxyFactory" ->
     * 15 = {ConcurrentHashMap$MapEntry@3673} "class com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter" ->
     * 16 = {ConcurrentHashMap$MapEntry@3674} "class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory" ->
     * 17 = {ConcurrentHashMap$MapEntry@3675} "class com.alibaba.dubbo.cache.filter.CacheFilter" ->
     * 18 = {ConcurrentHashMap$MapEntry@3676} "class com.alibaba.dubbo.rpc.filter.TimeoutFilter" ->
     * 19 = {ConcurrentHashMap$MapEntry@3677} "class com.alibaba.dubbo.validation.filter.ValidationFilter" ->
     *
     *
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================

    private final Class<?> type;
    /***用来负责根据name从cachedClasses和EXTENSION_INSTANCES适配出拓展类型对应的实现类的实例
     * 0 = {SpiExtensionFactory@3074}
     * 1 = {SpringExtensionFactory@3813}
     */
    private final com.alibaba.dubbo.common.extension.ExtensionFactory objectFactory;
    /***
     * 0 = {ConcurrentHashMap$MapEntry@3816} "class com.alibaba.dubbo.rpc.support.MockProtocol" -> "mock"
     * 1 = {ConcurrentHashMap$MapEntry@3817} "class com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol" -> "injvm"
     * 2 = {ConcurrentHashMap$MapEntry@3818} "class com.alibaba.dubbo.registry.integration.RegistryProtocol" -> "registry"
     * 3 = {ConcurrentHashMap$MapEntry@3819} "class com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol" -> "dubbo"
     * 或
     * 0 = {ConcurrentHashMap$MapEntry@3892} "class com.alibaba.dubbo.rpc.cluster.support.FailfastCluster" -> "failfast"
     * 1 = {ConcurrentHashMap$MapEntry@3893} "class com.alibaba.dubbo.rpc.cluster.support.ForkingCluster" -> "forking"
     * 2 = {ConcurrentHashMap$MapEntry@3894} "class com.alibaba.dubbo.rpc.cluster.support.MergeableCluster" -> "mergeable"
     * 3 = {ConcurrentHashMap$MapEntry@3895} "class com.alibaba.dubbo.rpc.cluster.support.FailbackCluster" -> "failback"
     * 4 = {ConcurrentHashMap$MapEntry@3896} "class com.alibaba.dubbo.rpc.cluster.support.BroadcastCluster" -> "broadcast"
     * 5 = {ConcurrentHashMap$MapEntry@3897} "class com.alibaba.dubbo.rpc.cluster.support.FailoverCluster" -> "failover"
     * 6 = {ConcurrentHashMap$MapEntry@3898} "class com.alibaba.dubbo.rpc.cluster.support.FailsafeCluster" -> "failsafe"
     * 7 = {ConcurrentHashMap$MapEntry@3899} "class com.alibaba.dubbo.rpc.cluster.support.AvailableCluster" -> "available"
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();
    /***
     * 0 = {HashMap$Node@4359} "registry" -> "class com.alibaba.dubbo.registry.integration.RegistryProtocol"
     * 1 = {HashMap$Node@4360} "injvm" -> "class com.alibaba.dubbo.rpc.protocol.injvm.InjvmProtocol"
     * 2 = {HashMap$Node@4361} "dubbo" -> "class com.alibaba.dubbo.rpc.protocol.dubbo.DubboProtocol"
     * 3 = {HashMap$Node@4362} "mock" -> "class com.alibaba.dubbo.rpc.support.MockProtocol"
     * 或
     * 0 = {HashMap$Node@4640} "jdk" -> "class com.alibaba.dubbo.common.compiler.support.JdkCompiler"
     * 1 = {HashMap$Node@4641} "javassist" -> "class com.alibaba.dubbo.common.compiler.support.JavassistCompiler"
     *
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();
    /***
     * 这个类
     */
    private final Map<String, com.alibaba.dubbo.common.extension.Activate> cachedActivates = new ConcurrentHashMap<String, com.alibaba.dubbo.common.extension.Activate>();
    /***
     * 0 = {ConcurrentHashMap$MapEntry@3976} "registry" ->
     * 1 = {ConcurrentHashMap$MapEntry@3977} "injvm" ->
     * 2 = {ConcurrentHashMap$MapEntry@3978} "dubbo" ->
     *  或
     * 0= "javassist" ->
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    /**
     * Protocol$Adaptive
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    /***适配器类主要是否则根据拓展类型和实现类类型返回一个具体的实现类
     * com.alibaba.dubbo.rpc.Protocol$Adaptive
     * 或
     * class com.alibaba.dubbo.common.compiler.support.AdaptiveCompiler
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    /***
     * dubbo
     *
     */
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;
    /***
     * 0 = {Class@1661} "class com.alibaba.dubbo.rpc.protocol.ProtocolFilterWrapper"
     * 1 = {Class@1662} "class com.alibaba.dubbo.rpc.protocol.ProtocolListenerWrapper"
     * 这里存放的实现类都是定义了一个只包含接口作为参数的构造方法的实现类(优先级比无参的更高)
     */
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    /***
     *
     * 根据接口类型构建拓展类型的加载类
     *      1、拓展类型的加载类绑定接口类型
     *      2、判断类型type == ExtensionFactory.class
     *          true: objectFactory 为null,ExtensionFactory关联的ExtensionLoader相当于是所有其它拓展类型的ExtensionLoader的基类
     *          false: objectFactory=
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        /****
         *
         *
         * type= protocol
         * objectFactory=AdaptiveExtensionFactory实例：
         * factories:
         *      0 = {SpiExtensionFactory@1754}
         *      1 = {SpringExtensionFactory@1755}
         */
        objectFactory = (type == com.alibaba.dubbo.common.extension.ExtensionFactory.class ? null :
                ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.extension.ExtensionFactory.class)
                        .getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        //只接受使用@SPI注解注释的接口类型
        return type.isAnnotationPresent(com.alibaba.dubbo.common.extension.SPI.class);
    }

    @SuppressWarnings("unchecked")
    /***
     * 根据接口类型获取拓展的加载类，每种接口类型只会创建一个加载类，并会存放到本地缓存当中
     *      1、类型的校验:不能为空，是接口类型
     *      2、检查接口是否配置SPI注解(所以说要自定义一个拓展类型的话，接口必须配置注解SPI)
     *      3、根据接口类型查看本地缓存集合EXTENSION_LOADERS，避免对同一类型重复创建拓展类型的加载类
     *      4、创建特定接口类型的拓展类型的加载类
     */
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        //只接受使用@SPI注解注释的接口类型
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + com.alibaba.dubbo.common.extension.SPI.class.getSimpleName() + " Annotation!");
        }
        //先从静态缓存中获取该类型
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {//静态缓存中不存在，则new一个实例,保证每一个扩展，dubbo中只会有一个对应的ExtensionLoader实例
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    /***
     * 根据拓展类的实现类的类型找出拓展实现类的名称
     *      * 0 = {ConcurrentHashMap$MapEntry@3892} "class com.alibaba.dubbo.rpc.cluster.support.FailfastCluster" -> "failfast"
     *      * 1 = {ConcurrentHashMap$MapEntry@3893} "class com.alibaba.dubbo.rpc.cluster.support.ForkingCluster" -> "forking"
     *      * 2 = {ConcurrentHashMap$MapEntry@3894} "class com.alibaba.dubbo.rpc.cluster.support.MergeableCluster" -> "mergeable"
     *      * 3 = {ConcurrentHashMap$MapEntry@3895} "class com.alibaba.dubbo.rpc.cluster.support.FailbackCluster" -> "failback"
     *      * 4 = {ConcurrentHashMap$MapEntry@3896} "class com.alibaba.dubbo.rpc.cluster.support.BroadcastCluster" -> "broadcast"
     *      * 5 = {ConcurrentHashMap$MapEntry@3897} "class com.alibaba.dubbo.rpc.cluster.support.FailoverCluster" -> "failover"
     *      * 6 = {ConcurrentHashMap$MapEntry@3898} "class com.alibaba.dubbo.rpc.cluster.support.FailsafeCluster" -> "failsafe"
     *      * 7 = {ConcurrentHashMap$MapEntry@3899} "class com.alibaba.dubbo.rpc.cluster.support.AvailableCluster" -> "available"
     * @param extensionClass
     * @return
     */
    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     *
     * 根据key从url中拿到对应的值，并获取有效的拓展类实例
     * 0 = {ConcurrentHashMap$MapEntry@3976} "registry" ->
     *      * 1 = {ConcurrentHashMap$MapEntry@3977} "injvm" ->
     *      * 2 = {ConcurrentHashMap$MapEntry@3978} "dubbo" ->
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     * * 直接根据值获取有效的拓展类实例
     *      * 0 = {ConcurrentHashMap$MapEntry@3976} "registry" ->
     *      *      * 1 = {ConcurrentHashMap$MapEntry@3977} "injvm" ->
     *      *      * 2 = {ConcurrentHashMap$MapEntry@3978} "dubbo" ->
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    /***
     *
     * @param url ：injvm://127.0.0.1/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.0.102&bind.port=20880&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=19808&qos.port=22222&side=provider&timestamp=1527944503518
     * @param key ：service.filter
     * @param group ：provider
     * @return
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    /***
     * 根据url地址查询各种拓展类的实例
     * @param url
     * @param values
     * @param group
     * @return
     *        0 = {ConcurrentHashMap$MapEntry@3976} "registry" ->
     *      * 1 = {ConcurrentHashMap$MapEntry@3977} "injvm" ->
     *      * 2 = {ConcurrentHashMap$MapEntry@3978} "dubbo" ->
     */
    /***
     *
     *  获得自动激活的拓展类
     *  1、如果values中是否包含-default
     *      1-1、加载拓展类型的所有实现类
     *      1-2、迭代配置了Activate注解的拓展类型的实现类
     *      1-3、判断Activate注解类的组名是否包含了group
     *      1-4、根据这个Activate注解的拓展类型的实现类的name名称找出拓展类的实现类
     *      1-5、如果查询的values不存在这个Activate的name,但是url中拼装了对应的名称，则这个拓展类的实现类符合条件
     *  2、根据values的元素查找拓展类，查找所有符合的拓展实现类，values中的元素不能以'-'开头，也不能是default
     *  3、返回拓展实现类的列表
     * @param url ：injvm://127.0.0.1/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.0.102&bind.port=20880&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=19808&qos.port=22222&side=provider&timestamp=1527944503518
     * @param values ：null
     * @param group ：provider
     * @return
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {//判断是否name包含'-default'
            getExtensionClasses();//获取拓展类型下的所有拓展类
            //这一段一般不会走
            //如果一个拓展类，配置了Activate注解，则要检查这个Activate注解的group值和参数group是否匹配，只有匹配了才会继续查找拓展类
            for (Map.Entry<String, com.alibaba.dubbo.common.extension.Activate> entry : cachedActivates.entrySet()) {//循环配置了Activate注解的类
                String name = entry.getKey();
                com.alibaba.dubbo.common.extension.Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) {//匹配group和Activate的组属性的值
                    T ext = getExtension(name);//最终还是根据name从getExtension来获取，比如CacheFilter时，根据name=lru找出 lru=com.alibaba.dubbo.cache.support.lru.LruCacheFactory
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    /***
     * 判断目标groups是否包含group
     * @param group
     * @param groups
     * @return
     */
    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
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

    private boolean isActive(com.alibaba.dubbo.common.extension.Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys == null || keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
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
     */
    @SuppressWarnings("unchecked")
    /***
     * 根据拓展类的名称获取拓展类
     */
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    /***
     * 获取所有的拓展类集合
     * @return
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    /***获得普通拓展类，根据name返回拓展类型对应的实现类的实例
     *      1、判断name是否为空
     *      2、判断ExtensionLoader的本地缓存cachedInstances是否包含name对应的实现类类型的实例
     *      3、如果name没有对应的实例，则根据name对应的Class创建一个实例并返回
     * 根据name从cachedInstances集合中获取配置的拓展类
     *        0 = {ConcurrentHashMap$MapEntry@3976} "registry" ->
     *      * 1 = {ConcurrentHashMap$MapEntry@3977} "injvm" ->
     *      * 2 = {ConcurrentHashMap$MapEntry@3978} "dubbo" ->
     */
    public T getExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    /***
     * 获取默认的拓展类
     *      cachedDefaultName：这个是配置在拓展类型的SPI注解中的值
     * @return
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    /***
     * 是否包含了指定名称的拓展实现类
     * @param name
     * @return
     */
    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
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
    /***
     * 新增一个拓展类的实例
     * @param name
     * @param clazz
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // 加载现有的所有拓展类
        //类必须是拓展类型的实现累
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }
        //如果类未配置注解Adaptive
        if (!clazz.isAnnotationPresent(com.alibaba.dubbo.common.extension.Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {//如果类配置注解Adaptive，则要检查是否已经存在一个类配置了注解Adaptive
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
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
     */
    @Deprecated
    /***
     * 校验信息和addExtension一样，只不过这边是根据名称替换现有的
     */
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(com.alibaba.dubbo.common.extension.Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    /***
     *  获得自适应拓展类
     * 获取跟拓展类型ExtensionLoader实例关联的适配器(该适配器负责根据拓展接口的实现类型适配出对应的实例对象)
     *    1、判断拓展类型的加载类ExtensionLoader是否存在了一一对应的适配器
     *       a:存在，直接返回
     *       b:不存在，创建适配器，并绑定到拓展类型ExtensionLoader
     */
    public T getAdaptiveExtension() {// 这里通过getAdaptiveExtension方法获取一个运行时自适应的扩展类型(每个Extension只能有一个@Adaptive类型的实现，如果没有dubbo会动态生成一个类)
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            instance = createAdaptiveExtension();//创建一个拓展类型的适配器
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    /***
     * 根据拓展类名称查找拓展类型专有的异常
     *      在解析时候，如果一个拓展类的实现类解析的时候抛异常，这个流程并不会中断，而是会把这个异常对象和name的映射关系存放到exceptions集合中
     * @param name
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

    @SuppressWarnings("unchecked")
    /***创建name对应的拓展类型的实现类的实例（保证了拓展类型下的每种实现类只需要一个实例对象即可）
     *      1、根据name查询ExtensionLoader实例中的本地缓存cachedClasses集合，并获取name对应的拓展类型的实现类的Class实例。如果不存在或抛出异常
     *      2、根据Class对象查询ExtensionLoader实例中的本地缓存EXTENSION_INSTANCES，如果存放拓展类型的实现实例的EXTENSION_INSTANCES已经存在相应的实例，则直接返回该实例，否则就新建一个实例
     *      3、通过ExtensionFactory为实例对象注射初始值
     *      4、循环遍历定义了包含拓展类型的构造方法的实现类，如果存在这样的构造方法，则会优先级使用带有接口类型的构造方法来实例化对象
     */
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);//3、通过ExtensionFactory为实例对象注射初始值
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;//4、循环遍历定义了包含拓展类型的构造方法的实现类，如果存在这样的构造方法，则会优先级使用带有接口类型的构造方法来实例化对象
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }
    // 1、判断拓展类的工厂是否为空
    // 2、如果拓展类的工厂存在，通过实现类的set方法为实例变量注射赋值
    //      这个属性的类型也应该是拓展类型，属性名称是拓展类型对应的属性名称
    //          比如 public void com.alibaba.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper.setProtocol(com.alibaba.dubbo.rpc.Protocol)
    //           返回的object=Protocol$Adaptive@2251
    // 3、返回实例对象
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        Class<?> pt = method.getParameterTypes()[0];//实现类的set方法参数类型名称
                        try {
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";//获得属性名称
                            Object object = objectFactory.getExtension(pt, property);//根据拓展类
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /***
     * 根据名称获得拓展类
     *      1、判断ExtensionLoader的类型不能为空
     *      2、拓展接口的实现类名称不能为空
     *      3、根据拓展类的实现类的名称获得相应的拓展实现类
     * @param name
     * @return
     */
    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /***
     * 获取拓展接口的实现类的Class对象集合
     *      1、检查拓展类型的加载类中的本地缓存获取拓展接口的实现类的Class对象集合
     *          a、【拓展接口的实现类的Class对象集合】不为空，直接返回拓展类型的实例集合
     *          b、【拓展接口的实现类的Class对象集合】为空，则可能还未加载，根据拓展接口和去META-INF下配置文件种查找
     *
     * 获得某一拓展类型的所有拓展类
     *  * 0 = {HashMap$Node@4640} "jdk" -> "class com.alibaba.dubbo.common.compiler.support.JdkCompiler"
     *      * 1 = {HashMap$Node@4641} "javassist" -> "class com.alibaba.dubbo.common.compiler.support.JavassistCompiler"
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {//"spring" -> "class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory"，"spi" -> "class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory"
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);//将拓展类存放到本地缓存cachedClasses
                }
            }
        }
        return classes;
    }

    // synchronized in getExtensionClasses

    /***
     * 根据拓展接口类型，到META-INF下查找实现拓展接口的类的配置信息，主要是在以下3个路径下查找
     *      a、META-INF/dubbo/internal/
     *      b、META-INF/dubbo/
     *      c、META-INF/services/
     *      具体步骤：
     *      1、设置拓展类型的加载类的默认名称，获取接口SPI注解的value值作为ExtensionLoader实例的cachedDefaultName值。比如:@SPI("dubbo")
     *          cachedDefaultName：作用是根据cachedDefaultName可以在ExtensionLoader的静态集合中找到对应的ExtensionLoader实例
     *      2、读取META-INF.dubbo.internal.${type}配置文件并加载拓展类型的实现类，比如：
     *          在 META-INF.dubbo.internal.com.alibaba.dubbo.common.extension.ExtensionFactory文件下获取ExtensionFactory实现类的类完整路径
     *      3、读取META-INF.dubbo.${type}配置文件中加载拓展类型的实现类，比如：
     *          在 META-INF.dubbo.com.alibaba.dubbo.common.extension.ExtensionFactory文件下获取ExtensionFactory实现类的类完整路径
     *      4、读取META-INF.services.${type}配置文件中加载拓展类型的实现类，比如：
     *           在 META-INF.services.com.alibaba.dubbo.common.extension.ExtensionFactory文件下获取ExtensionFactory实现类的类完整路径
     * @return
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        final com.alibaba.dubbo.common.extension.SPI defaultAnnotation = type.getAnnotation(com.alibaba.dubbo.common.extension.SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();//获得默认的拓展类实现
            if (value != null && (value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }
        //加载拓展文件里的配置
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        // META-INF/dubbo/internal/
        //读取拓展类的配置文件路：incubator-dubbo/dubbo-config/dubbo-config-spring/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
        //配置文件中的内容:spring=com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory
        //读取拓展类的配置文件路：incubator-dubbo/dubbo-common/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
        //配置文件中的内容:adaptive=com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory
        //                 spi=com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory
        loadFile(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        // META-INF/dubbo/
        //fileName:META-INF/dubbo/com.alibaba.dubbo.common.extension.ExtensionFactory
        loadFile(extensionClasses, DUBBO_DIRECTORY);
        // META-INF/services/
        //fileName:META-INF/services/com.alibaba.dubbo.common.extension.ExtensionFactory
        loadFile(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /***
     * 根据拓展接口类型和基础路径查找拓展类型的实现类的Class集合
     * @param extensionClasses 拓展类型的实现类的Class集合
     * @param dir 查找路径
     *       1、根据拓展接口类型和基础路径查找拓展类型，检查资源文件是否存在，若不存在则直接返回
     *       2、读取配置文件的配置信息，并解析成K-v键值对，比如：
     *            spring=com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory
     *       3、检查配置的实现类是否配置注解Adaptive
     *            将配置了注解的类设置为拓展类型的适配器类
     *            每个拓展类型只能配置一个适配器类
     *       4、校验实现类是否存在以下两种构造方法之一
     *            包含一个接口参数的构造方法
     *            包含无参数的构造方法
     *       5、检查实现类是否配置了Activate注解，如果陪配置了Activate注解，则也将该实现类的Class实例缓存到extensionLoader实例的本地缓存：
     *            Map<String, Activate> cachedActivates
     *       6、将拓展类型实现类和name分别缓存到extensionLoader实例的本地缓存：
     *            ConcurrentMap<Class<?>, String> cachedNames
     *       7、返回Map<String, Class<?>> extensionClasses，如果存在多个实现类的配置的Key是一样的，则抛出异常
     *
     */
    private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {
        //这里type=com.alibaba.dubbo.common.extension.ExtensionFactory.class,所以type.getName=com.alibaba.dubbo.common.extension.ExtensionFactory
        //type=com.alibaba.dubbo.rpc.Protocol,所以fileName=META-INF/dubbo/internal/com.alibaba.dubbo.rpc.Protocol
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                //fileName=META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    //(1)url=file:/Users/hb/Documents/study/xuzhifang/incubator-dubbo/dubbo-config/dubbo-config-spring/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
                    //(2)url=file:/Users/hb/Documents/study/xuzhifang/incubator-dubbo/dubbo-common/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
                    java.net.URL url = urls.nextElement();
                    try {
                        //读取指定路径的文件内容
                        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                        try {
                            String line = null;
                            // META-INF/dubbo/internal/
                            //读取拓展类的配置文件路：incubator-dubbo/dubbo-config/dubbo-config-spring/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
                            //配置文件中的内容:spring=com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory
                            //读取拓展类的配置文件路：incubator-dubbo/dubbo-common/target/classes/META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory
                            //配置文件中的内容:adaptive=com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory
                            //                 spi=com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory
                            while ((line = reader.readLine()) != null) {
                                final int ci = line.indexOf('#');
                                if (ci >= 0) line = line.substring(0, ci);//截取到#号，#号后面的忽略
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
                                            //根据配置反射生产Class对象
                                            Class<?> clazz = Class.forName(line, true, classLoader);
                                            //判定此 Class 对象所表示的类或接口与指定的 Class 参数所表示的类或接口是否相同，或是否是其超类或超接口
                                            //所以自定义拓展类要实现com.alibaba.dubbo.common.extension.ExtensionFactory
                                            if (!type.isAssignableFrom(clazz)) {
                                                throw new IllegalStateException("Error when load extension class(interface: " +
                                                        type + ", class line: " + clazz.getName() + "), class "
                                                        + clazz.getName() + "is not subtype of interface.");
                                            }
                                            //检查如果设置了@Adaptive，则把clazz保存在cachedAdaptiveClass中
                                            //对于ExtensionFactory来说，AdaptiveExtensionFactory设置了@Adaptive注解：
                                            if (clazz.isAnnotationPresent(com.alibaba.dubbo.common.extension.Adaptive.class)) {
                                                //最多有一个添加Adaptive注解的类,在这里dubbo默认配置了这样这样的一个类:adaptive=com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory
                                                //cachedAdaptiveClass=com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory
                                                if (cachedAdaptiveClass == null) {
                                                    cachedAdaptiveClass = clazz;
                                                } else if (!cachedAdaptiveClass.equals(clazz)) {
                                                    throw new IllegalStateException("More than 1 adaptive class found: "
                                                            + cachedAdaptiveClass.getClass().getName()
                                                            + ", " + clazz.getClass().getName());
                                                }
                                            } else {
                                                //对于没有设置@Adaptive的类，则存入loadExtensionClasses()传到loadFile()中的参数extensionClasses，
                                                // 返回后在getExtensionClasses() 中赋给cachedClasses缓存
                                                //对于ExtensionFactory来说，SpiExtensionFactory和SpringExtensionFactory都没有设置@Adaptive注解
                                                // （同一个类只能有一个Adaptive实现），所以都被存入了ExtensionLoader<ExtensionFactory>的cachedClasses。
                                                //
                                                try {
                                                    //获取类的构造函数，构造函数的参数是type类型，所以说自定义的拓展要定一个一个包含type的构造函数
                                                    clazz.getConstructor(type);
                                                    Set<Class<?>> wrappers = cachedWrapperClasses;
                                                    if (wrappers == null) {
                                                        cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                                                        wrappers = cachedWrapperClasses;
                                                    }
                                                    wrappers.add(clazz);
                                                } catch (NoSuchMethodException e) {
                                                    //如果不存在包含type的构造函数，则检查是否有空的构造函数
                                                    clazz.getConstructor();
                                                    if (name == null || name.length() == 0) {
                                                        name = findAnnotationName(clazz);//获得拓展类的名称
                                                        //判断拓展类的映射名称name
                                                        if (name == null || name.length() == 0) {
                                                            if (clazz.getSimpleName().length() > type.getSimpleName().length()
                                                                    && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                                name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                                            } else {
                                                                throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + url);
                                                            }
                                                        }
                                                    }
                                                    String[] names = NAME_SEPARATOR.split(name);//获得拓展类配置的类名
                                                    if (names != null && names.length > 0) {
                                                        //获得自定义拓展类的Activate注解信息，//一个扩展点只能又一个实现类做了Activate
                                                        com.alibaba.dubbo.common.extension.Activate activate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
                                                        if (activate != null) {//假如该拓展类配置了Activate注解，将{name, Activate}放到本地缓存cachedActivates中
                                                            cachedActivates.put(names[0], activate);
                                                        }
                                                        for (String n : names) {
                                                            //判断cachedNames是否已包含clazz
                                                            if (!cachedNames.containsKey(clazz)) {//存放拓展类class映射到name的映射关系，多个class可以映射同一个name，但是一个class只能映射一个name
                                                                //{
                                                                // com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory=spring,
                                                                // com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory=spi
                                                                // }
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            //和cachedNames相反，它存放的是name到拓展类的 Class映射关系，如果多个name映射同一Class，则保存多条，但是一个name只能映射一个Class，且是第一个
                                                            if (c == null) {
                                                                //{
                                                                //spring=class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory,
                                                                //spi=class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory
                                                                // }
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {//如果对同一个name配置了多个拓展类，则抛出异常
                                                                throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + url + ", cause: " + t.getMessage(), t);
                                        exceptions.put(line, e);
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            reader.close();
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " +
                                type + ", class file: " + url + ") in " + url, t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    @SuppressWarnings("deprecation")
    /***
     * 返回目标类的注解名称
     * 1、获得自定义类的Extension的注解
     *      注解Extension：获得注解配置的值
     *      未注解Extension：则直接获得目标类的名称
     */
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {//如果未注解extension，则获得自定义类的名称并返回
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    /***
     * 创建extensionLoader关联的适配器类
     */
    private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /***
     * 获取拓展类的适配器类
     *      1、到指定路径下查找接口对应的配置文件，并加载接口的实现类
     *      2、判断该拓展类型是否配置了适配器类
     *          a、配置文件已配置，则直接返回该适配器类
     *          b、未配置，则系统自动根据接口犯系列化一个适配器类
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        getExtensionClasses();//获取配置文件中的拓展类
        if (cachedAdaptiveClass != null) {//判断是否有拓展类配置了Adaptive注解
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass();//系统自动创建Adaptive实现类，没定义任何东西
    }

    /***
     *  系统自动根据type创建一个跟ExtensionLoader进行绑定的适配器实例
     *      1、根据拓展的接口类型生成一个适配器类的字符串
     *      2、加载拓展类型Compiler的实现类，并返回一个实例
     *      3、根据编译类编译加载适配器类的字符串
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        //系统会自动根据type创建一个Adaptive适配器类的字符串,创建的字符串是根据拓展类型接口，并根据接口中配有注解@Adaptive的方法来生哼
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        //这里会从配置MET-INF下的拓展类的配置文件中查找
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    /**
     * 系统自动根据ExtensionLoader的接口类型来创建一个Adaptive实现类，这个Adaptive不包含任何方法
     * @return
     */
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuidler = new StringBuilder();
        Method[] methods = type.getMethods();//type=com.alibaba.dubbo.common.extension.ExtensionFactory、com.alibaba.dubbo.rpc.Protocol等等
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {//如果type=com.alibaba.dubbo.rpc.Protoco,则它的export方法是Adaptive
            if (m.isAnnotationPresent(com.alibaba.dubbo.common.extension.Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation)//表示接口中必须存在adaptive method
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        codeBuidler.append("package " + type.getPackage().getName() + ";");
        codeBuidler.append("\nimport " + ExtensionLoader.class.getName() + ";");
        codeBuidler.append("\npublic class " + type.getSimpleName() + "$Adaptive" + " implements " + type.getCanonicalName() + " {");

        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            com.alibaba.dubbo.common.extension.Adaptive adaptiveAnnotation = method.getAnnotation(com.alibaba.dubbo.common.extension.Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            if (adaptiveAnnotation == null) {//如果遍历的方法未配置adaptive注解，则该方法直接被定义为操作异常，表示不能被操作
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                else {
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                if (value.length == 0) {
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            codeBuidler.append("\npublic " + rt.getCanonicalName() + " " + method.getName() + "(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuidler.append(", ");
                }
                codeBuidler.append(pts[i].getCanonicalName());
                codeBuidler.append(" ");
                codeBuidler.append("arg" + i);
            }
            codeBuidler.append(")");
            if (ets.length > 0) {
                codeBuidler.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuidler.append(", ");
                    }
                    codeBuidler.append(ets[i].getCanonicalName());
                }
            }
            codeBuidler.append(" {");
            codeBuidler.append(code.toString());
            codeBuidler.append("\n}");
        }
        codeBuidler.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuidler.toString());
        }
        return codeBuidler.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
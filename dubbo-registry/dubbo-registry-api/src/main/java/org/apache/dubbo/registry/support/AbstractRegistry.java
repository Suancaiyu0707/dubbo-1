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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.ACCEPTS_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTRY_FILESAVE_SYNC_KEY;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 * 实现了Registry的订阅、通知、注册、查询等方法，还实现了磁盘文件持久化注册信息。
 * 这里的订阅、通知、注册、查询只是简单把URL加到集合中，具体的注册或订阅逻辑🈶️子类来完成
 */
public abstract class AbstractRegistry implements Registry {

    //  URL地址分隔符，用于文件缓存中，服务提供者URL分隔
    private static final char URL_SEPARATOR = ' ';
    // URL address separated regular expression for parsing the service provider URL list in the file cache
    //URL地址分隔正则表达式，用于解析文件缓存中服务提供者URL列表
    private static final String URL_SPLIT = "\\s+";
    //保存本地缓存文件失败时，最大的重试次数
    private static final int MAX_RETRY_TIMES_SAVE_PROPERTIES = 3;
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    //properties保存了所有服务提供者的URL，使用URL#serviceKey作为key，提供者列表、路由规则列表、配置规则列表等作为value
    /**
     * 本地磁盘缓存。
     *  其中特殊的 key 值 .registies，它值是记录注册中心列表
     *  其它均为 notified 服务提供者列表
     *
     * 注册中心数据发生变更时，通知到 Registry 后，修改 properties 对应的值，并写入 file
     */
    private final Properties properties = new Properties();
    /**
     * 注册中心缓存写入执行器。
     */
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
    /**
     * properties 发生变更时候，是同步还是异步写入 file
     */
    private final boolean syncSaveFile;
    private final AtomicLong lastCacheChanged = new AtomicLong();
    private final AtomicInteger savePropertiesRetryTimes = new AtomicInteger();
    /***
     * 已注册 URL 集合
     * 这里，注册的 URL 不仅仅可以是服务提供者的，也可以是服务消费者的
     */
    private final Set<URL> registered = new ConcurrentHashSet<>();
    //维护监听该url的监听器列表，可能多个监听器监听同一个url路径
    /***
     *订阅 URL 的监听器集合
     *  key：订阅者的 URL ，例如消费者的 URL
     *  value：是订阅消费者监听器
     *
     *
     */
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<>();
    /**
     *
     * 0 = {ConcurrentHashMap$MapEntry@4548} "provider://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282" -> " size = 1"
     *  key = {URL@4367} "provider://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282"
     *  value = {ConcurrentHashMap@4529}  size = 1
     *   0 = {ConcurrentHashMap$MapEntry@4552} "configurators" -> " size = 1"
     *    key = "configurators"
     *    value = {ArrayList@4513}  size = 1
     * 1 = {ConcurrentHashMap$MapEntry@4549} "provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=14340&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576478833784" -> " size = 1"
     *  key = {URL@3847} "provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=14340&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576478833784"
     *  value = {ConcurrentHashMap@4021}  size = 1
     *   0 = {ConcurrentHashMap$MapEntry@4556} "configurators" -> " size = 1"
     *    key = "configurators"
     *    value = {ArrayList@4558}  size = 1
     *
     * notified是ConcurrentMap类型里面又嵌套了一个Map,
 *          外层map的key是订阅者的URL，和 {@link #subscribed} 的键一致
     *         内层的Map的key是分类的，包含provider、consumers、routers、configurators。
     *         内层的value则是对应的服务列表，对于没有服务提供者提供服务的URL，它会以特殊的empty://前缀开头
     */
    private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<>();//内存中的服务缓存对象
    /**
     * 注册中心 URL
     *      //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096
     *
     */
    private URL registryUrl;
    /**
     * 本地磁盘文件服务缓存对象
     *      /Users/hb/.dubbo/dubbo-registry-demo-provider-127.0.0.1-2181.cache,内容如下
     *      #Dubbo Registry Cache
     *      #Mon Dec 16 19:32:36 CST 2019
     *      org.apache.dubbo.demo.MockService=empty\://220.250.64.225\:20880/org.apache.dubbo.demo.MockService?anyhost\=true&bean.name\=org.apache.dubbo.demo.MockService&bind.ip\=220.250.64.225&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.MockService&methods\=sayHello&pid\=30672&release\=&side\=provider&timestamp\=1576489707917
     *      org.apache.dubbo.demo.CallbackService=empty\://192.168.0.105\:20880/org.apache.dubbo.demo.CallbackService?anyhost\=true&bean.name\=org.apache.dubbo.demo.CallbackService&bind.ip\=192.168.0.105&bind.port\=20880&callbacks\=1000&category\=configurators&check\=false&connections\=1&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.CallbackService&methods\=addListener&pid\=80463&release\=&side\=provider&timestamp\=1576456696572
     *      org.apache.dubbo.demo.AsyncService=empty\://192.168.0.105\:20880/org.apache.dubbo.demo.AsyncService?anyhost\=true&bean.name\=org.apache.dubbo.demo.AsyncService&bind.ip\=192.168.0.105&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.AsyncService&methods\=sayHello&pid\=80463&release\=&side\=provider&timestamp\=1576456696783
     *      org.apache.dubbo.demo.StubService=empty\://172.17.208.173\:20880/org.apache.dubbo.demo.StubService?anyhost\=true&bean.name\=org.apache.dubbo.demo.StubService&bind.ip\=172.17.208.173&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.StubService&methods\=sayHello&pid\=38508&release\=&side\=provider&stub\=org.apache.dubbo.demo.StubServiceStub&timestamp\=1576494020229
     *      org.apache.dubbo.demo.AsyncService2=empty\://192.168.0.105\:20880/org.apache.dubbo.demo.AsyncService2?anyhost\=true&bean.name\=org.apache.dubbo.demo.AsyncService2&bind.ip\=192.168.0.105&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.AsyncService2&methods\=sayHello&pid\=80463&release\=&side\=provider&timestamp\=1576456696499
     *      org.apache.dubbo.demo.InjvmService=empty\://192.168.0.105\:20880/org.apache.dubbo.demo.InjvmService?anyhost\=true&bean.name\=org.apache.dubbo.demo.InjvmService&bind.ip\=192.168.0.105&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.InjvmService&methods\=sayHello_Injvm&pid\=80463&release\=&side\=provider&timestamp\=1576456697469
     *      cn/org.apache.dubbo.demo.EventNotifyService\:1.0.0=empty\://192.168.0.105\:20880/org.apache.dubbo.demo.EventNotifyService?anyhost\=true&bean.name\=org.apache.dubbo.demo.EventNotifyService&bind.ip\=192.168.0.105&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&group\=cn&interface\=org.apache.dubbo.demo.EventNotifyService&methods\=get&pid\=80463&release\=&revision\=1.0.0&side\=provider&timestamp\=1576456692288&version\=1.0.0
     *      org.apache.dubbo.demo.DemoService=empty\://192.168.0.105\:20880/org.apache.dubbo.demo.DemoService?anyhost\=true&bean.name\=org.apache.dubbo.demo.DemoService&bind.ip\=192.168.0.105&bind.port\=20880&category\=configurators&check\=false&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.DemoService&methods\=sayHello,sayHelloAsync&pid\=80463&release\=&side\=provider&timestamp\=1576456697299
     *
     *      dubbo-registry-demo-consumer-127.0.0.1-2181.cache,内容如下
     *      #Dubbo Registry Cache
     *      #Mon Dec 16 08:48:17 CST 2019
     *      org.apache.dubbo.demo.MockService=empty\://220.250.64.225/org.apache.dubbo.demo.MockService?category\=routers&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.MockService&lazy\=false&methods\=sayHello&mock\=force%3Aorg.apache.dubbo.demo.consumer.MockServiceMock&pid\=1562&side\=consumer&sticky\=false&timestamp\=1575959908116 empty\://220.250.64.225/org.apache.dubbo.demo.MockService?category\=configurators&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.MockService&lazy\=false&methods\=sayHello&mock\=force%3Aorg.apache.dubbo.demo.consumer.MockServiceMock&pid\=1562&side\=consumer&sticky\=false&timestamp\=1575959908116 empty\://220.250.64.225/org.apache.dubbo.demo.MockService?category\=providers&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.MockService&lazy\=false&methods\=sayHello&mock\=force%3Aorg.apache.dubbo.demo.consumer.MockServiceMock&pid\=1562&side\=consumer&sticky\=false&timestamp\=1575959908116
     *      org.apache.dubbo.demo.StubService=empty\://192.168.0.105/org.apache.dubbo.demo.StubService?category\=routers&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.StubService&lazy\=false&methods\=sayHello&pid\=80530&side\=consumer&sticky\=false&timestamp\=1576456745232 empty\://192.168.0.105/org.apache.dubbo.demo.StubService?category\=configurators&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.StubService&lazy\=false&methods\=sayHello&pid\=80530&side\=consumer&sticky\=false&timestamp\=1576456745232 dubbo\://192.168.0.105\:20880/org.apache.dubbo.demo.StubService?anyhost\=true&bean.name\=org.apache.dubbo.demo.StubService&deprecated\=false&dubbo\=2.0.2&dynamic\=true&generic\=false&interface\=org.apache.dubbo.demo.StubService&methods\=sayHello&pid\=80463&release\=&side\=provider&stub\=org.apache.dubbo.demo.StubServiceStub&timestamp\=1576456697003
     *      org.apache.dubbo.demo.DemoService=empty\://220.250.64.225/org.apache.dubbo.demo.DemoService?category\=routers&check\=false&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.DemoService&lazy\=false&methods\=sayHello,sayHelloAsync&pid\=38171&side\=consumer&sticky\=false&timestamp\=1575883395444 empty\://220.250.64.225/org.apache.dubbo.demo.DemoService?category\=configurators&check\=false&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.DemoService&lazy\=false&methods\=sayHello,sayHelloAsync&pid\=38171&side\=consumer&sticky\=false&timestamp\=1575883395444 empty\://220.250.64.225/org.apache.dubbo.demo.DemoService?category\=providers&check\=false&dubbo\=2.0.2&init\=false&interface\=org.apache.dubbo.demo.DemoService&lazy\=false&methods\=sayHello,sayHelloAsync&pid\=38171&side\=consumer&sticky\=false&timestamp\=1575883395444
     */
    private File file;

    /***
     *
     * @param url
     * 1、 设置配置中心的地址
     *      zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096
     * 2、 检查配置中心的URL中是否配置了同步保存文件属性，通过save.file设置，默认为false。
     * 3、获得配置信息本地缓存的文件：
     *      /Users/hb/.dubbo/dubbo-registry-demo-provider-127.0.0.1-2181.cache
     *      /Users/hb/.dubbo/dubbo-registry-demo-consumer-127.0.0.1-2181.cache
     * 4、如果本地已经缓存了指定的配置文件，则进行加载
     * 5、
     *
     */
    public AbstractRegistry(URL url) {
        //1. 设置配置中心的地址
        //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096
        setUrl(url);// 2. 配置中心的URL中是否配置了同步保存文件属性，否则默认为false
        syncSaveFile = url.getParameter(REGISTRY_FILESAVE_SYNC_KEY, false);
        //配置信息本地缓存的文件名
        String defaultFilename = System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(APPLICATION_KEY) + "-" + url.getAddress().replaceAll(":", "-") + ".cache";//
        String filename = url.getParameter(FILE_KEY, defaultFilename);// /Users/hb/.dubbo/dubbo-registry-demo-provider-127.0.0.1-2181.cache
        File file = null;
        if (ConfigUtils.isNotEmpty(filename)) {
            file = new File(filename);
            if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException("Invalid registry cache file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                }
            }
        }
        this.file = file;
        // When starting the subscription center,
        // we need to read the local cache file for future Registry fault tolerance processing.
        //如果现有配置缓存，则从缓存文件中加载属性
        loadProperties();
        //如果url上设置了backup属性值，则参数返回backup设置的url
        notify(url.getBackupUrls());
    }

    /***
     * 获得协议是empty的url
     * @param url
     *      provider:
     *      consumer:
     * @param urls
     * @return
     *      如果urls是空的话，则把url的协议改成empty并返回。
     *      如果urls非空的话，则直接返回urls
     *
     */
    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            List<URL> result = new ArrayList<>(1);
            result.add(url.setProtocol(EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return Collections.unmodifiableSet(registered);
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return Collections.unmodifiableMap(subscribed);
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return Collections.unmodifiableMap(notified);
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    /***
     * 保存properties
     * @param version
     */
    public void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        try {
            //
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            try (RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
                 FileChannel channel = raf.getChannel()) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                }
                // Save
                try {
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    try (FileOutputStream outputFile = new FileOutputStream(file)) {
                        properties.store(outputFile, "Dubbo Registry Cache");
                    }
                } finally {
                    lock.release();
                }
            }
        } catch (Throwable e) {
            savePropertiesRetryTimes.incrementAndGet();
            if (savePropertiesRetryTimes.get() >= MAX_RETRY_TIMES_SAVE_PROPERTIES) {
                logger.warn("Failed to save registry cache file after retrying " + MAX_RETRY_TIMES_SAVE_PROPERTIES + " times, cause: " + e.getMessage(), e);
                savePropertiesRetryTimes.set(0);
                return;
            }
            if (version < lastCacheChanged.get()) {
                savePropertiesRetryTimes.set(0);
                return;
            } else {
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry cache file, will retry, cause: " + e.getMessage(), e);
        }
    }
    //在服务初始化时，会调用该方法，注册中心会从本地磁盘中把持久化的注册数据注册到Properties对象里，并加载到内存缓存中。
    // 默认 从/Users/hb/.dubbo/dubbo-registry-demo-provider-127.0.0.1-2181.cache 读取配置信息
    //properties保存了所有服务提供者的URL，使用URL#serviceKey作为key，提供者列表、路由规则列表、配置规则列表等作为value
    private void loadProperties() {
        //当本地存在配置缓存文件时
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                //读取配置文件的内容，并加载为properties的键值对存储
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry cache file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry cache file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }

    /***
     *
     * @param url
     *      provider:
     *      consumer:
     * @return
     */
    public List<URL> getCacheUrls(URL url) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();//key是服务提供者URL#servicekey()
            String value = (String) entry.getValue();//值可能是提供者的列表、路由规则的列表、配置规则等，这多个值用空格隔开
            if (key != null && key.length() > 0 && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && value != null && value.length() > 0) {
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }
    /***
     *  返回对url服务下的子目录的订阅列表
     * @param url
     *      provider:
     *      consumer:
     * @return
     * 1、根据url获得url服务对应的需要监听的路径：configurators/consumers/routers等
     * 2、如果已存在对该url服务的监控通知集合，则表示已对该url进行订阅维护了。
     *      这时候判断订阅的对应的路径的协议不是empty，则添加到result。返回订阅的地址
     */
    @Override
    public List<URL> lookup(URL url) {
        List<URL> result = new ArrayList<>();
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    if (!EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        } else {//如果还未对url进行订阅，则先订阅url，然后返回订阅的路径列表
            final AtomicReference<List<URL>> reference = new AtomicReference<>();
            NotifyListener listener = reference::set;
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (CollectionUtils.isNotEmpty(urls)) {
                for (URL u : urls) {
                    if (!EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 本地内存记录已注册的服务（这步其实还没有真正的进行注册，仅仅是添加到 registered 中，进行状态的维护）
     * @param url  Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     *      provider: dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=30672&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576489098974
     *      consumer: consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=consumers&check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     */
    @Override
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    /**
     * 取消注册的服务
     * @param url Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     *      provider: dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=30672&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576489098974
     *      consumer: consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=consumers&check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     */
    @Override
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    /***
     * 分别存储订阅的URL和其对应的监听器列表。可能多个监听器监听同一个地址
     * @param url      Subscription condition, not allowed to be empty, e.g. consumer://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     * @param listener A listener of the change event, not allowed to be empty
     *      provider: provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=19528&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576482019237
     *      consumer: consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=providers,configurators,routers&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     * 1、维护订阅关系，listener监听订阅url
     */
    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }//维护订阅信息：url<->Set<NotifyListener> 的映射关系
        //其实就是维护订阅消息
        Set<NotifyListener> listeners = subscribed.computeIfAbsent(url, n -> new ConcurrentHashSet<>());
        listeners.add(listener);
    }

    /**
     * 取消订阅
     * @param url      Subscription condition, not allowed to be empty, e.g. consumer://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     * @param listener A listener of the change event, not allowed to be empty
     *      provider: provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=19528&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576482019237
     *      consumer: consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=consumers&check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     */
    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    /***
     * 服务恢复,在注册中心断开，重连成功，调用 #recover() 方法，进行恢复注册和订阅。
     * 服务的恢复包括注册服务的恢复和订阅服务的恢复。因为内存中保留了注册的服务和订阅的服务。因此在恢复的时候会重新拉取这些数据，分别调用发布和订阅的方法来重新将其录入到注册中心上。
     * @throws Exception
     * 1、获取本地缓存里已注册的服务
     * 2、遍历缓存的已注册服务，一个个的重新注册
     * 3、获取本地缓存的已订阅的信息
     * 4、遍历本地缓存的已订阅的信息，一个个重新订阅
     */
    protected void recover() throws Exception {
        // register
        Set<URL> recoverRegistered = new HashSet<>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                register(url);
            }
        }
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    subscribe(url, listener);
                }
            }
        }
    }

    /***
     *  当监听的的url发生变化的时候，会回调通知所有的订阅该url的监听器NotifyListener
     *
     * @param urls 该方法只会被上面的构造方法里调用，所以urls的值是url的backup属性指定的url列表
     * 1、遍历所有的订阅关系的信息subscribed，其中key是订阅的url路径，value是监听这个url变化的NotifyListener列表
     * 2、检查遍历的订阅关系，检查urls列表里是否包含了该url，如果有的话。调用所有监听当前url的   NotifyListener.notify方法
     *
     */
    protected void notify(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        /***
         * 遍历所有的订阅关系的信息
         */
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL url = entry.getKey();//或者订阅关系的url
            //如果通知的url不包含订阅的url，则检查下一个订阅的ulr
            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }
            /***
             * 如果通知的url包含订阅的url，则遍历所有的监听当前url的NotifyListener，并调用对应的notify方法
             */
            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                for (NotifyListener listener : listeners) {
                    try {
                        notify(url, listener,
                                filterEmpty(url, urls)//获得协议是empty的相应的url列表
                        );
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * 通知对应的监听器，URL发生了变化了， 变化结果urls。
     * @param url 订阅者 URL
     *     provider: provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=10604&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576476419518
     *         会订阅configurators 目录
     *     consumer：consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=providers,configurators,routers&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     *        会订阅configurators/providers/routers 目录
     * @param listener 监听子路径变化的监听器 listener
     * @param urls 通知的 URL 变化结果（全量数据，这里的全量是某个服务下面的某一种分类的全量）
     *  1、对参数的校验
     *  2、notified维护针对url的通知的映射关系
     *     {serviceUrl:{configurators:url}}
     *  3、将订阅者url和订阅变化的urls进行处理
     *     a、检查订阅变化的urls是否符合url订阅
     *     b、维护覆盖notified中订阅者url最新的通知变化
     *     c、根据变化的url的类别category更新notified中对应类型的变化信息
     *     d、调用对应的监听器进行处理响应。
     *     e、把最新的变更信息通知保存到本地缓存文件
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((CollectionUtils.isEmpty(urls))
                && !ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }
        // keep every provider's category.
        // 遍历所有的urls
        Map<String, List<URL>> result = new HashMap<>();
        for (URL u : urls) {
            //检查变化的u是否符合url订阅的
            if (UrlUtils.isMatch(url, u)) {
                //获得category属性值。默认是providers
                String category = u.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
                List<URL> categoryList = result.computeIfAbsent(category, k -> new ArrayList<>());
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }
        //notified 维护了订阅信息发生变化的url
        //      key :订阅者的url
        //      value：
        //          key2：是订阅的类型，包含provider、consumers、routers、configurators
        //          value2：对应类型通知的 URL 变化结果（全量数据）
        Map<String, List<URL>> categoryNotified = notified.computeIfAbsent(url, u -> new ConcurrentHashMap<>());
        // 按照分类，循环处理通知的 URL 变化结果（全量数据）。
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            String category = entry.getKey();//configurators
            List<URL> categoryList = entry.getValue();//{empty://192.168.0.105:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=192.168.0.105&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=82030&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576457680054}
            // 覆盖到 `notified`
            // 当某个分类的数据为空时，会依然有 urls 。其中 `urls[0].protocol = empty` ，通过这样的方式，处理所有服务提供者为空的情况。
            categoryNotified.put(category, categoryList);
            // 保存到文件
            listener.notify(categoryList);
            // We will update our cache file after each notification.
            // When our Registry has a subscribe failure due to network jitter, we can return at least the existing cache URL.
            saveProperties(url);
        }
    }

    /***
     * 根据url获取 notified 记录的最新的变更信息进行更新properties中相应的信息。
     * 异步/同步将订阅信息的变更保存到缓存文件里
     * @param url
     */
    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            StringBuilder buf = new StringBuilder();
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                for (List<URL> us : categoryNotified.values()) {
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        buf.append(u.toFullString());
                    }
                }
            }
            //根据url获取 notified 记录的最新的变更信息进行更新properties中相应的信息。
            properties.setProperty(url.getServiceKey(), buf.toString());
            long version = lastCacheChanged.incrementAndGet();
            //异步/同步将订阅信息的变更保存到缓存文件里
            if (syncSaveFile) {//同步保存缓存
                doSaveProperties(version);
            } else {//异步保存，放入线程池中，，会传入一个版本号，保证数据是最新的
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }
        Set<URL> destroyRegistered = new HashSet<>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<>(getRegistered())) {
                if (url.getParameter(DYNAMIC_KEY, true)) {
                    try {
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }
    //

    /***
     * 检查这个注册中心是否接收注册 查看accepts属性
     * @param urlToRegistry
     * @return
     * 1、如果注册中心地址的accepts为空，则接受服务注册
     * 2、如果注册中心地址的accepts不为空，则检查协议是否匹配了accepts设置的规则
     */
    protected boolean acceptable(URL urlToRegistry) {
        String pattern = registryUrl.getParameter(ACCEPTS_KEY);//判断注册中心是否接受注册，通过获得accepts属性
        if (StringUtils.isEmpty(pattern)) {
            return true;
        }

        return Arrays.stream(COMMA_SPLIT_PATTERN.split(pattern))
                .anyMatch(p -> p.equalsIgnoreCase(urlToRegistry.getProtocol()));
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}

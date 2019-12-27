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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ReferenceConfigDestroyedEvent;
import org.apache.dubbo.config.service.ReferenceConfigBase;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.registry.ZoneAwareCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Please avoid using this class for any new application,
 * use {@link ReferenceConfigBase} instead.
 */
public class ReferenceConfig<T> extends ReferenceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ReferenceConfig.class);

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private static final Protocol REF_PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * The {@link Cluster}'s implementation with adaptive functionality, and actually it will get a {@link Cluster}'s
     * specific implementation who is wrapped with <b>MockClusterInvoker</b>
     */
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The url of the reference service
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    private DubboBootstrap bootstrap;

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        super(reference);
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    public synchronized T get() {
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        if (ref == null) {
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected error occured when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    public synchronized void init() {
        if (initialized) {
            return;
        }

        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            bootstrap.init();
        }
        //检查和更新消费端的配置
        checkAndUpdateSubConfigs();

        //初始化调用接口的元信息：版本、分组、接口类型、接口名称
        serviceMetadata.setVersion(version);
        serviceMetadata.setGroup(group);
        serviceMetadata.setDefaultGroup(group);
        serviceMetadata.setServiceType(getActualInterface());
        serviceMetadata.setServiceInterfaceName(interfaceName);
        // TODO, uncomment this line once service key is unified
        serviceMetadata.setServiceKey(URL.buildKey(interfaceName, group, version));//org.apache.dubbo.demo.DemoService
        //检查本地存根和本地调用(检查stub和local引用的类是否存在，是否规范：必须存在一个只有一个参数类型为interfaceClass的构造函数；且必须实现 interfaceClass 接口)
        checkStubAndLocal(interfaceClass);
        ConfigValidationUtils.checkMock(interfaceClass, this);//如果配置了mock属性，则根据mock不同值进行校验

        Map<String, String> map = new HashMap<String, String>();
        //标记这边是一个消费端
        map.put(SIDE_KEY, CONSUMER_SIDE);
        /**
         * 维护运行时参数
         * 1、dubbo：协议版本号："dubbo" -> "2.0.2"
         * 2、release：服务版本："release" -> ''
         * 3、timestamp：时间。"timestamp" -> "1576737965762"
         * 4、pid：进程号。"pid" -> "686"
         */
        ReferenceConfigBase.appendRuntimeParameters(map);
        if (!ProtocolUtils.isGeneric(generic)) {
            String revision = Version.getVersion(interfaceClass, version);//获得接口的版本配置，可通过vesrion进行设置
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            //获得调用接口的所有方法，并组装成数组
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();//{sayHello,sayHelloAsyn}
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }
        map.put(INTERFACE_KEY, interfaceName);//添加接口类型
        AbstractConfig.appendParameters(map, metrics);//添加埋点配置
        AbstractConfig.appendParameters(map, application);//添加application配置
        AbstractConfig.appendParameters(map, module);
        // remove 'default.' prefix for configs from ConsumerConfig
        // appendParameters(map, consumer, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, consumer);//添加服务引用者配置
        AbstractConfig.appendParameters(map, this);
        Map<String, Object> attributes = null;
        //检查消费者调用的方法
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>();
            for (MethodConfig methodConfig : getMethods()) {
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                ConsumerModel.AsyncMethodInfo asyncMethodInfo = AbstractConfig.convertMethodConfig2AsyncInfo(methodConfig);
                if (asyncMethodInfo != null) {
//                    consumerModel.getMethodModel(methodConfig.getName()).addAttribute(ASYNC_KEY, asyncMethodInfo);
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }
        //检查是否配置了系统配置：DUBBO_IP_TO_REGISTRY。顺序： System environment -> System properties
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {//如果没有配置 DUBBO_IP_TO_REGISTRY
            hostToRegistry = NetUtils.getLocalHost();//使用服务器本地地址作为暴露服务的地址
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(REGISTER_IP_KEY, hostToRegistry);

        serviceMetadata.getAttachments().putAll(map);

        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(interfaceClass);
        repository.registerConsumer(//缓存repository.consumers，记录已注册的消费者
                serviceMetadata.getServiceKey(),//org.apache.dubbo.demo.DemoService
                attributes,
                serviceDescriptor,
                this,
                null,
                serviceMetadata);
        //为调用服务创建代理对象
        ref = createProxy(map);//创建代理对象

        serviceMetadata.setTarget(ref);
        serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);
        repository.lookupReferredService(serviceMetadata.getServiceKey()).setProxyObject(ref);

        initialized = true;
        // dispatch a ReferenceConfigDestroyedEvent since 2.7.4
        dispatch(new ReferenceConfigDestroyedEvent(this));
    }

    /**
     * 根据服务引用信息生成指向远程服务地址的代理对象。生成的代理对象Invoker包含了网络连接、服务调用和重试等功能。和服务端不一样，在客户端，它可以一个远程的实现，也可以是一个集群的实现。
     *      注意，如果配置了服务同时注册多个注册中心，则会在这里被合并成一个Invoker
     *      第一步：通过持有远程服务实例生成Invoker,这个Invoker在客户端是核心的远程代理对象
     *      第二步：Invoker通过动态代理转换成实现用户接口的动态代理引用。
     * @param map  服务引用的配置参数：
     *              0 = {HashMap$Node@3155} "init" -> "false"
     *              1 = {HashMap$Node@3156} "side" -> "consumer"
     *              2 = {HashMap$Node@3157} "register.ip" -> "220.250.64.225"
     *              3 = {HashMap$Node@3158} "release" ->
     *              4 = {HashMap$Node@3159} "methods" -> "sayHello"
     *              5 = {HashMap$Node@3160} "lazy" -> "false"
     *              6 = {HashMap$Node@3161} "sticky" -> "false"
     *              7 = {HashMap$Node@3162} "dubbo" -> "2.0.2"
     *              8 = {HashMap$Node@3163} "pid" -> "73168"
     *              9 = {HashMap$Node@3164} "interface" -> "org.apache.dubbo.demo.StubService"
     *              10 = {HashMap$Node@3165} "timestamp" -> "1577351546755"
     * @return
     * 	1、如果是jvm内部调用：
     * 		定义一个jvm内部调用的URL:
     * 			protocol：Injvm
     * 			host：127.0.0.1
     * 			port：0
     * 			path：serviceName,eg：org.apache.dubbo.demo.StubService
     * 		protocol$Adapative对象会根据url的参数里的协议protocol获得对应的实现InjvmProtocol，并返回代理对象Invoker
     * 	2、如果不是jvm内部调用
     * 		获得远程调用的请求地址列表(注册中心的列表，如果是直连的话，则是直连地址的列表)：
     * 			a、如果<dubbo:reference>配置了url属性，则根据','号进行分割。
     * 				1）如果url未设置path属性，则设置path为服务名称，比如：org.apache.dubbo.demo.StubService
     * 				2）如果url是一个注册中心地址，则表示通过注册中心进行远程调用：则将请求参数拼接并作为url的refer属性
     * 				3）如果url不是一个注册中心地址的话，则表示直连：则会将url的键值配置和接口配置的参数进行合并
     * 			b、如果<dubbo:reference>没有配置url属性，则表示通过注册中心来进行远程调用
     * 				获取注册中心的地址列表urls
     * 				参数处理注册中心的地址url
     * 		遍历注册中心/直连地址，根据每个注册中心/直连地址生成一个用于相应的远程服务调用的代理对象invoker。
     *
     * 	生成invoker时候做了哪些工作了呢？
     * 		a、根据注册中心地址获得注册中心客户端实例，并向注册中心地址注册当前提供者，并订阅远程服务的configurators/providers/routers路径，用于监听消费的服务的配置变更
     * 		b、根据集群的负载均衡算法从注册中心的服务提供者列表中选举出一个服务提供者。
     * 		c、根据选举出来的服务提供者地址，创建一个NettyClient.
     * 		c、为选举出来的服务远程服务的调用的代理调用invoker绑定相应的过滤链，便于在服务调用过程中，进行过滤等处理。
     * 	3、如果消费端配置了check属性,则会校验远程服务代理对象invoker是否可用。
     * 	4、如果是多注册中心实例，则会通过Cluster将多个Invoker转换成一个Invoker
     * 	4、根据远程调用服务的代理对象invoker生成一个代理实例
     */
    private T createProxy(Map<String, String> map) {
        if (shouldJvmRefer(map)) {//判断是否injvm协议注册，是否内部访问
            // 创建一个jvm内部服务引用的 URL 对象
            URL url = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            // 引用服务，返回 Invoker 对象（协议REF_PROTOCOL或根据url参数查找匹配的InjvmProtocol并调用）
            invoker = REF_PROTOCOL.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {//如果不是内部调用，也就是远程调用的话
            urls.clear();
            if (url != null && url.length() > 0) { // 如果<dubbo:reference>配置了url属性,则可以通过点对点的直连，或者通过注册中心地址。
                String[] us = SEMICOLON_SPLIT_PATTERN.split(url);//按照','分割
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            url = url.setPath(interfaceName);//默认的path属性是接口名
                        }
                        if (UrlUtils.isRegistry(url)) {//如果url地址是注册中心,在注册中心地址后添加refer存储服务消费者元数据信息
                            urls.add(url.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        } else {//如果url地址是不是注册中心，也就是直连调用,如果多个直连服务器调用会使用负载均衡
                            urls.add(ClusterUtils.mergeUrl(url, map));//这里没有添加refer和注册中心，默认是dubbo会直接触发dubboProtocol进行远程消费，不会经过RegistryProtocol做服务发现
                        }
                    }
                }
            } else { //如果没有配置URL属性，则通过注册中心进行引用
                // 检查协议不能是inJvm协议
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {//如果不是local本地协议的话
                    checkRegistry();//检查注册中心，并加载注册中心URL列表
                    List<URL> us = ConfigValidationUtils.loadRegistries(this, false);//registry://localhost:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=68831&qos.port=33333&registry=zookeeper&timestamp=1575935377133
                    if (CollectionUtils.isNotEmpty(us)) {//检查注册中心列表不为空
                        for (URL u : us) {
                            URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);//检查是否配置了监控中心地址
                            if (monitorUrl != null) {
                                map.put(MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                            }
                            urls.add(u.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        }
                    }
                    if (urls.isEmpty()) {//registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=68831&qos.port=33333&refer=check%3Dfalse%26dubbo%3D2.0.2%26init%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26lazy%3Dfalse%26methods%3DsayHello%2CsayHelloAsync%26pid%3D68831%26register.ip%3D192.168.0.104%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1575935066048&registry=zookeeper&timestamp=1575935377133
                        throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                    }
                }
            }
            //url：registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=41141&qos.port=33333&refer=dubbo%3D2.0.2%26init%3Dfalse%26interface%3Dorg.apache.dubbo.demo.StubService%26lazy%3Dfalse%26methods%3DsayHello%26pid%3D41141%26register.ip%3D220.250.64.225%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1576810746199&registry=zookeeper&timestamp=1576812247269
            if (urls.size() == 1) {//如果是单注册中心消费，直接创建消费者的代理对象，顺序：Protocol$Adaptive -> ProtocolFilterWrapper -> ProtocolListenerWrapper
                invoker = REF_PROTOCOL.refer(interfaceClass, urls.get(0));
            } else {//如果是多个注册中心，则逐个获取注册中心的服务，并添加到Invokers列表
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                for (URL url : urls) {
                    invokers.add(REF_PROTOCOL.refer(interfaceClass, url));
                    if (UrlUtils.isRegistry(url)) {
                        registryURL = url; // use last registry url
                    }
                }
                if (registryURL != null) { //通过Cluster将多个invoker转换成一个Invoker
                    // for multi-subscription scenario, use 'zone-aware' policy by default
                    URL u = registryURL.addParameterIfAbsent(CLUSTER_KEY, ZoneAwareCluster.NAME);
                    // The invoker wrap relation would be like: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, routing happens here) -> Invoker
                    invoker = CLUSTER.join(new StaticDirectory(u, invokers));
                } else { // not a registry url, must be direct invoke.
                    invoker = CLUSTER.join(new StaticDirectory(invokers));
                }
            }
        }
        //如果配置了check=true,则需要检查提供者服务是否可用
        if (shouldCheck() && !invoker.isAvailable()) {
            throw new IllegalStateException("Failed to check the status of the service "
                    + interfaceName
                    + ". No provider available for the service "
                    + (group == null ? "" : group + "/")
                    + interfaceName +
                    (version == null ? "" : ":" + version)
                    + " from the url "
                    + invoker.getUrl()
                    + " to the consumer "
                    + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        /**
         * @since 2.7.0
         * ServiceData Store
         */
        String metadata = map.get(METADATA_KEY);
        WritableMetadataService metadataService = WritableMetadataService.getExtension(metadata == null ? DEFAULT_METADATA_STORAGE_TYPE : metadata);
        if (metadataService != null) {
            URL consumerURL = new URL(CONSUMER_PROTOCOL, map.remove(REGISTER_IP_KEY), 0, map.get(INTERFACE_KEY), map);
            metadataService.publishServiceDefinition(consumerURL);
        }
        //  创建 Service 代理对象
        return (T) PROXY_FACTORY.getProxy(invoker);
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     * 检查或更新订阅配置
     * 1、引用接口不能为空
     * 2、整合消费者的其它配置：application、module、monitor、registries
     * 3、判断是否配置了generic泛化调用
     * 4、检查接口和引用方法的匹配
     * 5、
     * */
    public void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {//org.apache.dubbo.demo.DemoService
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        //整合消费者的其它配置：application、module、monitor、registries
        completeCompoundConfigs();
        // get consumer's global configuration
        checkDefault();
        this.refresh();//根据当前环境，加载当前服务的各种属性的值。
        if (getGeneric() == null && getConsumer() != null) {//如果refrence里没有配置generic，则使用consumer的配置
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(generic)) {//如果是个泛型调用
            interfaceClass = GenericService.class;
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, getMethods());
        }//从配置文件里查找接口的配置，顺序依次：System系统变量配置->-Ddubbo.resolve.file 文件配置->${user.home}/dubbo-resolve.properties 配置
        resolveFile();
        ConfigValidationUtils.validateReferenceConfig(this);//检查服务引用的属性配置，这里主要是检查命名的规范，包括长度和字符规范
        appendParameters();
    }


    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     * 判断是否本地引用
     * 1、判断是否配置了 injvm 属性，
     *      如果配置了injvm=true，则表示本地引用，返回true
     *      如果没有配置injvm属性，如果配置了url，则肯定不是本地引用，直连，所以这里返回true
     *      如果没有配置injvm属性，且没有配置了url，则根据具体的业务逻辑判断是否是本地引用
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        if (isInjvm() == null) {//判断是 injvm 本地内部访问
            // if a url is specified, don't do local reference
            if (url != null && url.length() > 0) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.4
     */
    protected void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public void appendParameters() {
        URL appendParametersUrl = URL.valueOf("appendParameters://");
        List<AppendParametersComponent> appendParametersComponents = ExtensionLoader.getExtensionLoader(AppendParametersComponent.class).getActivateExtension(appendParametersUrl, (String[]) null);
        appendParametersComponents.forEach(component -> component.appendReferParameters(this));
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }
}

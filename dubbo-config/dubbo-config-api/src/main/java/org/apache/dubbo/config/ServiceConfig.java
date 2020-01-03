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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ServiceConfigExportedEvent;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.service.ServiceConfigBase;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.MULTICAST;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    //生成一个自适应的协议类
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    private DubboBootstrap bootstrap;

    /**
     * The urls of the services exported
     */
    private final List<URL> urls = new ArrayList<URL>();

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occured when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    /**
     * 暴露服务的入口
     * 1、如果不想暴露服务，则可以直接设置export=false，或者设置provider.export=false
     * 2、校验并更新服务提供者配置
     * 3、更新服务元信息对象的相关属性值
     * 4、判断是否延迟暴露，如果配置了延迟暴露属性，则通过延迟任务来延迟暴露，否则直接暴露服务
     */
    public synchronized void export() {
        /***
         * 服务进行暴露
         *   0 = {HashMap$Node@2607} "org.apache.dubbo.demo.EventNotifyService" -> "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="false" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@363042d7" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" dynamic="true" deprecated="false" group="cn" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
         *   1 = {HashMap$Node@2608} "org.apache.dubbo.demo.AsyncService2" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService2" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService2" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl2@366ac49b" interface="org.apache.dubbo.demo.AsyncService2" uniqueServiceName="org.apache.dubbo.demo.AsyncService2" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService2" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService2" valid="true" />"
         *   2 = {HashMap$Node@2609} "org.apache.dubbo.demo.CallbackService" -> "<dubbo:service beanName="org.apache.dubbo.demo.CallbackService" exported="false" unexported="false" path="org.apache.dubbo.demo.CallbackService" ref="org.apache.dubbo.demo.provider.CallbackServiceImpl@6ad59d92" interface="org.apache.dubbo.demo.CallbackService" uniqueServiceName="org.apache.dubbo.demo.CallbackService" prefix="dubbo.service.org.apache.dubbo.demo.CallbackService" dynamic="true" deprecated="false" connections="1" callbacks="1000" id="org.apache.dubbo.demo.CallbackService" valid="true" />"
         *   3 = {HashMap$Node@2610} "org.apache.dubbo.demo.AsyncService" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl@56f0cc85" interface="org.apache.dubbo.demo.AsyncService" uniqueServiceName="org.apache.dubbo.demo.AsyncService" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService" valid="true" />"
         *   4 = {HashMap$Node@2611} "org.apache.dubbo.demo.StubService" -> "<dubbo:service beanName="org.apache.dubbo.demo.StubService" exported="false" unexported="false" path="org.apache.dubbo.demo.StubService" ref="org.apache.dubbo.demo.provider.StubServiceImpl@62e20a76" interface="org.apache.dubbo.demo.StubService" uniqueServiceName="org.apache.dubbo.demo.StubService" prefix="dubbo.service.org.apache.dubbo.demo.StubService" dynamic="true" deprecated="false" stub="org.apache.dubbo.demo.StubServiceStub" id="org.apache.dubbo.demo.StubService" valid="true" />"
         *   5 = {HashMap$Node@2612} "org.apache.dubbo.demo.MockService" -> "<dubbo:service beanName="org.apache.dubbo.demo.MockService" exported="false" unexported="false" path="org.apache.dubbo.demo.MockService" ref="org.apache.dubbo.demo.provider.MockServiceImpl@2cc44ad" interface="org.apache.dubbo.demo.MockService" uniqueServiceName="org.apache.dubbo.demo.MockService" prefix="dubbo.service.org.apache.dubbo.demo.MockService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.MockService" valid="true" />"
         *   6 = {HashMap$Node@2613} "org.apache.dubbo.demo.DemoService" -> "<dubbo:service beanName="org.apache.dubbo.demo.DemoService" exported="false" unexported="false" path="org.apache.dubbo.demo.DemoService" ref="org.apache.dubbo.demo.provider.DemoServiceImpl@44b3606b" interface="org.apache.dubbo.demo.DemoService" uniqueServiceName="org.apache.dubbo.demo.DemoService" prefix="dubbo.service.org.apache.dubbo.demo.DemoService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.DemoService" valid="true" />"
         *   7 = {HashMap$Node@2614} "org.apache.dubbo.demo.InjvmService" -> "<dubbo:service beanName="org.apache.dubbo.demo.InjvmService" exported="false" unexported="false" path="org.apache.dubbo.demo.InjvmService" ref="org.apache.dubbo.demo.provider.InjvmServiceImpl@1477089c" interface="org.apache.dubbo.demo.InjvmService" uniqueServiceName="org.apache.dubbo.demo.InjvmService" prefix="dubbo.service.org.apache.dubbo.demo.InjvmService" dynamic="true" deprecated="false" protocolIds="injvm" id="org.apache.dubbo.demo.InjvmService" valid="true" />"
         */
        if (!shouldExport()) {//检查是否需要暴露服务
            return;
        }

        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            bootstrap.init();
        }
        //校验更新服务提供者配置
        checkAndUpdateSubConfigs();

        //init serviceMetadata
        serviceMetadata.setVersion(version);
        serviceMetadata.setGroup(group);
        serviceMetadata.setDefaultGroup(group);
        serviceMetadata.setServiceType(getInterfaceClass());
        serviceMetadata.setServiceInterfaceName(getInterface());
        serviceMetadata.setTarget(getRef());
        //是否延迟暴露服务
        if (shouldDelay()) {
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            //延迟暴露服务
            doExport();
        }
    }

    /***
     * 1、初始化provider、module、registries、protocols、configCenter等配置
     * 2、如果配置的protocol协议不止injvm注册，则需要是否配置了合法的配置中心
     * 3、通过各种环境配置设置当前服务对象ServiceConfig的属性值：
     *      SystemConfiguration -> AppExternalConfiguration-> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
     * 4、如果服务的实现类是一个 GenericService ，则标记下这个服务的类型：
     *      interfaceClass = GenericService.class
     *      generic=true
     * 5、如果服务的实现类不是一个 GenericService，则根据服务接口获得Class对象，并为MethodConfig绑定接口信息
     * 6、如果服务配置了local或者stub属性，则需要进行校验：
     *      绑定的local或stub类是否存在。
     *      绑定的local或stub类必须实现接口interfaceClass，且 必须存在一个只有一个参数类型为interfaceClass的构造函数
     * 7、将服务提供者参数配置拼接到URL里
     *
     */
    private void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly on global scope
        //初始化provider、module、registries、protocols、configCenter等配置
        completeCompoundConfigs();
        //验证并初始化provider对象
        checkDefault();
        //验证并初始化protocol对象
        checkProtocol();
        // if protocol is not injvm checkRegistry
        //配置的protocol协议不止injvm注册
        if (!isOnlyInJvm()) {
            //检查注册中心，将registryId转换成registers对象
            //如果配置文件里未配置register信息的话，则根据系统属性dubbo.registry.address获得注册中心地址，并用逗号分割
            checkRegistry();
        }
        //设置加载service对应属性配置的优先级：
        //      SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
        //      SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
        //校验ServiceConfig里的属性是否都存在getter/setter方法

        this.refresh();
        //接口名不能为空
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        //实例类型如果为泛化，则更新interfaceClass 和 generic
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                //获得接口类型的Class对象
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //如果配置了子标签dubbo:method，则要检查方法是否属于该暴露接口
            checkInterfaceAndMethods(interfaceClass, getMethods());
            //检查实例对象必须是一个 interfaceClass 类型的对象
            checkRef();//检查ref属性
            generic = Boolean.FALSE.toString();
        }
        //通过local属性，设为true，表示使用缺省代理类名，即：接口名 + Local后缀，服务接口客户端本地代理类名，
        // 用于在客户端执行本地逻辑，如本地缓存等，该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：public XxxServiceLocal(XxxService xxxService)
        // 我们口语看到这个类必须实现被代理接口，且和接口放在同一个目录下，所以也会被消费者打包到本地，所以访问Local后缀的实现类，对消费者来说是本地访问，只不过该本地对象里持有远程代理对象的引用
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";//拼接对应的local类名
            }
            Class<?> localClass;
            try {
                //根据local名称加载指定的Class
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //org.apache.dubbo.demo.HelloService
        //org.apache.dubbo.demo.HelloServiceStub
        //通过配置stub配置本地存根，stub类也要实现对应的接口，且和接口方法一块。并提供一个包含接口参数的构造方法，用来注入接口代理对象
        ///客户端如果配置了stub，就会把代理对象注入到这个stub实例中，并调用这个stub实例,再通过这个伪装类来调用被代理对象的方法
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //检查当前服务对应的stub和local的合法性：stub或local必须实现接口interfaceClass，且 必须存在一个只有一个参数类型为interfaceClass的构造函数
        checkStubAndLocal(interfaceClass);
        //规范并校验mock属性
        ConfigValidationUtils.checkMock(interfaceClass, this);
        ConfigValidationUtils.validateServiceConfig(this);
        appendParameters();
    }

    /***
     * 暴露服务(采用锁来避免并发和重复暴露服务)
     *  1、判断是否需要暴露服务：
     *      a、对于直连的话，则不需要暴露服务。
     *      b、如果一个服务已暴露，则无需重新暴露
     *  2、初始化serviceConfig对象的path属性，如果没有配置path属性的话，则默认使用接口名
     *  3、开始暴露服务
     */
    protected synchronized void doExport() {
        //判断是否需要暴露服务(开发阶段用直连)
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {//注意exported是一个可见性变量
            return;
        }
        exported = true;
        //如果没有指定path的话，默认是服务名
        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        doExportUrls();

        // dispatch a ServiceConfigExportedEvent since 2.7.4
        dispatch(new ServiceConfigExportedEvent(this));
    }

    /***
     * 1、获取或创建一个单例对象 ServiceRepository，并
     *     a、在ServiceRepository.services维护key-value：
     *          key：接口全路径名
     *          value：接口对应的描述对象 ServiceDescriptor
     *     b、在ServiceRepository.providers 维护key-value：
     *          key：接口唯一标示(interfaceName+group+version)
     *          value：ProviderModel 模型
     * 2、根据服务提供者配置信息创建注册地址url列表
     * 3、遍历注册协议，针对每个协议都向注册中心列表注册服务
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        //获得ServiceRepository对象，这是一个全局变量，所有服务提供者共享一个ServiceRepository
        /**
         *前：repository
         * 0 = {ConcurrentHashMap$MapEntry@3554} "org.apache.dubbo.rpc.service.EchoService" ->
         * 1 = {ConcurrentHashMap$MapEntry@3555} "org.apache.dubbo.rpc.service.GenericService" ->
         * 2 = {ConcurrentHashMap$MapEntry@3556} "org.apache.dubbo.monitor.MetricsService" ->
         * 3 = {ConcurrentHashMap$MapEntry@3557} "org.apache.dubbo.monitor.MonitorService" ->
         */
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        //通过ServiceRepository 维护服务提供者接口接口和接口描述信息
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        /**
         * 后：repository
         * 0 = {ConcurrentHashMap$MapEntry@3585} "org.apache.dubbo.demo.EventNotifyService" ->
         * 1 = {ConcurrentHashMap$MapEntry@3586} "org.apache.dubbo.rpc.service.EchoService" ->
         * 2 = {ConcurrentHashMap$MapEntry@3587} "org.apache.dubbo.rpc.service.GenericService" ->
         * 3 = {ConcurrentHashMap$MapEntry@3588} "org.apache.dubbo.monitor.MetricsService" ->
         * 4 = {ConcurrentHashMap$MapEntry@3589} "org.apache.dubbo.monitor.MonitorService" ->
         */
        repository.registerProvider(
                getUniqueServiceName(),//interfaceName+group+version
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );
        /***
         * 加载注册中心的数组。
         * URL：registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=5410
         *          &qos.port=22222&registry=zookeeper&timestamp=1575332257645
         */
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);
        //遍历协议配置 <dubbo:protocol name="dubbo" valid="true" id="dubbo" prefix="dubbo.protocols." />
        //按照配置的协议，每种协议都会暴露当前服务
        for (ProtocolConfig protocolConfig : protocols) {
            String pathKey = URL.buildKey(getContextPath(protocolConfig)//org.apache.dubbo.demo.DemoService
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path.
            //记录暴露的服务：保证一个服务路径只会暴露一次
            repository.registerService(pathKey, interfaceClass);
            // TODO, uncomment this line once service key is unified
            serviceMetadata.setServiceKey(pathKey);
            //根据某种协议暴露服务
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    /***
     * 开始暴露服务
     * @param protocolConfig
     * @param registryURLs
     *  dubbo在进行服务暴露的时候，主要分成两个步骤：
     *      第一步：根据暴露的服务接口类型和实际的实例引用ref使用代理方式转换成Invoker对象。
     *      第二步：生成的Invoker实例会通过具体的协议转换成Exporter
     * 如果是远程暴露的话，顺序依次是：
     *      向注册中心注册自己：Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol
     *      =>
     *      注册中心注册完自己后，把自己暴露出去：Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol
     *
     *  1、根据配置信息组装用于构建URL的参数Map<String, String> map（这些信息主要来源于：application/mudule/provider/protocol/service）
     *  2、根据map构建用于暴露服务的URL对象。
     *  3、代理工厂会根据被引用的实例ref、暴露的服务生成一个代理对象Invoker（实际上是一个AbstractProxyInvoker），后续真实的方法调用都会交给代理对象，由代理对象转发给服务ref调用：
     *        JavassistProxyFactory：创建wrapper子类，在子类中实现invokeMethod方法，方法内为每个ref方法做参数名和参数校验，然后直接调用，减少了反射调用的开销
     *        JdkProxyFactory：通过反射获取真实对象的方法，然后进行调用即可
     *   4、暴露服务
     *      a、本地暴露：直接在本地内存里JVM协议暴露（存储在本地内存里即可）
     *      b、远程暴露：
     *          使用注册中心暴露：zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.44.56%3A20880%2Forg.apache.dubbo.demo.AsyncService2%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.AsyncService2%26bind.ip%3D192.168.44.56%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.AsyncService2%26methods%3DsayHello%26pid%3D22219%26release%3D%26side%3Dprovider%26timestamp%3D1576066567127&pid=22219&qos.port=22222&timestamp=1576066538747
     *              1）会取出具体协议，比如Zookeeper.
     *              2）取出export对应的暴露服务的具体地址URL
     *              3）根据服务的ULR对应的协议（默认为DUBBO）进行服务暴露。
     *              4）当服务暴露成功后把服务数据注册到zookeeper。
     *          没有使用注册中心暴露： dubbo://192.168.0.103:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.103&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=78763&release=&side=provider&timestamp=1574988715703
     *              根据服务的ULR对应的协议（默认为DUBBO）进行服务暴露。
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();//协议名称 dubbo
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;//默认协议名称dubbo
        }
        //map主要是用于读取并组装配置，用于后续构造URL
        Map<String, String> map = new HashMap<String, String>();
        //side：provider，后面的过滤器链会根据side来判断是否生效
        map.put(SIDE_KEY, PROVIDER_SIDE);
        //添加服务配置信息
        /***
         * 1、dubbo：协议版本号："dubbo" -> "2.0.2"
         * 2、release：服务版本："release" -> ''
         * 3、timestamp：时间。"timestamp" -> "1576737965762"
         * 4、pid：进程号。"pid" -> "686"
         **/
        ServiceConfig.appendRuntimeParameters(map);
        /***
         * 添加MetricsConfig的配置
         */
        AbstractConfig.appendParameters(map, metrics);
        /***
         * 添加application的配置
         */
        AbstractConfig.appendParameters(map, application);
        /***
         * 添加module的配置
         */
        AbstractConfig.appendParameters(map, module);
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        /***
         * 添加provider的配置
         */
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        /***
         * 添加protocol配置
         */
        AbstractConfig.appendParameters(map, this);
        //如果配置了<dubbo:method>标签
        /***
         * 1、遍历dubbo:method标签，并将 MethodConfig 对象，添加到 `map` 集合中
         * 2、当 配置了 `MethodConfig.retry = false` 时，强制方法禁用重试
         * 3、遍历dubbo:method的子标签dubbo:arguments标签
         * 4、解析argument的参数索引位置和参数类型
         * 5、校验dubbo:arguments中的参数和索引是否和接口方法里相应的方法的参数和位置保持一致
         */
        if (CollectionUtils.isNotEmpty(getMethods())) {
            for (MethodConfig method : getMethods()) {
                // 将 MethodConfig 对象，添加到 `map` 集合中。
                AbstractConfig.appendParameters(map, method, method.getName());
                // 当 配置了 `MethodConfig.retry = false` 时，强制禁用重试
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                // 将 ArgumentConfig 对象数组，添加到 `map` 集合中。
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // 获得参数标签的type类型
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // 检查接口里相应的方法，查找方法和dubbo:method的方法名一样的方法
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        //获得方法的参数类型数组
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            // 校验dubbo:arguments中的参数和索引是否和接口方法里相应的方法的参数和位置保持一致
                                            //将 ArgumentConfig 对象，添加到 `map` 集合中。`${methodName}.${index}`
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    AbstractConfig.appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) { // 指定单个参数的位置
                            // 将 ArgumentConfig 对象，添加到 `map` 集合中。
                            //`${methodName}.${index}`
                            AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }
        //判断是否是泛化类型的服务，是否指定了 'generic' 属性，如果指定了generic，则针对所有方法，都走的泛型
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);//获得版本号
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            //获取接口所有方法名称列表，不包含来自Object对象的方法。然后用','拼接
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {//{"sayHello","sayHelloAsync"}
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        if (!ConfigUtils.isEmpty(token)) {//判断提供者服务是否配置了token
            if (ConfigUtils.isDefault(token)) {//如果token=true或者token=default，则为服务分配一个随机值
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }
        //init serviceMetadata attachments 根据上面的参数，初始化隐参
        serviceMetadata.getAttachments().putAll(map);
        //protocolConfig：<dubbo:protocol name="dubbo" valid="true" id="dubbo" prefix="dubbo.protocols." />
        // registryURLs： registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=78763&qos.port=22222&registry=zookeeper&timestamp=1574988712664
        String host = findConfigedHosts(protocolConfig, registryURLs, map);//获得配置的地址，默认本机地址 192.168.0.103
        Integer port = findConfigedPorts(protocolConfig, name, map);//20880
        //dubbo://192.168.0.103:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.103
        // &bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync
        // &pid=78763&release=&side=provider&timestamp=1574988715703
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);
        // You can customize Configurator to append extra parameters
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }
        /***
         * 获得scope配置属性(可以在providere/service里配置)
         * 1、如果scope=none，则不需要暴露服务(不管是jvm内部还是注册中心)
         * 2、如果scope=remote，则不会在jvm内部暴露这个服务,但是会通过注册中心注册服务。
         *      如果scope!=remote,则会在jvm内部暴露这个服务
         * 3、如果scope=local，那么就不会通过注册中心暴露服务，但是会在jvm内部暴露这个服务。
         * 4、如果scope没设置，或者是默认的，则会在jvm内部暴露这个服务,也会通过注册中心注册服务。
         * 5、将invoker包装成一个exporter，并注册到相应的注册中心，同时会监听对应的路径：/dubbo/org.apache.dubbo.demo.DemoService/configurators
         *
         */
        //url：dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=6134&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576739514406
        String scope = url.getParameter(SCOPE_KEY);
        //如果scope=none，则不暴露url配置
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {//本地暴露

            // 在本地暴露服务，这个时候协议是dubbo，如果配置了remote配置，则只支持远程注册中心注册，就不需要本地注册。
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {//如果sopce未配置为remote，则先进行本地暴露(方便本地直连测试)
                exportLocal(url);
            }
            /***
             * 遍历注册中心开始进行远程服务注册
             * 1、如果服务提供者的scope属性是local属性，则无需远程暴露服务了。
             * 2、服务提供者通过dynamic属性，用于控制服务是否动态注册，如果设为false，注册后将显示后disable状态，需人工启用，并且服务提供者停止时，也不会自动取消册，需人工禁用。
             * 3、获得服务提供者的代理配置属性proxy，如果未配置了生成代理对象的方式，则默认是javassist。（用户可以在<dubbo:service>里通过proxy属性控制生成代理对象的方式）
             * 4、根据服务提供者的接口和ref指向的实例，通过代理工厂使用特定的代理方式创建一个代理对象
             * 5、使用RegistryProtocol，将代理对象注册到注册中心并通过NettyServer暴露给外服务调用，并返回一个暴露的对象Exporter
             *      RegistryProtocol在将自己服务注册地址注册到注册中心的同时，会调用DubboProtocol将具体服务通过NettyServer暴露出去，方便其它服务调用。
             *      整个暴露流程如下：
             *          Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol  //该链路主要是将服务提供者自己的地址注册到注册中心上去，便于消费者发现
             *          =>
             *          Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol  //真正的将自己通过网络服务暴露出去
             *
             */
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (CollectionUtils.isNotEmpty(registryURLs)) {//如果注册地址不为空
                    //遍历注册中心 registryURL(注意它跟上面的url的区别，上面的url是服务提供者在服务端的地址)：registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=6134&qos.port=22222&registry=zookeeper&timestamp=1576739508116
                    for (URL registryURL : registryURLs) {
                        //如果服务提供者的暴露协议是injvm，则表示在虚拟机内部调用，就无需远程暴露
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            continue;
                        }
                        //"dynamic" ：服务是否动态注册，如果设为false，注册后将显示后disable状态，需人工启用，并且服务提供者停止时，也不会自动取消册，需人工禁用。
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));//dubbo://220.250.64.225:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
                        URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);//获得监控中心地址
                        if (monitorUrl != null) {//判断监控地址，如果配置了，则服务的调用信息会上报到监控中心
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            if (url.getParameter(REGISTER_KEY, true)) {//从url中获取register属性，默认是true
                                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                            } else {
                                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                            }
                        }
                        //url: dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=6134&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576739514406
                        String proxy = url.getParameter(PROXY_KEY);//从url中获取 proxy 属性，默认是javassist。从这里我们可以知道，可以通过service的proxy来指定生成代理对象
                        if (StringUtils.isNotEmpty(proxy)) {//如果provider或service里设置了proxy值
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }
                        /***
                         *
                         * 1、根据以下三个参数创建并缓存Invoker代理对象：代理工厂会根据被引用的实例ref、暴露的服务生成一个代理对象Invoker（实际上是一个AbstractProxyInvoker），后续真实的方法调用都会交给代理对象，由代理对象转发给服务ref调用。
                         *      ref：被代理的实例
                         *      interfaceClass：服务接口类型
                         *      registryURL：注册地址信息对象
                         *    JavassistProxyFactory：创建wrapper子类，在子类中实现invokeMethod方法，方法内为每个ref方法做参数名和参数校验，然后直接调用，减少了反射调用的开销
                         *    JdkProxyFactory：通过反射获取真实对象的方法，然后进行调用即可
                         * 2、把代理对象包装成一个DelegateProviderMetaDataInvoker
                         * 3、通过RegistryProtocol 暴露服务（RegistryProtocol在将自己服务注册地址注册到注册中心的同时，会调用DubboProtocol将具体服务通过NettyServer暴露出去，方便其它服务调用）
                         *      整个流程如下：
                         *         Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => RegistryProtocol
                         *         =>
                         *         Protocol$Adaptive => ProtocolFilterWrapper => ProtocolListenerWrapper => DubboProtocol
                         */
                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        //维护已暴露的服务
                        exporters.add(exporter);
                    }
                } else {//当配置注册中心为 "N/A" 时，表示即使远程暴露服务，也不向注册中心注册。这种方式用于被服务消费者直连服务提供者，参见文档 http://dubbo.apache.org/zh-cn/docs/user/demos/explicit-target.html 。主要用于开发测试环境使用。
                    if (logger.isInfoEnabled()) {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                    // 使用 ProxyFactory 创建 Invoker 对象
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                    // 使用 Protocol 暴露 Invoker 对象
                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
                /**
                 * @since 2.7.0
                 * ServiceData Store
                 */
                WritableMetadataService metadataService = WritableMetadataService.getExtension(url.getParameter(METADATA_KEY, DEFAULT_METADATA_STORAGE_TYPE));
                if (metadataService != null) {
                    metadataService.publishServiceDefinition(url);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm 本地暴露
     * 1、根据服务提供者的url，生成一个协议是injvm,端口是0的本地url对象（因为本地注册的话，不要向注册中心发起请求，所以这边协议是injvm）
     * 2、代理工厂会根据接口和被代理对象为本地暴露协议生成一个代理的对象。
     * 3、将代理对象使用InjvmProtocol进行暴露(其实是维护到一块内存里)：
     *      调用涮需：方法的调用顺序是：Protocol$Adaptive
     *          => ProtocolFilterWrapper
     *          => ProtocolListenerWrapper （这一步会根据配置把一个用于监听export或unexport行为的ExportListener和exporter进行绑定）
     *          => InjvmProtocol 。
     * 4、本地缓存已暴露的对象
     */
    private void exportLocal(URL url) {
        //旧的：url=dubbo://192.168.0.103:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.103&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=78763&release=&side=provider&timestamp=1574988715703
        //新的：url=injvm://127.0.0.1          /org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.108&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=5410&release=&side=provider&timestamp=1575332340328
        // 创建本地 注册 URL
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)//固定是：injvm协议
                .setHost(LOCALHOST_VALUE)//host：127.0.0.1
                .setPort(0)//端口号 0
                .build();
        Exporter<?> exporter = protocol.export(//将服务包装成对象，放到本地内存地址里
                PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local)//代理对象工厂PROXY_FACTORY会帮我们生成代理对象
        );
        exporters.add(exporter);//缓存暴露的服务
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {//检查是否只允许jvm内部访问
        return getProtocols().size() == 1//如果只配置了一个protocol标签，且协议是injvm
                && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }


    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig ：<dubbo:protocol name="dubbo" valid="true" id="dubbo" prefix="dubbo.protocols." />
     * @param registryURLs：registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=78763&qos.port=22222&registry=zookeeper&timestamp=1574988712664
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig,
                                     List<URL> registryURLs,
                                     Map<String, String> map) {
        boolean anyhost = false;
        //从协议protocolConfig获得DUBBO_IP_TO_BIND属性
        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();//从协议中获得host
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();//查找
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    logger.info("No valid ip found from environment, try to find valid host from DNS.");
                    hostToBind = InetAddress.getLocalHost().getHostAddress();//获得本机地址
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        for (URL registryURL : registryURLs) {
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try (Socket socket = new Socket()) {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig,
                                      String name,
                                      Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }//根据 ExtensionLoader.getExtensionLoader(Protocol.class) 获得对应ExtensionLoader,再根据协议名称获得对应的拓展类实现
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(port)) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    private Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    /***
     * 把服务提供者的配置添加到url里
     */
    public void appendParameters() {
        URL appendParametersUrl = URL.valueOf("appendParameters://");
        List<AppendParametersComponent> appendParametersComponents = ExtensionLoader.getExtensionLoader(AppendParametersComponent.class).getActivateExtension(appendParametersUrl, (String[]) null);
        appendParametersComponents.forEach(component -> component.appendExportParameters(this));
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.4
     */
    private void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }
}
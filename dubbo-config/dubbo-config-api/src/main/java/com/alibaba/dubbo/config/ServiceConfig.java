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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ProviderModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.ServiceClassHolder;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

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

import static com.alibaba.dubbo.common.utils.NetUtils.LOCALHOST;
import static com.alibaba.dubbo.common.utils.NetUtils.getAvailablePort;
import static com.alibaba.dubbo.common.utils.NetUtils.getLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;
    /***
     * 加载协议的拓展类
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
    /***
     * jdk=com.alibaba.dubbo.rpc.proxy.jdk.JdkProxyFactory
     * javassist=com.alibaba.dubbo.rpc.proxy.javassist.JavassistProxyFactory
     * 加载代理对象工厂的拓展类
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    private final List<URL> urls = new ArrayList<URL>();
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();
    // interface type
    private String interfaceName;
    private Class<?> interfaceClass;
    // reference to interface impl
    private T ref;
    // service name
    private String path;
    // method configuration
    private List<MethodConfig> methods;
    private ProviderConfig provider;
    private transient volatile boolean exported;

    private transient volatile boolean unexported;

    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }

    @Deprecated
    private static final List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static final List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static final ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static final ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }

    public URL toUrl() {
        return urls == null || urls.isEmpty() ? null : urls.iterator().next();
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

    /***
     * 暴露接口(将服务注册到注册中心上)
     * 1、检查是否配置provider属性
     *      a、如果配置了provider属性，检查对应的provider是否配置了export和delay属性
     *          export:默认为true,如果export=false,则不暴露服务
     *          delay:是否延迟暴露，如果配置了delay，则采用ScheduledExecutorService定时任务在指定时间后暴露该服务
     * 2、开始暴露服务
     */

    public synchronized void export() {
        //如果provider不为空，则获取provider的export和delaY属性
        if (provider != null) {
            if (export == null) {
                export = provider.getExport();
            }
            if (delay == null) {
                delay = provider.getDelay();
            }
        }
        //如果接口已经暴露过一次，不能重复暴露
        if (export != null && !export) {
            return;
        }
        //判断是否延迟暴露，如果配置了延迟暴露的时间，则通过定时任务在指定时间之后暴露(可以分散暴露服务)
        if (delay != null && delay > 0) {
            delayExportExecutor.schedule(new Runnable() {
                public void run() {
                    doExport();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            doExport();
        }
    }

    /***
     * 暴露服务(采用锁来避免兵法和重复暴露服务)
     *      1、检查是否允许暴露，又或者已经暴露过
     *      2、检查是否有必要根据配置文件dubbo.properties来初始化provider（如果没有显式的配置provider，则会根据dubbo.properties来初始化provider）
     *      3、
     */
    protected synchronized void doExport() {//为了避免接口重复暴露，加锁
        //判断是否需要暴露服务(开发阶段用直连)
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        if (exported) {//如果已经export暴露服务了，就不需要再重复暴露
            return;
        }
        //修改接口的暴露状态，避免并发多次暴露，
        exported = true;
        //暴露的接口名称不能为空，因为rpc调用时候就是根据这个接口名称来调用
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        //检查是否需要根据dubbo.properties配置文件设置默认的provider
        checkDefault();
        if (provider != null) {//这段代码可以看到，provider/module/registry/modinor/protocol在配置文件中还是要有一定的顺序，要先于service配置
            if (application == null) {
                application = provider.getApplication();//<dubbo:application name="demo-provider" id="demo-provider" />
            }
            if (module == null) {
                module = provider.getModule();
            }
            if (registries == null) {//获得注册地址列表 <dubbo:registry address="zookeeper://127.0.0.1:2081" id="com.alibaba.dubbo.config.RegistryConfig" />
                registries = provider.getRegistries();//<dubbo:registry address="zookeeper://127.0.0.1:2181" id="com.alibaba.dubbo.config.RegistryConfig" />
            }
            if (monitor == null) {
                monitor = provider.getMonitor();
            }
            if (protocols == null) {//<dubbo:protocol name="dubbo" port="20880" id="dubbo" />
                protocols = provider.getProtocols();//<dubbo:protocol name="dubbo" port="20880" id="dubbo" />
            }
        }
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        if (application != null) {//application中配置的registry优先级比较低
            if (registries == null) {//说明registry的优先于高于application中的子元素
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        if (ref instanceof GenericService) {//判断引用是否是GenericService类型，GenericService是一个通用的服务测试框架，可通过GenericService调用所有服务实
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {//判断是否配置了generic属性
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {//interface com.alibaba.dubbo.demo.DemoService
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());//反射创建一个接口类的Class对象
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //如果配置了子标签dubbo:method，则要检查方法是否属于该暴露接口
            checkInterfaceAndMethods(interfaceClass, methods);
            checkRef();//检查ref是否实现该接口
            generic = Boolean.FALSE.toString();//设置generic=false，表示接口不是通用服务测试接口
        }
        //通过local属性，设为true，表示使用缺省代理类名，即：接口名 + Local后缀，服务接口客户端本地代理类名，
        // 用于在客户端执行本地逻辑，如本地缓存等，该本地代理类的构造函数必须允许传入远程代理对象，构造函数如：public XxxServiceLocal(XxxService xxxService)
        // 我们口语看到这个类必须实现被代理接口，且和接口放在同一个目录下，所以也会被消费者打包到本地，所以访问Local后缀的实现类，对消费者来说是本地访问，只不过该本地对象里持有远程代理对象的引用
        if (local != null) {//判断是否配置了本地调用
            if ("true".equals(local)) {//local=true;则local类的名称规范为interfaceName + "Local";
                local = interfaceName + "Local";
            }
            //根据local名称加载指定的类
            Class<?> localClass;
            try {
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            //如果local指定类未实现interfaceClass，则会抛异常
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        //com.mei.oms.service.BarService
        //com.mei.oms.service.BarServiceStub
        //通过配置stub配置本地伪装，stub类也要实现对应的接口。并提供一个包含接口参数的构造方法，用来注入接口代理对象
        ///客户端如果配置了stub，就会把代理对象注入到这个stub实例中，并调用这个stub实例,再通过这个伪装类来调用被代理对象的方法
        if (stub != null) {//和local一样
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        checkApplication();
        checkRegistry();
        checkProtocol();
        appendProperties(this);
        checkStubAndMock(interfaceClass);
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }
        doExportUrls();//开始暴露接口
        ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
        ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
    }
    //检查是否配置ref属性，且ref是否指向的实现类是否是接口的实例
    private void checkRef() {
        // dubbo:service的ref属性不能为空，且必须实现Class属性所指定的接口
        if (ref == null) {//判断dubbo配置中的ref属性是否为空
            throw new IllegalStateException("ref not allow null!");
        }
        if (!interfaceClass.isInstance(ref)) {//检查ref属性指向的类是否是接口的指向类
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (exporters != null && !exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /***
     * 开始根据注册地址url，将接口暴露到指定的注册地址上,如果有多种协议，或者有多个中心，接口会通过各种协议在各个注册中心暴露服务。消费端消费时，指定的注册中心和协议要和生产者匹配
     * 获取注册地址url
     */
    private void doExportUrls() {
        List<URL> registryURLs = loadRegistries(true);
        //<dubbo:protocol name="dubbo" port="20880" id="dubbo" />
        for (ProtocolConfig protocolConfig : protocols) {//根据协议进行注册暴露到指定的地址上，根据每一种协议分别在各个注册中心上暴露接口
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }
    //根据协议和url暴露接口
    //<dubbo:protocol name="dubbo" port="20880" id="dubbo" />
    //registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.0&pid=25796&qos.port=22222&registry=zookeeper&timestamp=1527337222654
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        String name = protocolConfig.getName();//获得协议名称name=dubbo
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }

        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);//生产者
        map.put(Constants.DUBBO_VERSION_KEY, Version.getVersion());//接口版本
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));//当前时间:timestamp
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        appendParameters(map, application);//获取application的配置，并放到map里
        appendParameters(map, module);//获取module的配置，并放到map里
        appendParameters(map, provider, Constants.DEFAULT_KEY);////获取provider的配置，并放到map里
        appendParameters(map, protocolConfig);//获取protocol的配置，并放到map里
        appendParameters(map, this);//获取service的配置，并放到map里
        /**map:
         * 0 = {HashMap$Node@3121} "side" -> "provider"
         * 1 = {HashMap$Node@3122} "bind.ip" -> "192.168.1.13"
         * 2 = {HashMap$Node@3123} "application" -> "demo-provider"
         * 3 = {HashMap$Node@3124} "methods" -> "sayHello"
         * 4 = {HashMap$Node@3125} "qos.port" -> "22222"
         * 5 = {HashMap$Node@3126} "dubbo" -> "2.0.0"
         * 6 = {HashMap$Node@3127} "pid" -> "25796"
         * 7 = {HashMap$Node@3128} "interface" -> "com.alibaba.dubbo.demo.DemoService"
         * 8 = {HashMap$Node@3129} "generic" -> "false"
         * 9 = {HashMap$Node@3130} "bind.port" -> "20880"
         * 10 = {HashMap$Node@3131} "timestamp" -> "1527337237218"
         * 11 = {HashMap$Node@3132} "anyhost" -> "true"
         *
         */
        if (methods != null && !methods.isEmpty()) {
            for (MethodConfig method : methods) {
                appendParameters(map, method, method.getName());
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                List<ArgumentConfig> arguments = method.getArguments();
                if (arguments != null && !arguments.isEmpty()) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }
        //判断协议是否是generic通用测试服务框架 ，
        if (ProtocolUtils.isGeneric(generic)) {
            map.put("generic", generic);
            map.put("methods", Constants.ANY_VALUE);
        } else {
            //获取接口版本号
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }
            //获取接口所有方法名称列表，不包含来自Object对象的放啊
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {//如果没有配置dubbo:method,那么表示该接口的所有方法都会暴露到注册中心中
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {//将接口中暴露的方法用,号进行拼接method1,method2
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        if (!ConfigUtils.isEmpty(token)) {//配置了token属性
            if (ConfigUtils.isDefault(token)) {//token=default或true
                map.put("token", UUID.randomUUID().toString());//则设置token为随机uuid值
            } else {
                map.put("token", token);//配置了指定的token
            }
        }
        if ("injvm".equals(protocolConfig.getName())) {//判断protocolConfig的协议名称name属性
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // export service
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }

        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        Integer port = this.findConfigedPorts(protocolConfig, name, map);
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);
        /***
         * 0 = {HashMap$Node@3157} "absent" -> "class com.alibaba.dubbo.rpc.cluster.configurator.absent.AbsentConfiguratorFactory"
         * 1 = {HashMap$Node@3158} "override" -> "class com.alibaba.dubbo.rpc.cluster.configurator.override.OverrideConfiguratorFactory"
         */
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            //0 = {HashMap$Node@2297} "absent" -> "class com.alibaba.dubbo.rpc.cluster.configurator.absent.AbsentConfiguratorFactory"
            //1 = {HashMap$Node@2298} "override" -> "class com.alibaba.dubbo.rpc.cluster.configurator.override.OverrideConfiguratorFactory"
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(Constants.SCOPE_KEY);//获取scope属性
        // don't export when none is configured
        if (!Constants.SCOPE_NONE.toString().equalsIgnoreCase(scope)) {//如果scope=none，则不暴露url配置

            // export to local if the config is not remote (export to remote only when config is remote)
            if (!Constants.SCOPE_REMOTE.toString().equalsIgnoreCase(scope)) {//如果scope未配置未remote，则先进行本地暴露
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (registryURLs != null && !registryURLs.isEmpty()) {
                    for (URL registryURL : registryURLs) {
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }
                        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString()));
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);
                        /*****
                         *
                         *
                         * 0 = {HashMap$Node@3090} "broadcast" -> "class com.alibaba.dubbo.rpc.cluster.support.BroadcastCluster"
                         * 1 = {HashMap$Node@3091} "mergeable" -> "class com.alibaba.dubbo.rpc.cluster.support.MergeableCluster"
                         * 2 = {HashMap$Node@3092} "failsafe" -> "class com.alibaba.dubbo.rpc.cluster.support.FailsafeCluster"
                         * 3 = {HashMap$Node@3093} "failback" -> "class com.alibaba.dubbo.rpc.cluster.support.FailbackCluster"
                         * 4 = {HashMap$Node@3094} "failover" -> "class com.alibaba.dubbo.rpc.cluster.support.FailoverCluster"
                         * 5 = {HashMap$Node@3095} "failfast" -> "class com.alibaba.dubbo.rpc.cluster.support.FailfastCluster"
                         * 6 = {HashMap$Node@3096} "available" -> "class com.alibaba.dubbo.rpc.cluster.support.AvailableCluster"
                         * 7 = {HashMap$Node@3097} "forking" -> "class com.alibaba.dubbo.rpc.cluster.support.ForkingCluster"
                         *
                         */
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        exporters.add(exporter);
                    }
                } else {
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    //dubbo://192.168.1.13:20880/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.1.13&bind.port=20880&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=27126&qos.port=22222&side=provider&timestamp=1527342486164
    private void exportLocal(URL url) {
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(LOCALHOST)
                    .setPort(0);//根据url创建一个本地的url暴露地址injvm://127.0.0.1/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.1.13&bind.port=20880&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=27126&qos.port=22222&side=provider&timestamp=1527342486164
            ServiceClassHolder.getInstance().pushServiceClass(getServiceClass(ref));
            Exporter<?> exporter = protocol.export(//根据实际对象引用和接口创建一个本地的暴露对象exporter
                    proxyFactory.getInvoker(ref, (Class) interfaceClass, local));
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    /***
     * 获取配置地址
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    /**
     * 1、检查是否配置了host地址，顺序：dubbo.properties-->protocol-->provider-->registry
     *      以下集中不符合：localhost、0.0.0.0
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);//获取host端口号
        //在这里会对host进行校验，以下集中不符合：localhost、0.0.0.0、127.0.0.1等
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {//判断协议是否符合规范
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + Constants.DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // 如果没有绑定地址的话
        if (hostToBind == null || hostToBind.length() == 0) {
            hostToBind = protocolConfig.getHost();//获取协议ip
            if (provider != null && (hostToBind == null || hostToBind.length() == 0)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {//如果host还是不符合规则，则
                    if (registryURLs != null && !registryURLs.isEmpty()) {
                        for (URL registryURL : registryURLs) {
                            try {
                                Socket socket = new Socket();
                                try {
                                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                    socket.connect(addr, 1000);
                                    hostToBind = socket.getLocalAddress().getHostAddress();
                                    break;
                                } finally {
                                    try {
                                        socket.close();
                                    } catch (Throwable e) {
                                    }
                                }
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

        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (hostToRegistry == null || hostToRegistry.length() == 0) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

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
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
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
                logger.warn("Use random available port(" + portToBind + ") for protocol " + name);
            }
        }

        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
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
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";   //获得协议名称
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (port == null || port.length() == 0) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }
    //解析provider的配置，如果没有配置的话，则默认创建一个
    private void checkDefault() {
        //如果尚未配置provider,则自动new一个，并根据dubbo.properties配置文件初始化该provider配置
        if (provider == null) {
            provider = new ProviderConfig();
        }
        //初始化provider的配置信息
        appendProperties(provider);
    }

    private void checkProtocol() {
        if ((protocols == null || protocols.isEmpty())
                && provider != null) {
            setProtocols(provider.getProtocols());
        }
        // 如果没配置protocol协议，则默认采用dubbo协议
        //优先级:protocol标签 > provider标签 > 默认的协议
        if (protocols == null || protocols.isEmpty()) {
            setProtocol(new ProtocolConfig());
        }
        for (ProtocolConfig protocolConfig : protocols) {
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                protocolConfig.setName("dubbo");//如果dubbo:protocol未配置name,则默认是name=dubbo
            }
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? (String) null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName("path", path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }
}

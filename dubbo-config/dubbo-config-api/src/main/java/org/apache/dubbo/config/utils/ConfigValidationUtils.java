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
package org.apache.dubbo.config.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.status.StatusChecker;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.AbstractInterfaceConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Dispatcher;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.Exchanger;
import org.apache.dubbo.remoting.telnet.TelnetHandler;
import org.apache.dubbo.rpc.ExporterListener;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PASSWORD_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.SHUTDOWN_WAIT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SHUTDOWN_WAIT_SECONDS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADPOOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.USERNAME_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;
import static org.apache.dubbo.common.utils.UrlUtils.isServiceDiscoveryRegistryType;
import static org.apache.dubbo.config.Constants.ARCHITECTURE;
import static org.apache.dubbo.config.Constants.CONTEXTPATH_KEY;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.ENVIRONMENT;
import static org.apache.dubbo.config.Constants.LAYER_KEY;
import static org.apache.dubbo.config.Constants.LISTENER_KEY;
import static org.apache.dubbo.config.Constants.NAME;
import static org.apache.dubbo.config.Constants.ORGANIZATION;
import static org.apache.dubbo.config.Constants.OWNER;
import static org.apache.dubbo.config.Constants.STATUS_KEY;
import static org.apache.dubbo.monitor.Constants.LOGSTAT_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.SUBSCRIBE_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.DISPATCHER_KEY;
import static org.apache.dubbo.remoting.Constants.EXCHANGER_KEY;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.remoting.Constants.TELNET;
import static org.apache.dubbo.remoting.Constants.TRANSPORTER_KEY;
import static org.apache.dubbo.rpc.Constants.FAIL_PREFIX;
import static org.apache.dubbo.rpc.Constants.FORCE_PREFIX;
import static org.apache.dubbo.rpc.Constants.LOCAL_KEY;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.RETURN_PREFIX;
import static org.apache.dubbo.rpc.Constants.THROW_PREFIX;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

public class ConfigValidationUtils {
    private static final Logger logger = LoggerFactory.getLogger(ConfigValidationUtils.class);
    /**
     * The maximum length of a <b>parameter's value</b>
     */
    private static final int MAX_LENGTH = 200;

    /**
     * The maximum length of a <b>path</b>
     */
    private static final int MAX_PATH_LENGTH = 200;

    /**
     * The rule qualification for <b>name</b>
     */
    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    /**
     * The rule qualification for <b>multiply name</b>
     */
    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    /**
     * The rule qualification for <b>method names</b>
     */
    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    /**
     * The rule qualification for <b>path</b>
     */
    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    /**
     * The pattern matches a value who has a symbol
     */
    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,\\s/\\-._0-9a-zA-Z]+");

    /**
     * The pattern matches a property key
     */
    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");

    /***
     *  加载注册中心地址列表
     * @param interfaceConfig 具体服务提供者配置
     * @param provider 服务端provider配置
     * @return
     * 1、检查serviceConfig服务配置指定的注册中心列表(dubbo:service可自定义registries地址，如果未指定，则向所有的注册中心注册)
     * 2、遍历每个注册中心配置
     * 3、为每个注册中心地址url封装额外的请求参数：
     *      application、RegistryConfig、path=RegistryService.class.getName()、protocol、
     */
    public static List<URL> loadRegistries(AbstractInterfaceConfig interfaceConfig, boolean provider) {
        // check && override if necessary
        List<URL> registryList = new ArrayList<URL>();
        ApplicationConfig application = interfaceConfig.getApplication();//获得ApplicationConfig
        //获得服务需要注册的注册配置
        List<RegistryConfig> registries = interfaceConfig.getRegistries();//获得注册配置列表
        if (CollectionUtils.isNotEmpty(registries)) {
            for (RegistryConfig config : registries) {//遍历注册列表

                String address = config.getAddress();//获得注册地址
                if (StringUtils.isEmpty(address)) {//如果注册地址为空
                    address = ANYHOST_VALUE;//0.0.0.0
                }
                //注册地址不是：N/A，也就是说会跳过N/A
                if (!RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    Map<String, String> map = new HashMap<String, String>();
                    //把应用application的配置参数添加到map里
                    AbstractConfig.appendParameters(map, application);
                    //把RegistryConfig的配置参数添加到map里
                    AbstractConfig.appendParameters(map, config);
                    //添加path：接口名称
                    map.put(PATH_KEY, RegistryService.class.getName());
                    //添加运行时参数
                    AbstractInterfaceConfig.appendRuntimeParameters(map);
                    //如果在<dubbo:registry>未指定协议protocol，则默认为dubbo协议
                    if (!map.containsKey(PROTOCOL_KEY)) {
                        map.put(PROTOCOL_KEY, DUBBO_PROTOCOL);
                    }
                    //将配置参数和address地址拼接成 URL对象，在address里，如果有多个地址的话，用;号分割
                    List<URL> urls = UrlUtils.parseURLs(address, map);
                    //registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
                    // application=demo-provider
                    // &dubbo=2.0.2
                    // &pid=686
                    // &qos.port=22222
                    // &registry=zookeeper
                    // &timestamp=1576736124139
                    for (URL url : urls) {//
                        //拼接额外参数registry 和 service-discovery-registry或registry
                        url = URLBuilder.from(url)
                                .addParameter(REGISTRY_KEY, url.getProtocol())
                                .setProtocol(extractRegistryType(url))
                                .build();
                        //如果是服务提供者&&允许注册（<dubbo:registry>里指定了 register 属性）
                        //或者不是服务提供者，但是允许订阅
                        //则加到注册中心列表里
                        if ((provider && url.getParameter(REGISTER_KEY, true))
                                || (!provider
                                //<dubbo:registry>里指定了 subscribe属性
                                && url.getParameter(SUBSCRIBE_KEY, true))) {
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }
    //检查是否配置了监控中心地址，优先级：系统属性dubbo.monitor.address-><dubbo:service monitor=''>或<dubbo:reference monitor=''>
    public static URL loadMonitor(AbstractInterfaceConfig interfaceConfig, URL registryURL) {
        Map<String, String> map = new HashMap<String, String>();
        map.put(INTERFACE_KEY, MonitorService.class.getName());
        AbstractInterfaceConfig.appendRuntimeParameters(map);
        //修改暴露到注册中心的地址，可以通过DUBBO_IP_TO_REGISTRY进行配置，默认是本机地址
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (NetUtils.isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" +
                    DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(REGISTER_IP_KEY, hostToRegistry);
        //检查 <dubbo:service>或<dubbo:reference>是否配置了monitor
        MonitorConfig monitor = interfaceConfig.getMonitor();
        ApplicationConfig application = interfaceConfig.getApplication();
        AbstractConfig.appendParameters(map, monitor);
        AbstractConfig.appendParameters(map, application);
        String address = monitor.getAddress();
        String sysaddress = System.getProperty("dubbo.monitor.address");//可通过 系统属性 dubbo.monitor.address配置
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        if (ConfigUtils.isNotEmpty(address)) {
            if (!map.containsKey(PROTOCOL_KEY)) {
                if (getExtensionLoader(MonitorFactory.class).hasExtension(LOGSTAT_PROTOCOL)) {
                    map.put(PROTOCOL_KEY, LOGSTAT_PROTOCOL);
                } else {
                    map.put(PROTOCOL_KEY, DUBBO_PROTOCOL);
                }
            }
            return UrlUtils.parseURL(address, map);
        } else if ((REGISTRY_PROTOCOL.equals(monitor.getProtocol()) || SERVICE_REGISTRY_PROTOCOL.equals(monitor.getProtocol()))
                && registryURL != null) {
            return URLBuilder.from(registryURL)
                    .setProtocol(DUBBO_PROTOCOL)
                    .addParameter(PROTOCOL_KEY, monitor.getProtocol())
                    .addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map))
                    .build();
        }
        return null;
    }

    /**
     * Legitimacy check and setup of local simulated operations. The operations can be a string with Simple operation or
     * a classname whose {@link Class} implements a particular function
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface that will be referenced
     * 1、对mock属性的值进行规范化
     * 2、如果mock的值以return开始，则截断return
     * 3、如果mock的值以return开始，则截断 throw
     * 4、检查normalizedMock类型是否实现了interfaceClass接口，且是否存在无参数的构造函数
     */

    public static void checkMock(Class<?> interfaceClass, AbstractInterfaceConfig config) {
        //检查当前服务的mock谁能够配置
        String mock = config.getMock();
        //判断是否配置了mock做降级服务
        //当服务提供方失败全部挂掉后，客户端不抛出异常，而是通过Mock数据返回授权失败。
        //mock只在出现非业务异常(比如超时，当消费者发送请求时，等待响应时间超过设置的timeout，就会报超时，网络异常等)时执行。
        //mock的配置支持两种，一种为boolean值，默认的为false。如果配置为true，则缺省使用mock类名，即类名+Mock后缀；另外一种则是配置”return null”，可以很简单的忽略掉异常。
        //消费端配置:注意这里配了mock不一定会调用mock快，只有超时等异常时候才会再调这个，如果请求响应正常不会走mock
        /***
         *       <dubbo:reference interface="com.mei.oms.service.BarService" mock="com.mei.oms.service.BarServiceMock" id="barService" timeout="10000" />
         * 		或
         * 		<dubbo:reference interface="com.mei.oms.service.BarService" mock="true" id="barService" timeout="10000" />
         * 		或
         * 		<dubbo:reference id="barService" interface="com.mei.oms.service.BarService"  timeout="10000" check="false" mock="return null"></dubbo:reference>
         * 	注意这里配置了timeout，表示超时时间，当超时了，就会执行mock做服务降级，也就是直接调用mock,不会抛异常
         */
        if (ConfigUtils.isEmpty(mock)) {
            return;
        }
        //检查mock值的规范
        String normalizedMock = MockInvoker.normalizeMock(mock);
        if (normalizedMock.startsWith(RETURN_PREFIX)) {//return 开头
            normalizedMock = normalizedMock.substring(RETURN_PREFIX.length()).trim();
            try {
                //Check whether the mock value is legal, if it is illegal, throw exception
                MockInvoker.parseMockValue(normalizedMock);
            } catch (Exception e) {
                throw new IllegalStateException("Illegal mock return in <dubbo:service/reference ... " +
                        "mock=\"" + mock + "\" />");
            }
        } else if (normalizedMock.startsWith(THROW_PREFIX)) {//throw 开头
            normalizedMock = normalizedMock.substring(THROW_PREFIX.length()).trim();
            if (ConfigUtils.isNotEmpty(normalizedMock)) {
                try {
                    //Check whether the mock value is legal
                    MockInvoker.getThrowable(normalizedMock);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock throw in <dubbo:service/reference ... " +
                            "mock=\"" + mock + "\" />");
                }
            }
        } else {
            //检查normalizedMock类型是否实现了interfaceClass接口，且是否存在无参数的构造函数
            MockInvoker.getMockObject(normalizedMock, interfaceClass);
        }
    }

    public static void validateAbstractInterfaceConfig(AbstractInterfaceConfig config) {
        checkName(LOCAL_KEY, config.getLocal());
        checkName("stub", config.getStub());
        checkMultiName("owner", config.getOwner());
        //各种拓展类是否存在的检查
        checkExtension(ProxyFactory.class, PROXY_KEY, config.getProxy());
        checkExtension(Cluster.class, CLUSTER_KEY, config.getCluster());
        checkMultiExtension(Filter.class, FILE_KEY, config.getFilter());
        checkMultiExtension(InvokerListener.class, LISTENER_KEY, config.getListener());
        checkNameHasSymbol(LAYER_KEY, config.getLayer());

        List<MethodConfig> methods = config.getMethods();
        if (CollectionUtils.isNotEmpty(methods)) {
            methods.forEach(ConfigValidationUtils::validateMethodConfig);
        }
    }

    /***
     * 校验服务属性配置的合法性
     * @param config
     */
    public static void validateServiceConfig(ServiceConfig config) {
        //校验版本号
        checkKey(VERSION_KEY, config.getVersion());
        //校验group
        checkKey(GROUP_KEY, config.getGroup());
        //校验token
        checkName(TOKEN_KEY, config.getToken());
        //校验路径，默认路径为接口名称
        checkPathName(PATH_KEY, config.getPath());
        //检查listener属性配置的合法性
        checkMultiExtension(ExporterListener.class, "listener", config.getListener());

        validateAbstractInterfaceConfig(config);
        //检验 register的配置
        List<RegistryConfig> registries = config.getRegistries();
        if (registries != null) {
            for (RegistryConfig registry : registries) {
                validateRegistryConfig(registry);
            }
        }
        //检验 protocol 的配置
        List<ProtocolConfig> protocols = config.getProtocols();
        if (protocols != null) {
            for (ProtocolConfig protocol : protocols) {
                validateProtocolConfig(protocol);
            }
        }
        //检验 provider 的配置
        ProviderConfig providerConfig = config.getProvider();
        if (providerConfig != null) {
            validateProviderConfig(providerConfig);
        }
    }
    //检查服务引用的属性配置，这里主要是检查命名的规范，包括长度和字符规范
    public static void validateReferenceConfig(ReferenceConfig config) {
        checkMultiExtension(InvokerListener.class, "listener", config.getListener());//检查listener属性值引用的是否是InvokerListener的实现类
        checkKey(VERSION_KEY, config.getVersion());//检查版本的值是否规范(如果没有配置的化无需检查)
        checkKey(GROUP_KEY, config.getGroup());//检查group(如果没有配置的化无需检查)
        checkName(CLIENT_KEY, config.getClient());//检查client网络传输的值(如果没有配置的化无需检查)
        //各种拓展类配置的规范存在
        validateAbstractInterfaceConfig(config);

        List<RegistryConfig> registries = config.getRegistries();
        if (registries != null) {
            for (RegistryConfig registry : registries) {
                validateRegistryConfig(registry);
            }
        }

        ConsumerConfig consumerConfig = config.getConsumer();
        if (consumerConfig != null) {
            validateConsumerConfig(consumerConfig);
        }
    }
    //检查配置中心配置(默认是注册中心)，检查长度和命名规范
    public static void validateConfigCenterConfig(ConfigCenterConfig config) {
        if (config != null) {
            checkParameterName(config.getParameters());
        }
    }

    public static void validateApplicationConfig(ApplicationConfig config) {
        if (config == null) {
            return;
        }

        if (!config.isValid()) {
            throw new IllegalStateException("No application config found or it's not a valid config! " +
                    "Please add <dubbo:application name=\"...\" /> to your spring config.");
        }

        // backward compatibility
        String wait = ConfigUtils.getProperty(SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }

        checkName(NAME, config.getName());
        checkMultiName(OWNER, config.getOwner());
        checkName(ORGANIZATION, config.getOrganization());
        checkName(ARCHITECTURE, config.getArchitecture());
        checkName(ENVIRONMENT, config.getEnvironment());
        checkParameterName(config.getParameters());
    }

    public static void validateModuleConfig(ModuleConfig config) {
        if (config != null) {
            checkName(NAME, config.getName());
            checkName(OWNER, config.getOwner());
            checkName(ORGANIZATION, config.getOrganization());
        }
    }

    public static void validateMetadataConfig(MetadataReportConfig metadataReportConfig) {
        if (metadataReportConfig == null) {
            return;
        }
    }

    public static void validateMetricsConfig(MetricsConfig metricsConfig) {
        if (metricsConfig == null) {
            return;
        }
    }

    public static void validateSslConfig(SslConfig sslConfig) {
        if (sslConfig == null) {
            return;
        }
    }

    public static void validateMonitorConfig(MonitorConfig config) {
        if (config != null) {
            if (!config.isValid()) {
                logger.info("There's no valid monitor config found, if you want to open monitor statistics for Dubbo, " +
                        "please make sure your monitor is configured properly.");
            }

            checkParameterName(config.getParameters());
        }
    }

    public static void validateProtocolConfig(ProtocolConfig config) {
        if (config != null) {
            String name = config.getName();
            checkName("name", name);
            checkName(HOST_KEY, config.getHost());
            checkPathName("contextpath", config.getContextpath());


            if (DUBBO_PROTOCOL.equals(name)) {
                checkMultiExtension(Codec.class, CODEC_KEY, config.getCodec());
            }
            if (DUBBO_PROTOCOL.equals(name)) {
                checkMultiExtension(Serialization.class, SERIALIZATION_KEY, config.getSerialization());
            }
            if (DUBBO_PROTOCOL.equals(name)) {
                checkMultiExtension(Transporter.class, SERVER_KEY, config.getServer());
            }
            if (DUBBO_PROTOCOL.equals(name)) {
                checkMultiExtension(Transporter.class, CLIENT_KEY, config.getClient());
            }
            checkMultiExtension(TelnetHandler.class, TELNET, config.getTelnet());
            checkMultiExtension(StatusChecker.class, "status", config.getStatus());
            checkExtension(Transporter.class, TRANSPORTER_KEY, config.getTransporter());
            checkExtension(Exchanger.class, EXCHANGER_KEY, config.getExchanger());
            checkExtension(Dispatcher.class, DISPATCHER_KEY, config.getDispatcher());
            checkExtension(Dispatcher.class, "dispather", config.getDispather());
            checkExtension(ThreadPool.class, THREADPOOL_KEY, config.getThreadpool());
        }
    }

    public static void validateProviderConfig(ProviderConfig config) {
        checkPathName(CONTEXTPATH_KEY, config.getContextpath());
        checkExtension(ThreadPool.class, THREADPOOL_KEY, config.getThreadpool());
        checkMultiExtension(TelnetHandler.class, TELNET, config.getTelnet());
        checkMultiExtension(StatusChecker.class, STATUS_KEY, config.getStatus());
        checkExtension(Transporter.class, TRANSPORTER_KEY, config.getTransporter());
        checkExtension(Exchanger.class, EXCHANGER_KEY, config.getExchanger());
    }

    public static void validateConsumerConfig(ConsumerConfig config) {
        if (config == null) {
            return;
        }
    }

    public static void validateRegistryConfig(RegistryConfig config) {
        checkName(PROTOCOL_KEY, config.getProtocol());
        checkName(USERNAME_KEY, config.getUsername());
        checkLength(PASSWORD_KEY, config.getPassword());
        checkPathLength(FILE_KEY, config.getFile());
        checkName(TRANSPORTER_KEY, config.getTransporter());
        checkName(SERVER_KEY, config.getServer());
        checkName(CLIENT_KEY, config.getClient());
        checkParameterName(config.getParameters());
    }
    //<dubbo:method name="addListener" service="org.apache.dubbo.demo.CallbackService" serviceId="org.apache.dubbo.demo.CallbackService" prefix="dubbo.org.apache.dubbo.demo.CallbackService.org.apache.dubbo.demo.CallbackService.addListener" id="addListener" valid="true" />
    public static void validateMethodConfig(MethodConfig config) {
        checkExtension(LoadBalance.class, LOADBALANCE_KEY, config.getLoadbalance());
        checkParameterName(config.getParameters());
        checkMethodName("name", config.getName());

        String mock = config.getMock();
        if (mock.startsWith(RETURN_PREFIX) || mock.startsWith(THROW_PREFIX + " ")) {
            checkLength(MOCK_KEY, mock);
        } else if (mock.startsWith(FAIL_PREFIX) || mock.startsWith(FORCE_PREFIX)) {
            checkNameHasSymbol(MOCK_KEY, mock);
        } else {
            checkName(MOCK_KEY, mock);
        }
    }

    /***
     * 获取注册类型
     *      service-discovery-registry
     *      registry
     * @param url zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&pid=686&qos.port=22222&timestamp=1576736124139
     *
     * @return
     *      如果 url中没有设置registry-type=service，则isServiceDiscoveryRegistryType返回true，这边也就返回service-discovery-registry
     *      其它情况返回 registry
     */
    private static String extractRegistryType(URL url) {
        return isServiceDiscoveryRegistryType(url) ? //通常这个返回返回
                SERVICE_REGISTRY_PROTOCOL
                : REGISTRY_PROTOCOL;
    }

    public static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (StringUtils.isNotEmpty(value)
                && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    /**
     *  如果配置了type类型的监听器，则检查是否在该type下是否存在对应的SPI拓展实现类
     * @param type
     * @param property
     * @param value
     * 1、把value用逗号分割
     * 2、检查每个值是否存在对应的spi拓展实现类，如果没有，则抛出异常
     */
    public static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (StringUtils.isNotEmpty(value)) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException("No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    public static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    public static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    public static void checkName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    public static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    public static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    public static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    public static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    public static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    public static void checkParameterName(Map<String, String> parameters) {
        if (CollectionUtils.isEmptyMap(parameters)) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    public static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (StringUtils.isEmpty(value)) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" contains illegal " +
                        "character, only digit, letter, '-', '_' or '.' is legal.");
            }
        }
    }

}

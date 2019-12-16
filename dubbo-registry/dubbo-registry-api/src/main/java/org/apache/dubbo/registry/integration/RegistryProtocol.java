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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.FilterConstants.VALIDATION_KEY;
import static org.apache.dubbo.common.constants.QosConstants.ACCEPT_FOREIGN_IP;
import static org.apache.dubbo.common.constants.QosConstants.QOS_ENABLE;
import static org.apache.dubbo.common.constants.QosConstants.QOS_HOST;
import static org.apache.dubbo.common.constants.QosConstants.QOS_PORT;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY;
import static org.apache.dubbo.registry.Constants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.EXCHANGER_KEY;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.rpc.Constants.DEPRECATED_KEY;
import static org.apache.dubbo.rpc.Constants.INTERFACES;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WARMUP_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WEIGHT_KEY;

/**
 * RegistryProtocol
 */
public class RegistryProtocol implements Protocol {
    public static final String[] DEFAULT_REGISTER_PROVIDER_KEYS = {
            APPLICATION_KEY, CODEC_KEY, EXCHANGER_KEY, SERIALIZATION_KEY, CLUSTER_KEY, CONNECTIONS_KEY, DEPRECATED_KEY,
            GROUP_KEY, LOADBALANCE_KEY, MOCK_KEY, PATH_KEY, TIMEOUT_KEY, TOKEN_KEY, VERSION_KEY, WARMUP_KEY,
            WEIGHT_KEY, TIMESTAMP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    public static final String[] DEFAULT_REGISTER_CONSUMER_KEYS = {
            APPLICATION_KEY, VERSION_KEY, GROUP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    private final ConcurrentMap<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    private Cluster cluster;
    private Protocol protocol;
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (CollectionUtils.isNotEmptyMap(params)) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDE_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    /***
     * 将 registeredProviderUrl注册到注册中心registryUrl上
     * @param registryUrl
     * @param registeredProviderUrl
     * 1、根据 registryUrl 获得注册中心
     * 2、将 registeredProviderUrl 注册到注册中心registryUrl上
     * 3、将注册信息维护到ApplicationModel内存中
     */
    public void register(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);//获得注册中心
        registry.register(registeredProviderUrl);//向注册中心注册这个服务
        //serviceKey:cn/org.apache.dubbo.demo.EventNotifyService:1.0.0
        ProviderModel model = ApplicationModel.getProviderModel(registeredProviderUrl.getServiceKey());
        model.addStatedUrl(new ProviderModel.RegisterStatedURL(
                registeredProviderUrl,
                registryUrl,
                true
        ));
    }
    /***
     *  将invoker包装成一个exporter，exporter包含了注册的地址和监听的configurators地址
     *      这里会进行服务在注册中心上的注册和订阅configurators地址
     *  invoker = {JavassistProxyFactory$1@4074} "interface org.apache.dubbo.demo.EventNotifyService -> registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F220.250.64.225%3A20880%2Forg.apache.dubbo.demo.EventNotifyService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.EventNotifyService%26bind.ip%3D220.250.64.225%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Dcn%26interface%3Dorg.apache.dubbo.demo.EventNotifyService%26methods%3Dget%26pid%3D34339%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1575881105857%26version%3D1.0.0&pid=34339&qos.port=22222&registry=zookeeper&timestamp=1575881026931"
     *  metadata = {ServiceBean@3049} "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="true" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@7807ac2c" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" generic="false" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" group="cn" deprecated="false" dynamic="true" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
     *  1、获得zookeeper注册地址:
     *      registryUrl=zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider
     *      &dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.44.56%3A20880%2Forg.apache.dubbo.demo.AsyncService2%3F
     *      anyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.AsyncService2%26bind.ip%3D192.168.44.56%26bind.port%3D20880
     *      %26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse
     *      %26interface%3Dorg.apache.dubbo.demo.AsyncService2%26methods%3DsayHello%26pid%3D22219%26release%3D%26side%3Dprovider
     *      %26timestamp%3D1576066567127&pid=22219&qos.port=22222&timestamp=1576066538747
     *  2、从registryUrl获得export属性值，也就是服务提供者的地址：
     *      providerUrl=dubbo://192.168.44.56:20880/org.apache.dubbo.demo.AsyncService2?
     *      anyhost=true&bean.name=org.apache.dubbo.demo.AsyncService2&bind.ip=192.168.44.56&bind.port=20880
     *      &deprecated=false&dubbo=2.0.2&dynamic=true&generic=false
     *      &interface=org.apache.dubbo.demo.AsyncService2&methods=sayHello&pid=22219&release=&side=provider
     *      &timestamp=1576066567127
     *  3、根据providerUrl获得订阅地址，且paramaters添加了category=configurators和check=false：
     *      overrideSubscribeUrl=provider://192.168.44.56:20880/org.apache.dubbo.demo.AsyncService2?
     *      anyhost=true&bean.name=org.apache.dubbo.demo.AsyncService2&bind.ip=192.168.44.56&bind.port=20880
     *      &category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false
     *      &interface=org.apache.dubbo.demo.AsyncService2&methods=sayHello&pid=22219&release=&side=provider
     *      &timestamp=1576066567127
     *  4、为对应的服务provider绑定一个监听器OverrideListener，用于监听对应的服务provider的地址变化
     *  5、将configurators里对应的providerUrl的配置参数合并到当前的providerUrl里，并监听configurators下对该服务的地址的配置的变化
     *  6、将originInvoker包装成一个ExporterChangeableWrapper。同时为创建一个NettyServer，用于接收客户端的地址
     *  7、将当前服务originInvoker注册到注册中心上，同时订阅监听configurators路径
     *
     */
    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        URL registryUrl = getRegistryUrl(originInvoker);//获得注册地址： zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.44.56%3A20880%2Forg.apache.dubbo.demo.AsyncService2%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.AsyncService2%26bind.ip%3D192.168.44.56%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.AsyncService2%26methods%3DsayHello%26pid%3D22219%26release%3D%26side%3Dprovider%26timestamp%3D1576066567127&pid=22219&qos.port=22222&timestamp=1576066538747
        // 获得服务提供者的地址 dubbo://192.168.44.56:20880/org.apache.dubbo.demo.AsyncService2?anyhost=true&bean.name=org.apache.dubbo.demo.AsyncService2&bind.ip=192.168.44.56&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.AsyncService2&methods=sayHello&pid=22219&release=&side=provider&timestamp=1576066567127
        URL providerUrl = getProviderUrl(originInvoker);//dubbo://220.250.64.225:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
        //获得订阅地址，因为服务提供者也要监听对应configurators目录的变化
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);//provider://192.168.0.103:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.103&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.HelloService&methods=sayHello,sayHelloAsync&pid=78763&release=&side=provider&timestamp=1574988715703
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);//为订阅地址绑定对应的监听器
        //获得服务提供者地址
        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        //export invoker 将originInvoker包装成一个ExporterChangeableWrapper。同时为创建一个NettyServer，用于接收客户端的地址
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        //获得注册中心的地址
        final Registry registry = getRegistry(originInvoker);//zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096
        final URL registeredProviderUrl = getUrlToRegistry(providerUrl, registryUrl);//dubbo://192.168.44.56:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=22219&release=&revision=1.0.0&side=provider&timestamp=1576065463228&version=1.0.0
        //to judge if we need to delay publish
        boolean register = providerUrl.getParameter(REGISTER_KEY, true);
        if (register) {//服务提供者注册服务,会在zk上创建一个路径
            //将当前服务originInvoker注册到注册中心上
            register(registryUrl, registeredProviderUrl);
        }
        //
        // Deprecated! Subscribe to override rules in 2.6.x or before.
        //订阅监听configurators路径
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    /***
     *
     * @param providerUrl
     * @param listener
     * @return
     * 1、将configurators里对应的providerUrl的配置参数合并到当前的providerUrl里
     * 2、为合并后的providerUrl创建一个监听器，用于监听当前服务地址配置的变化
     */
    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        String key = getCacheKey(originInvoker);

        return (ExporterChangeableWrapper<T>) bounds.computeIfAbsent(key, s -> {
            Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);//创建一个InvokerDelegate
            return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
        });
    }

    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        // update local exporter
        ExporterChangeableWrapper exporter = doChangeLocalExport(originInvoker, newInvokerUrl);
        // update registry
        URL registryUrl = getRegistryUrl(originInvoker);
        final URL newProviderUrl = getUrlToRegistry(newInvokerUrl, registryUrl);

        getRegisteredUrl(registryUrl, newProviderUrl)
                .ifPresent(oldProviderUrl -> {
                    if (!newProviderUrl.equals(oldProviderUrl)) {
                        Registry registry = getRegistry(originInvoker);
                        registry.unregister(oldProviderUrl);
                        registry.register(newProviderUrl);
                        exporter.setRegisterUrl(newProviderUrl);
                    }
                });
    }

    private Optional<URL> getRegisteredUrl(URL registryUrl, URL providerUrl) {
        ProviderModel providerModel = ApplicationModel.getServiceRepository()
                .lookupExportedService(providerUrl.getServiceKey());

        List<ProviderModel.RegisterStatedURL> statedUrls = providerModel.getStatedUrl();
        Optional<ProviderModel.RegisterStatedURL> statedUrlOptional = statedUrls.stream()
                .filter(u -> u.getRegistryUrl().equals(registryUrl)
                        && u.getProviderUrl().getProtocol().equals(providerUrl.getProtocol()))
                .findFirst();

        if (statedUrlOptional.isPresent()) {
            ProviderModel.RegisterStatedURL statedURL = statedUrlOptional.get();
            if (statedURL.isRegistered()) {
                return Optional.of(statedURL.getProviderUrl());
            }
        }
        return Optional.empty();
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
        } else {
            final Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegate));
        }
        return exporter;
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    protected Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }

    protected URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();//registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.44.56%3A20880%2Forg.apache.dubbo.demo.EventNotifyService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.EventNotifyService%26bind.ip%3D192.168.44.56%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Dcn%26interface%3Dorg.apache.dubbo.demo.EventNotifyService%26methods%3Dget%26pid%3D34250%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1576073028433%26version%3D1.0.0&pid=34250&qos.port=22222&registry=zookeeper&timestamp=1576073019384
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {//registry
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY);//获取注册协议，默认是dubbo
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);//设置registryUrl的协议，并移除registry的key
        }
        return registryUrl;
    }

    protected URL getRegistryUrl(URL url) {
        return URLBuilder.from(url)
                .setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY))
                .removeParameter(REGISTRY_KEY)
                .build();
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getUrlToRegistry(final URL providerUrl, final URL registryUrl) {
        //The address you see at the registry
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            return providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_HOST, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            String extraKeys = registryUrl.getParameter(EXTRA_KEYS_KEY, "");
            // if path is not the same as interface name then we should keep INTERFACE_KEY,
            // otherwise, the registry structure of zookeeper would be '/dubbo/path/providers',
            // but what we expect is '/dubbo/interface/providers'
            if (!providerUrl.getPath().equals(providerUrl.getParameter(INTERFACE_KEY))) {
                if (StringUtils.isNotEmpty(extraKeys)) {
                    extraKeys += ",";
                }
                extraKeys += INTERFACE_KEY;
            }
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS
                    , COMMA_SPLIT_PATTERN.split(extraKeys));
            return URL.valueOf(providerUrl, paramsToRegistry, providerUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    /***
     *
     * @param registeredProviderUrl
     * @return
     */
    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**originInvoker:
     *      invoker = {JavassistProxyFactory$1@3698} "interface org.apache.dubbo.demo.EventNotifyService -> registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.44.56%3A20880%2Forg.apache.dubbo.demo.EventNotifyService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.EventNotifyService%26bind.ip%3D192.168.44.56%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Dcn%26interface%3Dorg.apache.dubbo.demo.EventNotifyService%26methods%3Dget%26pid%3D34250%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1576073028433%26version%3D1.0.0&pid=34250&qos.port=22222&registry=zookeeper&timestamp=1576073019384"
     *      metadata = {ServiceBean@3046} "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="true" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@740abb5" generic="false" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" deprecated="false" group="cn" dynamic="true" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);//dubbo://192.168.0.108:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.108&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=5410&release=&side=provider&timestamp=1575332340328
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);//dubbo://192.168.44.56:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=192.168.44.56&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34250&release=&revision=1.0.0&side=provider&timestamp=1576073028433&version=1.0.0
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;//dubbo://192.168.44.56:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=192.168.44.56&bind.port=20880&deprecated=false&dubbo=2.0.2&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34250&release=&revision=1.0.0&side=provider&timestamp=1576073028433&version=1.0.0
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        url = getRegistryUrl(url);
        Registry registry = registryFactory.getRegistry(url);
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        String group = qs.get(GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                return doRefer(getMergeableCluster(), registry, type, url);
            }
        }
        return doRefer(cluster, registry, type, url);
    }

    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }

    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        Map<String, String> parameters = new HashMap<String, String>(directory.getUrl().getParameters());
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        if (!ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true)) {
            directory.setRegisteredConsumerUrl(getRegisteredConsumerUrl(subscribeUrl, url));
            registry.register(directory.getRegisteredConsumerUrl());
        }
        directory.buildRouterChain(subscribeUrl);
        directory.subscribe(subscribeUrl.addParameter(CATEGORY_KEY,
                PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY));

        Invoker invoker = cluster.join(directory);
        return invoker;
    }

    public URL getRegisteredConsumerUrl(final URL consumerUrl, URL registryUrl) {
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            return consumerUrl.addParameters(CATEGORY_KEY, CONSUMERS_CATEGORY,
                    CHECK_KEY, String.valueOf(false));
        } else {
            return URL.valueOf(consumerUrl, DEFAULT_REGISTER_CONSUMER_KEYS, null).addParameters(
                    CATEGORY_KEY, CONSUMERS_CATEGORY, CHECK_KEY, String.valueOf(false));
        }
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] additionalParameterKeys) {
        int additionalLen = additionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(additionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

    //Merge the urls of configurators

    /***
     *
     * @param configurators
     * @param url
     * @return
     * 1、遍历configurators列表
     * 2、根据每个configurator合并url中的属性
     */
    private static URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    private static class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {//empty://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282
            logger.debug("original override urls: " + urls);
            //empty://192.168.0.105:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=192.168.0.105&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=82030&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576457680054
           List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();//dubbo://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, originUrl);
            newUrl = getConfigedInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            newUrl = getConfigedInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);//dubbo://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(DynamicConfiguration.getRuleKey(providerUrl) + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         * 根据configurators里的规则，重写providerUrl对应的属性
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            Registry registry = RegistryProtocol.this.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.this.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }
    }
}

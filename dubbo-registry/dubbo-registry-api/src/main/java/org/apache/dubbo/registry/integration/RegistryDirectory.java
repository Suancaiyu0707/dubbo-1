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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.AddressListener;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DISABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PREFERRED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.ROUTER_KEY;


/**
 * RegistryDirectory
 */

/***
 *
 * @param <T>
 *    属于动态列表实现，会自动从注册中心更新Invoker列表、配置信息、路由列表
 *
 * RegistryDirectory 是一种动态服务目录，它实现了 NotifyListener 接口。当注册中心服务配置发生变化后，RegistryDirectory 可收到与当前服务相关的变化。
 * 收到变更通知后，RegistryDirectory 可根据配置变更信息刷新 Invoker 列表。
 *
 * 主要监听三类信息：
 *     第一类：configurators  配置信息
 *     第二类：invoker列表
 *     第三类：providers 提供者列表
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final RouterFactory ROUTER_FACTORY = ExtensionLoader.getExtensionLoader(RouterFactory.class)
            .getAdaptiveExtension();
    //服务key，在构造时初始化，这样可以断言不为空
    //org.apache.dubbo.registry.RegistryService
    private final String serviceKey; // Initialization at construction time, assertion not null
    //interface com.springcloud.alibaba.service.HelloAnnotationProviderService
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    /**
     * 0 = {HashMap$Node@6123} "side" -> "consumer"
     * 1 = {HashMap$Node@6124} "register.ip" -> "220.250.64.225"
     * 2 = {HashMap$Node@6125} "lazy" -> "false"
     * 3 = {HashMap$Node@6126} "methods" -> "sayHi"
     * 4 = {HashMap$Node@6127} "release" -> "2.7.4.1"
     * 5 = {HashMap$Node@6128} "dubbo" -> "2.0.2"
     * 6 = {HashMap$Node@6129} "monitor" -> "dubbo%3A%2F%2F127.0.0.1%3A2181%2Forg.apache.dubbo.registry.RegistryService%3Fapplication%3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26pid%3D29002%26protocol%3Dregistry%26qos.enable%3Dfalse%26refer%3Dapplication%253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526interface%253Dorg.apache.dubbo.monitor.MonitorService%2526pid%253D29002%2526qos.enable%253Dfalse%2526register.ip%253D220.250.64.225%2526release%253D2.7.4.1%2526timestamp%253D1574218168630%26registry%3Dzookeeper%26release%3D2.7.4.1%26timestamp%3D1574218168628"
     * 7 = {HashMap$Node@6130} "pid" -> "29002"
     * 8 = {HashMap$Node@6131} "interface" -> "com.springcloud.alibaba.service.HelloAnnotationProviderService"
     * 9 = {HashMap$Node@6132} "qos.enable" -> "false"
     * 10 = {HashMap$Node@6133} "application" -> "springcloud-alibaba-dubbo-provider"
     * 11 = {HashMap$Node@6134} "sticky" -> "false"
     * 12 = {HashMap$Node@6135} "timestamp" -> "1574218168373"
     */
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    //目录地址 zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=springcloud-alibaba-dubbo-provider&dubbo=2.0.2&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService&lazy=false&methods=sayHi&pid=29002&qos.enable=false&register.ip=220.250.64.225&release=2.7.4.1&side=consumer&sticky=false&timestamp=1574218168373
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    //是否多个group
    private final boolean multiGroup;
    //协议
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    //注册信息 zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=springcloud-alibaba-dubbo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=29002&qos.enable=false&release=2.7.4.1&timestamp=1574218168628
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    private volatile boolean forbidden = false;
    //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=springcloud-alibaba-dubbo-provider&dubbo=2.0.2
    // &interface=com.springcloud.alibaba.service.HelloAnnotationProviderService&lazy=false&methods=sayHi&pid=29002&qos.enable=false
    // &register.ip=220.250.64.225&release=2.7.4.1&side=consumer&sticky=false&timestamp=1574218168373
    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
//   //consumer://220.250.64.225/com.springcloud.alibaba.service.HelloAnnotationProviderService?application=springcloud-alibaba-dubbo-provider&category=consumers&check=false&dubbo=2.0.2&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService&lazy=false&methods=sayHi&pid=29002&qos.enable=false&release=2.7.4.1&side=consumer&sticky=false&timestamp=1574218168373
    private volatile URL registeredConsumerUrl;

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    //配置信息
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    //notify更新后最终的Invoker列表，key是对应的方法名，value是整个Invoker列表
    //doList就是从这个map里匹配
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    //服务提供者列表
    private volatile List<Invoker<T>> invokers;

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    private static final ConsumerConfigurationListener CONSUMER_CONFIGURATION_LISTENER = new ConsumerConfigurationListener();
    private ReferenceConfigurationListener serviceConfigurationListener;


    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        //服务类型不能为空
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }
        //
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        //根据url构建 overrideDirectoryUrl 和 directoryUrl
        this.overrideDirectoryUrl = this.directoryUrl = turnRegistryUrlToConsumerUrl(url);
        String group = directoryUrl.getParameter(GROUP_KEY, "");//获得group属性
        //组配置是否属于多个组
        this.multiGroup = group != null && (ANY_VALUE.equals(group) || group.contains(","));
    }

    /***
     * 将url转换成URL对象
     * @param url
     * @return
     */
    private URL turnRegistryUrlToConsumerUrl(URL url) {
        // save any parameter in registry that will be useful to the new url.
        //获得preferred属性
        String isDefault = url.getParameter(PREFERRED_KEY);
        if (StringUtils.isNotEmpty(isDefault)) {
            queryMap.put(REGISTRY_KEY + "." + PREFERRED_KEY, isDefault);
        }
        return URLBuilder.from(url)
                .setPath(url.getServiceInterface())
                .clearParameters()
                .addParameters(queryMap)
                .removeParameter(MONITOR_KEY)
                .build();
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    /***
     *根据url 订阅服务提供者列表的更新信息，时刻关注这个服务提供者列表中的URL变化
     * @param url
     */
    public void subscribe(URL url) {
        setConsumerUrl(url);
        //将自己本身作为一个监听器保存到静态属性：CONSUMER_CONFIGURATION_LISTENER
        CONSUMER_CONFIGURATION_LISTENER.addNotifyListener(this);
        serviceConfigurationListener = new ReferenceConfigurationListener(this, url);
        //订阅监听器
        registry.subscribe(url, this);
    }


    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }

        // 取消注册
        try {
            //从内存中移除，就是取消注册
            if (getRegisteredConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unregister(getRegisteredConsumerUrl());
            }
        } catch (Throwable t) {
            logger.warn("unexpected error when unregister service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        // 取消订阅.
        try {
            //取消订阅
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
            ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                    .removeListener(ApplicationModel.getApplication(), CONSUMER_CONFIGURATION_LISTENER);
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     *
     * @param urls The list of registered information , is always not empty.
     *         The meaning is the same as the return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
     *      当前对象本身是一个监听器，会监听注册中心指定目录，如果监听的目录发生变化，则会接收到通知并调用notify方法
     */
    //通常是ZookeeperRegistry.doSubscribe会通知
    @Override
    public synchronized void notify(List<URL> urls) {
        //从Url列表中中分别解析出以下三个属性(每个服务可能有多个服务提供者)：
        //    configurators
        //    routers
        //    providers
        Map<String, List<URL>> categoryUrls = urls.stream()
                .filter(Objects::nonNull)
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.groupingBy(url -> {
                    if (UrlUtils.isConfigurator(url)) {
                        return CONFIGURATORS_CATEGORY;
                    } else if (UrlUtils.isRoute(url)) {
                        return ROUTERS_CATEGORY;
                    } else if (UrlUtils.isProvider(url)) {
                        return PROVIDERS_CATEGORY;
                    }
                    return "";
                }));

        //获取第一类： configurators 列表。管理员也可以通过dubbo-admin动态配置功能修改生产者的参数。这些参数保存到配置中心的Configurators类目下
        //notify监听到URL配置参数的变化，会解析斌更新呢到本地的Configurator配置
        List<URL> configuratorURLs = categoryUrls.getOrDefault(CONFIGURATORS_CATEGORY, Collections.emptyList());
        //更新本地内存的configurators，直接替换更新,不要做合并
        this.configurators = Configurator.toConfigurators(configuratorURLs).orElse(this.configurators);
        //获取第二类： routers 列表
        List<URL> routerURLs = categoryUrls.getOrDefault(ROUTERS_CATEGORY, Collections.emptyList());
        //循环遍历判断路由是否存在，如果是新增的路由，则更新routerChain
        toRouters(routerURLs).ifPresent(this::addRouters);

        //获取第三类： providers列表
        List<URL> providerURLs = categoryUrls.getOrDefault(PROVIDERS_CATEGORY, Collections.emptyList());
        /**
         * 3.x added for extend URL address
         */
        ExtensionLoader<AddressListener> addressListenerExtensionLoader = ExtensionLoader.getExtensionLoader(AddressListener.class);
        List<AddressListener> supportedListeners = addressListenerExtensionLoader.getActivateExtension(getUrl(), (String[]) null);
        if (supportedListeners != null && !supportedListeners.isEmpty()) {
            for (AddressListener addressListener : supportedListeners) {
                providerURLs = addressListener.notify(providerURLs, getUrl(),this);
            }
        }
        //更新invoker列表
        refreshOverrideAndInvoker(providerURLs);
    }

    /***
     *
     * @param urls
     */
    private void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        // 获取配置 overrideDirectoryUrl 值
        overrideDirectoryUrl();
        refreshInvoker(urls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * <ol>
     * <li> If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache,
     * and notice that any parameter changes in the URL will be re-referenced.</li>
     * <li>If the incoming invoker list is not empty, it means that it is the latest invoker list.</li>
     * <li>If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route
     * rule, which needs to be re-contrasted to decide whether to re-reference.</li>
     * </ol>
     *
     * @param invokerUrls this parameter can't be null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.

    /**
     *
     * @param invokerUrls
     * 1、根据入参 invokerUrls 的数量和 url 的协议头判断是否需要禁用所有服务，若需要 forbidden 设为 true，并销毁所有的 Invoker
     * 2、将 url 转换成 invoker，并得到 <url, Invoker> 的映射关系
     * 3、合并多个组的 invoker，并赋值给 invokers 变量
     * 4、销毁无用的 invoker，避免 consumer 调用下线的 provider
     */
    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null");
        // invokerUrls 中只有一个元素 && url 的协议头 Protocol 为 empty，表示禁用所有服务
        if (invokerUrls.size() == 1
                && invokerUrls.get(0) != null
                && EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 禁用服务标识
            this.forbidden = true; // Forbid to access
            // 空 invoker 列表
            this.invokers = Collections.emptyList();
            routerChain.setInvokers(this.invokers);
            // 销毁所有的 invoker
            destroyAllInvokers(); // Close all invokers
        } else {
            this.forbidden = false; // Allow to access
            // 原来的 invoker 映射map
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 添加缓存 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                this.cachedInvokerUrls = new HashSet<>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }
            if (invokerUrls.isEmpty()) {
                return;
            }
            // 将 url 转换成 url 到 invoker 映射 map，map 中 url 为 key，value 为 invoker
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map

            /**
             * If the calculation is wrong, it is not processed.
             *
             * 1. The protocol configured by the client is inconsistent with the protocol of the server.
             *    eg: consumer protocol = dubbo, provider only has other protocol services(rest).
             * 2. The registration center is not robust and pushes illegal specification data.
             *
             */

            if (CollectionUtils.isEmptyMap(newUrlInvokerMap)) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }

            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            routerChain.setInvokers(newInvokers);
            // 多个 group 时需要合并 invoker
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        Map<String, List<Invoker<T>>> groupMap = new HashMap<>();
        // 遍历 invoker 进行分组
        for (Invoker<T> invoker : invokers) {
            // url 中获取 group 配置
            String group = invoker.getUrl().getParameter(GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            // invoker 放入同一组中
            groupMap.get(group).add(invoker);
        }

        if (groupMap.size() == 1) {// 只有一个分组，直接取出值
            mergedInvokers.addAll(groupMap.values().iterator().next());
        } else if (groupMap.size() > 1) {// 多个分组时，遍历分组中的 invoker列表，并调用集群的 join 方法合并每个组的 invoker 列表
            for (List<Invoker<T>> groupList : groupMap.values()) {
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                staticDirectory.buildRouterChain();
                // 通过集群类 的 join 方法合并每个分组对应的 Invoker 列表
                mergedInvokers.add(CLUSTER.join(staticDirectory));
            }
        } else {
            mergedInvokers = invokers;
        }
        return mergedInvokers;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     * 对于router类参数，会遍历所有router类型的url，让好通过Router工厂把每个URL包装成路由故意则，
     * 最后更新本地的路由信息。这个过程会跳过empty开头的URL
     */
    private Optional<List<Router>> toRouters(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        for (URL url : urls) {
            //如果当前url，会跳过当前的url，并不会退出循环
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }
            //获取router值
            String routerType = url.getParameter(ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                url = url.setProtocol(routerType);
            }
            try {
                //根据 router的值，新增路由规则。所以router的值对应RouterFactory实现类中的Name的值
                Router router = ROUTER_FACTORY.getRouter(url);
                if (!routers.contains(router)) {
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     *
     * @param urls
     * @return invokers
     * 1）、忽略 provider 协议不被 consumer 支持的 和 empty 协议的 url
     *
     * 2）、合并 url 参数，顺序是 : override > -D > Consumer > Provider
     *
     * 3）、获取本地缓存 invoker，如果缓存没有或者provider配置变化了 && provider 没有被禁用，则构建新的 invoker 并放入缓存 newUrlInvokerMap 中
     *
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<>();
        if (urls == null || urls.isEmpty()) {// 列表为空，直接返回
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<>();
        // 获取 consumer 端配置的协议
        String queryProtocols = this.queryMap.get(PROTOCOL_KEY);
        //遍历服务提供者的url
        for (URL providerUrl : urls) {
            //如果该服务提供者的协议是empty，也直接忽略
            if (EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            // If protocol is configured at the reference side, only the matching protocol is selected
            //如果在 consumer 端配置了协议，则只选择匹配的协议
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                //consumer端可能支持多种协议，并用逗号分隔，这里会转成数组
                String[] acceptProtocols = queryProtocols.split(",");
                for (String acceptProtocol : acceptProtocols) {
                    // provider提供者的协议是否属于consumer支持的多中协议种的一种，如果是，则直接返回true
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {//如果provider提供者的协议不属于consumer支持的多中协议种的一种，则忽略该提供者
                    continue;
                }
            }
            // 通过 SPI 检测 provider 端协议是否被 consumer 端支持，不支持则抛出异常
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并 url 参数，顺序是 : override > -D > Consumer > Provider
            URL url = mergeUrl(providerUrl);
            // url 参数拼接的字符串且被排序了，
            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                // 忽略重复 key
                continue;
            }
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 原来本地缓存的 <url, Invoker>
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            //获取现有的 url 对应的 invoker
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            // 原来缓存没有或者provider配置变化了
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true;
                    if (url.hasParameter(DISABLED_KEY)) {// 获取 url 中 disable 配置值，取反，然后赋值给 enable 变量
                        enabled = !url.getParameter(DISABLED_KEY, false);
                    } else {
                        enabled = url.getParameter(ENABLED_KEY, true);
                    }
                    if (enabled) {
                        // 调用 Protocol 的 refer 方法构建获取 Invoker，
                        // 具体调用流程是 refer(ProtocolListenerWrapper) -> refer(ProtocolFilterWrapper) -> refer(AbstractProtocol)
                        invoker = new InvokerDelegate<>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    // 新 invoker 放入缓存
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {
                newUrlInvokerMap.put(key, invoker);
            }
        }
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        providerUrl = overrideWithConfigurator(providerUrl);

        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        if ((providerUrl.getPath() == null || providerUrl.getPath()
                .length() == 0) && DUBBO_PROTOCOL.equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);

        // override url with configurator from configurator from "app-name.configurators"
        providerUrl = overrideWithConfigurators(CONSUMER_CONFIGURATION_LISTENER.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (serviceConfigurationListener != null) {
            providerUrl = overrideWithConfigurators(serviceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    /**
     * Close all invokers
     * 从本地内存里清除所有的提供者
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        invokers = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    /***
     * 销毁无用的服务提供者
     * @param oldUrlInvokerMap  旧的服务提供者列表
     * @param newUrlInvokerMap 新的服务提供者列表
     *
     * 1）、检测入参是否仅包含一个 url，且 url 协议头为 empty
     *
     * 2）、若第一步检测结果为 true，表示禁用所有服务，此时销毁所有的 Invoker
     *
     * 3）、若第一步检测结果为 false，此时将 url 转换成 invoker，并得到 <url, Invoker> 的映射关系
     *
     * 4）、合并多组 Invoker
     *
     * 5）、销毁无用 Invoker
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            // 新的 invoker 为空，表明禁用了所有的 provider，这里销毁所有的 invoker
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        // 记录需要被销毁的 invoker 列表
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 新的 invoker 列表中不包含原有的 invoker，则该 invoker 需要被销毁
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<>();
                    }
                    // 该 invoker 添加到销毁列表中
                    deleted.add(entry.getKey());
                }
            }
        }
        // 销毁列表中有数据，需要销毁 invoker
        if (deleted != null) {
            for (String url : deleted) {
                if (url != null) {
                    // 缓存中移除要销毁的 invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            // 销毁 invoker
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    /***
     * 列举出可用的invoker
     * @param invocation
     * @return
     */
    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        //如果配置中心禁用了这个服务，则不能用
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }
        // 多个 group 的话直接返回 invoker 列表。所以如果有多个group的话，不回走router路由
        if (multiGroup) {
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // Get invokers from cache, only runtime routers will be executed.
            //通过路由，获得服务提供者列表
            //路由是沿着一个 RouterChain 链来过滤，从
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }

        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public List<Invoker<T>> getAllInvokers() {
        return invokers;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    public URL getRegisteredConsumerUrl() {
        return registeredConsumerUrl;
    }

    public void setRegisteredConsumerUrl(URL registeredConsumerUrl) {
        this.registeredConsumerUrl = registeredConsumerUrl;
    }

    /***
     * 判断服务是否可用
     * @return
     */
    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void buildRouterChain(URL url) {
        this.setRouterChain(RouterChain.buildChain(url));
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    public List<Invoker<T>> getInvokers() {
        return invokers;
    }

    private boolean isValidCategory(URL url) {
        String category = url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||
                PROVIDERS_CATEGORY.equals(category) ||
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(COMPATIBLE_CONFIG_KEY));
    }
    //重写 DirectoryUrl
    private void overrideDirectoryUrl() {
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        List<Configurator> localConfigurators = this.configurators; // local reference
        doOverrideUrl(localConfigurators);
        List<Configurator> localAppDynamicConfigurators = CONSUMER_CONFIGURATION_LISTENER.getConfigurators(); // local reference
        doOverrideUrl(localAppDynamicConfigurators);
        if (serviceConfigurationListener != null) {
            List<Configurator> localDynamicConfigurators = serviceConfigurationListener.getConfigurators(); // local reference
            doOverrideUrl(localDynamicConfigurators);
        }
    }

    private void doOverrideUrl(List<Configurator> configurators) {
        if (CollectionUtils.isNotEmpty(configurators)) {
            for (Configurator configurator : configurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        ReferenceConfigurationListener(RegistryDirectory directory, URL url) {
            this.directory = directory;
            this.url = url;
            this.initWith(DynamicConfiguration.getRuleKey(url) + CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshInvoker(Collections.emptyList()));
        }
    }

}

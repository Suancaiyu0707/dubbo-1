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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SEPARATOR;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 *
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final static String DEFAULT_ROOT = "dubbo";

    private final String root;

    private final Set<String> anyServices = new ConcurrentHashSet<>();
    //维护URL和监听器的映射关系，可能会有多个监听器监听同一个地址
    /***
     * 0 = {ConcurrentHashMap$MapEntry@4446} "provider://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282" -> " size = 1"
     *  key = {URL@4367} "provider://220.250.64.225:20880/org.apache.dubbo.demo.MockService?anyhost=true&bean.name=org.apache.dubbo.demo.MockService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.MockService&methods=sayHello&pid=14340&release=&side=provider&timestamp=1576479462282"
     *  value = {ConcurrentHashMap@4404}  size = 1
     * 1 = {ConcurrentHashMap$MapEntry@4447} "provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=14340&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576478833784" -> " size = 1"
     *  key = {URL@3847} "provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=14340&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576478833784"
     *  value = {ConcurrentHashMap@4379}  size = 1
     */
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    private final ZookeeperClient zkClient;
    //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096

    /**
     *
     * @param url
     * @param zookeeperTransporter
     * 1、检查url的地址不是0.0.0.0或者anyhost不是true
     * 2、获得注册中心的根路径，默认是/dubbo
     * 3、获得跟注册中心地址的连接，并监听连接状态的变化
     */
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);//获得group属性，默认是dubbo
        if (!group.startsWith(PATH_SEPARATOR)) {//拼接一个 '/'
            group = PATH_SEPARATOR + group;
        }
        this.root = group;//设置zk的根路径
        //获得跟zk连接的client
        zkClient = zookeeperTransporter.connect(url);//默认是curator客户端
        zkClient.addStateListener((state) -> {
            if (state == StateListener.RECONNECTED) {
                logger.warn("Trying to fetch the latest urls, in case there're provider changes during connection loss.\n" +
                        " Since ephemeral ZNode will not get deleted for a connection lose, " +
                        "there's no need to re-register url of this instance.");
                ZookeeperRegistry.this.fetchLatestAddresses();
            } else if (state == StateListener.NEW_SESSION_CREATED) {
                logger.warn("Trying to re-register urls and re-subscribe listeners of this instance to registry...");
                try {
                    ZookeeperRegistry.this.recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            } else if (state == StateListener.SESSION_LOST) {
                logger.warn("Url of this instance will be deleted from registry soon. " +
                        "Dubbo client will try to re-register once a new session is created.");
            } else if (state == StateListener.SUSPENDED) {

            } else if (state == StateListener.CONNECTED) {

            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }
    /***
     * 注册地址，就是创建一个zk的目录
     * @param url
     */
    @Override
    public void doRegister(URL url) {
        try {//provider :/dubbo/org.apache.dubbo.demo.StubService/providers/dubbo%3A%2F%2F220.250.64.225%3A20880%2Forg.apache.dubbo.demo.StubService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.StubService%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.StubService%26methods%3DsayHello%26pid%3D10604%26release%3D%26side%3Dprovider%26stub%3Dorg.apache.dubbo.demo.StubServiceStub%26timestamp%3D1576476419518
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 取消注册，删除这个zk的目录
     * @param url
     */
    @Override
    public void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }
    /**
     *这个接口主要是服务提供者和消费者为自身服务下的 providers/consumers/configurators/routers维护对应的监听器
     *比如:
     *  某个服务的provider要监听对应的configurators
     *  某个服务的provider要监听对应的providers/consumers/configurators/routers
     *
     *
     *
     *
     * @param url
     * @param listener
     *      一个notifyLister，会关联一个ChildListener，这个 ChildListener 是用来监听zk节点的变化，一个ChildListener对应zk上面的一个路径
     * 1、如果url.getServiceInterface是*号，那么通常是dubbo-admin用于订阅所有的消费者和生产者，则只需从根节点/dubbo开始监听即可
     *      a、获取根路径：root=/dubbo
     *      b、检查是否存在对根路径进行监听的映射关系：NotifyListener-->ChildListener的集合，如果没有的话则初始化一个
     *          这里用的是一个ConcurrentMap，是因为可能多个NotifyListener监听同一个路径。
     *      c、判断是否已经为该NotifyListener维护了对应的监听路径的 ChildListener：
     *          没有：则初始化一个 ChildListener，这个ChildListener订阅了root节点下所有路径的变化
     *      d、为ChildListener绑定订阅路径(订阅了root节点下所有路径的变化,包括providers/routers/consumers/configurators路径)
     *  2、如果是普通消费者消费
     *      a、根据url参数获取需要订阅的路径：
     *          根据url类别获取一组要订阅的路径（可能包括providers/consumers/configurators/routers）
     *          如果 category=*，则订阅providers/consumers/configurators/routers
     *          如果 category!=*,则根据category的值进行订阅指定的路径，默认是 /providers。多个路径用','号隔开
     *      b、检查是否存在对url进行监听的映射关系：NotifyListener-->ChildListener的集合，如果没有的话则初始化一个
     *          这里用的是一个ConcurrentMap，是因为可能多个NotifyListener监听同一个路径。
     *      c、判断是否已经为该NotifyListener维护了对应的监听路径url的 ChildListener：
     *          没有：则初始化一个 ChildListener，这个ChildListener订阅了url节点下所有路径的变化
     *      d、在zk上创建对应的临时节点url，并为url绑定监听器ChildListener。
     *          注意：这个ChildListener和NotifyListener是一一对应的，所以当NotifyListener接到通知后，可以找到对应的NotifyListener进行回调。
     *      e、 同时这个ChildListener会监听以下相应的路径：
     *          provider:{/dubbo/org.apache.dubbo.demo.StubService/configurators}
     *          consumer:{/dubbo/org.apache.dubbo.demo.StubService/providers,/dubbo/org.apache.dubbo.demo.StubService/configurators,/dubbo/org.apache.dubbo.demo.StubService/routers}
     *
     *      url:
     *          provider话：provider://192.168.0.105:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=192.168.0.105&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=82030&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576457680054
     *          consumer话：consumer://192.168.0.105/org.apache.dubbo.demo.StubService?category=providers,configurators,routers&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=80530&side=consumer&sticky=false&timestamp=1576456745232
     */
    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            //如果是订阅所有的服务：主要是支持服务治理平台dubbo-admin，平台在启动时会订阅全量接口
            if (ANY_VALUE.equals(url.getServiceInterface())) {//如果是*号，则会订阅(providers/routers/consumers/configurators)
                String root = toRootPath();//获得根路径，通过该dubbo:restery表的group配置，默认是/dubbo
                //检查是否存在对url进行监听的映射关系：NotifyListener-->ChildListener的集合，如果没有的话则初始化一个
                //   这里用的是一个ConcurrentMap，是因为可能多个NotifyListener监听同一个路径。
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                if (listeners == null) {//新建一个子节点的监听器
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                    listeners = zkListeners.get(url);
                }
                //判断是否已经为该NotifyListener维护了对应的监听路径url的 ChildListener，
                ChildListener zkListener = listeners.get(listener);
                //判断是否配置了监听当前节点的自己的监听器
                if (zkListener == null) {
                    //创建一个ChildListener，用于监听/dubbo下所有的子节点的变化，这些子节点也包括所有的服务提供者，比如:com.xuzf.service.HelloService
                    listeners.putIfAbsent(listener, (parentPath, currentChilds) -> {
                        for (String child : currentChilds) {
                            child = URL.decode(child);
                            //如果anyServices 不包含child，则表示是新的节点，则订阅
                            if (!anyServices.contains(child)) {
                                //订阅所有的服务，并监听服务节点的变化
                                anyServices.add(child);
                                subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child,
                                        Constants.CHECK_KEY, String.valueOf(false)), listener);
                            }
                        }
                    });
                    zkListener = listeners.get(listener);
                }
                zkClient.create(root, false);//创建非持久化节点
                List<String> services = zkClient.addChildListener(root, zkListener);//增加当前节点的订阅，并且会返回该节点所有子节点列表
                //订阅获得所有的服务提供者
                if (CollectionUtils.isNotEmpty(services)) {
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {//普通消费者的订阅逻辑， 如果 category=*，则订阅providers/consumers/configurators/routers
                List<URL> urls = new ArrayList<>();
                //根据url路径类别获取需要订阅的路径：
                //  如果 category=*，则订阅providers/consumers/configurators/routers
                //  如果 根据category的值进行订阅指定的路径，默认是 /providers
                for (String path : toCategoriesPath(url)) {
                    //检查是否存在对url进行监听的映射关系：NotifyListener-->ChildListener的集合，如果没有的话则初始化一个
                    //   这里用的是一个ConcurrentMap，是因为可能多个NotifyListener监听同一个路径。
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                        listeners = zkListeners.get(url);
                    }
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        //初始化一个 ChildListener，这个ChildListener订阅了url节点下所有路径的变化
                        //当节点或者子节点的信息发送变化，则会调用FallbackRegistry的notify方法
                        listeners.putIfAbsent(listener, (parentPath, currentChilds) -> ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds)));
                        zkListener = listeners.get(listener);
                    }
                    //创建临时节点
                    zkClient.create(path, false);
                    //对应的zkListener会监听订阅的服务下的providers/consumers/configurators/routers等，同时拉取子节点的数据
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                //拉取到待监听的数据并通过listener进行通知给客户端
                notify(url, listener, urls);//回调NotifyListener,更新本地缓存信息
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     *
     * @param url
     *        consumer:
     *        provider:
     * @param listener
     */
    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
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
    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<>();
            for (String path : toCategoriesPath(url)) {
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }
    // provider: dubbo://192.168.0.105:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=79756&release=&revision=1.0.0&side=provider&timestamp=1576456269728&version=1.0.0
    private String toServicePath(URL url) {//consumer: consumer://192.168.0.105/org.apache.dubbo.demo.StubService?category=providers,configurators,routers&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=80530&side=consumer&sticky=false&timestamp=1576456745232
        String name = url.getServiceInterface();//org.apache.dubbo.demo.EventNotifyService或org.apache.dubbo.demo.StubService
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }
    /***
     *
     * @param url :
     *            consumer://192.168.0.105/org.apache.dubbo.demo.StubService?category=providers,configurators,routers&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=80530&side=consumer&sticky=false&timestamp=1576456745232
     *            provider://192.168.0.105:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=192.168.0.105&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=82030&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576457680054
     * @return
     *      consumer: {/dubbo/org.apache.dubbo.demo.StubService/providers,/dubbo/org.apache.dubbo.demo.StubService/configurators,/dubbo/org.apache.dubbo.demo.StubService/routers}
     *      provider: {/dubbo/org.apache.dubbo.demo.StubService/configurators}
     */
    private String[] toCategoriesPath(URL url) {
        String[] categories;
        if (ANY_VALUE.equals(url.getParameter(CATEGORY_KEY))) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {
            categories = url.getParameter(CATEGORY_KEY, new String[]{DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];//conumser: {providers,configurators,routers} provider:{configurators}
        for (int i = 0; i < categories.length; i++) {
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i];
        }
        return paths;
    }
    //dubbo://192.168.0.105:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=79756&release=&revision=1.0.0&side=provider&timestamp=1576456269728&version=1.0.0

    /**
     *
     * @param url
     *      provider:
     *      consumer:
     *
     * @return
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }
    //url：dubbo://192.168.0.105:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=79756&release=&revision=1.0.0&side=provider&timestamp=1576456269728&version=1.0.0
    /**
     *
     * @param url
     *      provider:
     *      consumer:
     *
     * @return
     */
    private String toUrlPath(URL url) {
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }
    /**
     *
     * @param consumer
     *      provider:
     *      consumer:
     *
     * @return
     */
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(providers)) {
            for (String provider : providers) {
                provider = URL.decode(provider);
                if (provider.contains(PROTOCOL_SEPARATOR)) {
                    URL url = URL.valueOf(provider);
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }
    //provider://192.168.0.105:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=192.168.0.105&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=82030&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576457680054
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf(PATH_SEPARATOR);
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = URLBuilder.from(consumer)
                    .setProtocol(EMPTY_PROTOCOL)
                    .addParameter(CATEGORY_KEY, category)
                    .build();
            urls.add(empty);
        }
        return urls;//将consumer路径转换成empty://192.168.0.105:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=192.168.0.105&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=82030&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576457680054
    }

    /**
     * When zookeeper connection recovered from a connection loss, it need to fetch the latest provider list.
     * re-register watcher is only a side effect and is not mandate.
     */
    private void fetchLatestAddresses() {
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Fetching the latest urls of " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

}

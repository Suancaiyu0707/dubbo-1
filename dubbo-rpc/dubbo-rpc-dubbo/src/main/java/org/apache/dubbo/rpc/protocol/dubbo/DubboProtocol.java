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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;

    /**
     * <host:port,Exchanger>
     */
    private final Map<String, List<ReferenceCountExchangeClient>> referenceClientMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> optimizers = new ConcurrentHashSet<>();
    /**
     * consumer side export a stub service for dispatching event
     * servicekey-stubmethods
     */
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<>();

    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            Invoker<?> invoker = getInvoker(channel, inv);
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
            Result result = invoker.invoke(inv);
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, url.getParameter(INTERFACE_KEY), new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // load
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }

        return INSTANCE;
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getAttachments().get(PATH_KEY);

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }

        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(port, path, (String) inv.getAttachments().get(VERSION_KEY), (String) inv.getAttachments().get(GROUP_KEY));
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + getInvocationWithoutData(inv));
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /****
     * 暴露服务
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     * 1、根据服务提供者地址获得服务的key，比如：org.apache.dubbo.demo.DemoService:20880
     * 2、将invoker包装成一个 DubboExporter
     * 3、获得dubbo.stub.event和is_callback_service属性值
     * 4、启动服务器 NettyServer，用于接收监听客户端请求
     *      注意，这里是一个服务器host:port创建一个NettyServer
     * 5、初始化序列化的优化器
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();//dubbo://192.168.0.108:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.108&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=5410&release=&side=provider&timestamp=1575332340328

        // export service. 获得服务的key
        String key = serviceKey(url);//org.apache.dubbo.demo.DemoService:20880
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);//false
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);//获得回调服务
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }
        //根据url地址中的服务器ip创建一个nettyServer，也就是启动Netty服务器
        openServer(url);//dubbo://220.250.64.225:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
        // 初始化序列化优化器
        optimizeSerialization(url);

        return exporter;
    }

    /***
     * 在服务端启动服务器NettyServer，注意这个NettyServer只跟服务提供者ip有关
     * @param url
     * 1、根据服务提供者地址获得服务端地址：192.168.0.108:20880
     * 2、检查缓存 serverMap，避免重复启动服务NettyServer
     * 3、如果缓存里没有，则开始启动一个NettyServer服务
     * 4、如果缓存里有，则重置这个NettyServer服务
     */
    private void openServer(URL url) {//dubbo://192.168.0.108:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.108&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=5410&release=&side=provider&timestamp=1575332340328
        // find server. 查找服务提供者的地址，与服务接口无关
        String key = url.getAddress();//192.168.0.108:20880
        //client can export a service which's only for server to invoke
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);//true 可以暴露一个仅当前 JVM 可调用的服务。目前该配置项已经不存在
        if (isServer) {
            ProtocolServer server = serverMap.get(key);//检查本地内存，该服务是否已启动，避免重复启动
            if (server == null) {//通信服务器不存在，调用 #createServer(url) 方法，创建服务器。
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {//通信服务器已存在，调用 Server#reset(url) 方法，重置服务器的属性。
                server.reset(url);
            }
        }
    }

    /***
     *
     * @param url
     *      dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&channel.readonly.sent=true&codec=dubbo&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&heartbeat=60000&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=26573&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576486511601
     * @return
     * 1、根据服务提供者地址维护一个url
     *      开启 server 关闭时发送 READ_ONLY 事件
     *      开启 heartbeat
     *      设置编码协议为dubbo
     * 2、通过Exchangers启动一个NettyServer，并绑定监听端口
     * 3、检查client属性值对应的Transporter SPI实现类是否存在，默认是NettyTransporter
     */
    private ProtocolServer createServer(URL url) {//旧的：dubbo://220.250.64.225:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
        //新的：dubbo://220.250.64.225:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&channel.readonly.sent=true&codec=dubbo&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&heartbeat=60000&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
        url = URLBuilder.from(url)
                // 开启 server 关闭时发送 READ_ONLY 事件
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // 默认开启 heartbeat
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
                //设置编码协议为dubbo
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);//网络服务：默认是netty
        // 检查 Server 的 Dubbo SPI 拓展是否存在
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }
        // 启动服务器
        ExchangeServer server;
        try {
            server = Exchangers.bind(url, requestHandler);//dubbo://220.250.64.225:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&channel.readonly.sent=true&codec=dubbo&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&heartbeat=60000&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        // 校验 Client 的 Dubbo SPI 拓展是否存在
        str = url.getParameter(CLIENT_KEY);//client属性值默认是netty
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        //返回包装的 DubboProtocolServer7
        return new DubboProtocolServer(server);
    }

    /**
     *  创建一个序列化优化器
     * @param url
     * @throws RpcException
     * 1、通过服务提供者获取optimizer属性，默认是空的,optimizer定义了优化器的类名，必须是SerializationOptimizer的实现类
     */
    private void optimizeSerialization(URL url) throws RpcException {
        //服务提供者获取optimizer属性，默认是空的,optimizer定义了优化器的类名，必须是SerializationOptimizer的实现类
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    /***
     * 将Invokers绑定一组客户端连接NettyClient，这样后续针对这个invoker的调用，都会交给相应的NettyClient处理
     * @param serviceType org.apache.dubbo.demo.StubService
     * @param url dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=63743&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576754446869
     * @param <T>
     * @return
     * @throws RpcException
     * 1、根据引用的地址获取引用服务对应的客户端连接数组：nettyClients
     * 2、把serviceType、invokers和nettyClients进行绑定，这样后续针对这个invoker的调用，都会交给相应的NettyClient处理。
     */
    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);

        // 创建一个DubboInvoker，绑定了连接服务提供者的客户端连接NettyClient
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);//本地缓存已初始化的DubboInvoker

        return invoker;
    }

    /***
     *
     * 根据引用的服务提供者的地址，获得连接服务提供者的远程通信客户端数组：NettyClient
     * @param url dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=63743&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576754446869
     * @return
     * 获得连接到服务提供者的客户端连接数组NettyClient.
     *      1、如果<dubbo:reference>配置了connections属性值，且大于0，则这个服务引用不会使用共享的NettyClient连接，则会为该服务引用者维护独立的NettyClient数组
     *      2、如果<dubbo:reference>没有connections属性值，则这个服务引用会使用共享的NettyClient连接。
     */
    private ExchangeClient[] getClients(URL url) {
        // 是否共享连接
        boolean useShareConnect = false;
        //从url中获取connections配置，默认是0
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {//如果connections=0，默认共享
            useShareConnect = true;

            /**
             * The xml configuration should have a higher priority than properties.
             *///获得shareconnections属性，并获得共享连接数，默认是1
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            shareClients = getSharedClient(url, connections);
        }
        //返回指向服务端的共享的客户端连接数组：ExchangeClient
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            if (useShareConnect) {// 共享
                clients[i] = shareClients.get(i);

            } else {// 不共享
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *  获得共享客户端连接(事实上，这些NettyClient跟服务提供者无关，只是当某个NettyClient被某个引用服务绑定了，则会标记这个引用服务)
     * @param url
     *      dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=63743&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576754446869
     * @param connectNum 共享的连接数
     *  1、获得服务端的地址：220.250.64.225:20880
     *  2、根据服务端的地址，获得对应的所有的客户端连接(包括所有服务引用者的连接，因为这些连接是所有的consumer共享)
     *  3、检查针对这个服务端地址的所有客户端连接，如果已存在的所有的客户端连接数都可用，则可以直接返回这些共享的客户端连接。同时会记录共享这些连接的Invoker次数+1
     *  4、如果当前的针对这个服务端地址的所有客户端连接中，存在一些NettyClient已被关闭的话，那么我们就需要创建新的NettyClient，维持NettyClient的连接数
     *  5、如果当前存在指向该服务端的地址的客户端连接，则遍历每个NettyClient：
     *     如果遍历到有客户端连接已使用完或者被关闭了，则创建一个新的NettyClient替换掉这个无效的连接
     *     如果遍历到的客户端连接目前还在使用，则ExchangeClient 会被新的invoker共享，所以被引用的次数需要+1
     */
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();//获得服务提供者的地址，也就是Netty网络服务端的地址：220.250.64.225:20880
        List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);//检查该服务提供者地址是否已创建客户端实例
        //检查客户端连接是否可用，只要任何一个不可用，则返回false
        if (checkClientCanUse(clients)) {
            batchClientRefIncr(clients);
            return clients;
        }

        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            clients = referenceClientMap.get(key);//判断是否已为Netty服务端创建好客户端连接
            // dubbo check
            if (checkClientCanUse(clients)) {
                batchClientRefIncr(clients);
                return clients;
            }

            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // 创建是第一次向服务端创建连接
            if (CollectionUtils.isEmpty(clients)) {
                //初始化创建connectNum个共享连接，并返回
                clients = buildReferenceCountExchangeClientList(url, connectNum);
                referenceClientMap.put(key, clients);

            } else {
                //如果当前存在指向该服务端的地址的客户端连接，则遍历每个NettyClient：
                for (int i = 0; i < clients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
                    //如果遍历到有客户端连接已使用完或者被关闭了，则创建一个新的NettyClient替换掉这个无效的连接
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        clients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    //走到这边，说明这个referenceCountExchangeClient 会被新的invoker共享，所以被引用的次数需要+1
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }

            /**
             * I understand that the purpose of the remove operation here is to avoid the expired url key
             * always occupying this memory space.
             */
            locks.remove(key);

            return clients;
        }
    }

    /**
     * Check if the client list is all available
     * 检查客户端连接是否可用
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     * 1、如果遍历到有客户端连接已使用完或者被关闭了，只有任何一个客户端连接不可用，则返回false
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }
        //遍历每个 客户端连接
        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            //如果遍历到有客户端连接已使用完或者被关闭了，只有任何一个客户端连接不可用，则返回false
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     *
     * @param referenceCountExchangeClients
     * 如果有新的服务引用invoker共享这些客户端连接，则需要遍历客户端连接列表，并为每个NettyClient的引用+1
     *      注意：只有引用次数为0，这个nettyClient才能被关闭
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }
        //为每个NettyClient记录被Invoker引用的次数，只有引用次数为0，这个nettyClient才能被关闭
        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=63743&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576754446869
     * @param connectNum
     * @return
     *  遍历，根据url地址获得指定数量的客户端端链接：ReferenceCountExchangeClient
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();
        //根据引用服务的地址，创建跟客户端的连接NettyClient
        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *  根据服务提供者的地址，创建一个连接到服务端的客户端连接NettyClient
     * @param url dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=63743&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576754446869
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);

        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * 根据url创建一个客户端连接NettyClient
     *
     * @param url dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=63743&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576754446869
     * 1、获得网络连接方式，默认是Netty
     * 2、获得网络传输的序列化方式，默认是dubbo
     * 3、设置客户端和服务端的心跳间隔
     * 4、创建一个ExchangeClient,ExchangeClient其实是一个NettyClient连接。
     *        如果lazy=true，则延迟创建TCP连接,这样会在真实发生RPC调用时创建
     */
    private ExchangeClient initClient(URL url) {

        // client type setting. 从url中获得网络连接方式client属性，默认是netty。
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));
        //获得网络传输的序列化方式，默认是dubbo，可用 codec 指定
        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
        //设置客户端和服务端的心跳间隔
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // 创建一个连接NettyServer的客户端连接
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {//
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {//立即发起TCP连接，默认是netty传输
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            List<ReferenceCountExchangeClient> clients = referenceClientMap.remove(key);

            if (CollectionUtils.isEmpty(clients)) {
                continue;
            }

            for (ReferenceCountExchangeClient client : clients) {
                closeReferenceCountExchangeClient(client);
            }
        }

        stubServiceMethodsMap.clear();
        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /**
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}

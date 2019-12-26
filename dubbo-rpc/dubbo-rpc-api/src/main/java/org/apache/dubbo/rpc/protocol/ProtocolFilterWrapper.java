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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_FILTER_KEY;

/**
 * ListenerProtocol
 *org.apache.dubbo.rpc.Filter：
 * echo=org.apache.dubbo.rpc.filter.EchoFilter
 * generic=org.apache.dubbo.rpc.filter.GenericFilter
 * genericimpl=org.apache.dubbo.rpc.filter.GenericImplFilter
 * token=org.apache.dubbo.rpc.filter.TokenFilter
 * accesslog=org.apache.dubbo.rpc.filter.AccessLogFilter
 * activelimit=org.apache.dubbo.rpc.filter.ActiveLimitFilter
 * classloader=org.apache.dubbo.rpc.filter.ClassLoaderFilter
 * context=org.apache.dubbo.rpc.filter.ContextFilter
 * consumercontext=org.apache.dubbo.rpc.filter.ConsumerContextFilter
 * exception=org.apache.dubbo.rpc.filter.ExceptionFilter
 * executelimit=org.apache.dubbo.rpc.filter.ExecuteLimitFilter
 * deprecated=org.apache.dubbo.rpc.filter.DeprecatedFilter
 * compatible=org.apache.dubbo.rpc.filter.CompatibleFilter
 * timeout=org.apache.dubbo.rpc.filter.TimeoutFilter
 *
 *  是protocol的一个拓展类，用于给 Invoker 增加过滤链
 *
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    /***
     * 创建调用链
     * @param invoker url
     * @param key  reference.filter 或 service.filter
     * @param group CONSUMER或PROVIDER
     * @param <T>
     * @return
     * 1、根据key从invoker的url里解析对应的属性值
     * 2、根据key的值和group过滤获得Filter对应的实现类(实现类在org.apache.dubbo.rpc.Filter里添加，采用SPI)
     */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        Invoker<T> last = invoker;
        //根据key的值和group过滤获得Filter对应的实现类(实现类在org.apache.dubbo.rpc.Filter里添加，采用SPI)
        //<dubbo:service interface="org.apache.dubbo.demo.StubService" ref="stubService" filter="demo" />
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class)//获得Filter的拓展类加载器
                .getActivateExtension(invoker.getUrl(), key, group);
        //如果满足条件的filers不为空，倒序循环 Filter ，创建带 Filter 链的 Invoker 对象
        if (!filters.isEmpty()) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new Invoker<T>() {
                    //获得待过滤的服务的接口
                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        Result asyncResult;
                        try {
                            //调用过滤器执行
                            asyncResult = filter.invoke(next, invocation);
                        } catch (Exception e) {
                            if (filter instanceof ListenableFilter) {// Deprecated!
                                Filter.Listener listener = ((ListenableFilter) filter).listener();
                                if (listener != null) {
                                    listener.onError(e, invoker, invocation);
                                }
                            } else if (filter instanceof Filter.Listener) {
                                Filter.Listener listener = (Filter.Listener) filter;
                                listener.onError(e, invoker, invocation);
                            }
                            throw e;
                        } finally {

                        }
                        return asyncResult.whenCompleteWithContext((r, t) -> {
                            if (filter instanceof ListenableFilter) {// Deprecated!
                                Filter.Listener listener = ((ListenableFilter) filter).listener();
                                if (listener != null) {
                                    if (t == null) {
                                        listener.onMessage(r, invoker, invocation);
                                    } else {
                                        listener.onError(t, invoker, invocation);
                                    }
                                }
                            } else if (filter instanceof Filter.Listener) {
                                Filter.Listener listener = (Filter.Listener) filter;
                                if (t == null) {
                                    listener.onMessage(r, invoker, invocation);
                                } else {
                                    listener.onError(t, invoker, invocation);
                                }
                            }
                        });
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }

        return last;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    /***
     * 暴露服务，每个服务暴露的时候都要分成两步：
     *      1、根据暴露的协议，判断是否需要通过RegistryProtocol将自己注册到注册中心上去，维护跟注册中心的zk连接(一个服务端注册中心配置只要维护一个zkclient)
     *            通常远程暴露会分两步，第一步就是通过该分支向注册中心创建目录，并获得注册中心的客户端连接。(注意，这一步只是将自己的网络地址暴露到注册中心上，保证可以被消费者获取)
     *      2、真正将服务暴露给其它应用进行调用，主要有两种情况：
     *          如果本地暴露，也就是不需要对外暴露，这个时候协议是通过InjvmProtocol.export将自己的暴露关系维护到内存中，并不会通过网络暴露出去。
     *          如果是远程暴露的话，除了上面它会把自己地址添加到注册中心后，就需要通过NettyServer将自己真正暴露出去，这样可以保证能接收到远程服务的请求。
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     * 1、判断是registry或者service-discovery-registry的url，表示需要把自己注册到注册中心上，以便于远程服务发现自己：
     *       将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支。
     * 2、如果不是registry或者service-discovery-registry的url，则通常是真正的将注册暴露出去，便于别人调用：
     *      如果本地暴露，也就是不需要对外暴露，这个时候协议是通过InjvmProtocol.export将自己的暴露关系维护到内存中，并不会通过网络暴露出去。
     *      如果是远程暴露的话，除了上面它会把自己地址添加到注册中心后，就需要通过NettyServer将自己真正暴露出去，这样可以保证能接收到远程服务的请求。
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支。不管是生产者还是消费，如果需要通过注册中心暴露服务的话，都会先走这个分支，然后在这个分支里调用下面的分支
        if (UrlUtils.isRegistry(invoker.getUrl())) {//如果是registry或者service的url，则走到这个分支
            return protocol.export(invoker);
        }
        // 真正将服务暴露出去，通过本地内存(如果是本地暴露的话)或NettyServer(如果是远程暴露的话)将自己暴露出去。InjvmProtocol或DubboProtocol的export会走到这个分支:
        //      先为Invoker绑定一个过滤链，然后暴露服务提供者
        return protocol.export(buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    /***
     * 服务引用时会通过该方法进行引用
     *      1、根据引用的协议，判断是否需要通过RegistryProtocol将自己注册到注册中心上去，维护跟注册中心的zk连接(一个客户端注册中心配置只要维护一个zkclient)
     *            通常远引用远程服务会分两步，第一步就是通过该分支向注册中心创建目录，并获得注册中心的客户端连接。(注意，这一步只是将自己的网络地址暴露到注册中心上，保证可以被dubbo-admin获取)
     *      2、真正进行服务引用，主要有两种情况：
     *          如果本地引用，
     *          如果是远程引用的话，除了上面它会把自己地址添加到注册中心后，DubboProtocol会根据url获得远程服务的引用，还会将invoker绑定NettyClient数组，后续调用invoker的方法，都要通过该NettyClient进行网络请求。
     * @param type Service class 被引用的服务类型
     * @param url  被引用的服务的远程地址
     * @param <T>
     * @return
     * @throws RpcException
     * 1、判断是registry或者service-discovery-registry的url，表示需要把自己注册到注册中心上，以便于dubbo-admin发现自己：
     *       将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支。
     * 2、如果不是registry或者service-discovery-registry的url，则通常是真正的将注册暴露出去，便于别人调用：
     *      如果本地暴露，
     *      如果是引用远程服务话，除了上面它会把自己地址添加到注册中心后，还会将invoker绑定NettyClient数组，后续调用invoker的方法，都要通过该NettyClient进行网络请求。
     * 3、获得远程服务的引用对象后，会为服务引用对象invoker包装一层Filter的过滤链
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 注册中心分支
        // 远程引用会将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支。不管是生产者还是消费，如果需要通过注册中心暴露服务的话，都会先走这个分支，然后在这个分支里调用下面的分支
        if (UrlUtils.isRegistry(url)) {//registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=41141&qos.port=33333&refer=dubbo%3D2.0.2%26init%3Dfalse%26interface%3Dorg.apache.dubbo.demo.StubService%26lazy%3Dfalse%26methods%3DsayHello%26pid%3D41141%26register.ip%3D220.250.64.225%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1576810746199&registry=zookeeper&timestamp=1576812247269
            return protocol.refer(type, url);
        }
        /***
         * 引用服务，返回 Invoker 对象
         * 1、引用服务，最终返回 Invoker
         * 2、创建带有 Filter 过滤链的 Invoker 对象
         */
        return buildInvokerChain(protocol.refer(type, url), REFERENCE_FILTER_KEY, CommonConstants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

}

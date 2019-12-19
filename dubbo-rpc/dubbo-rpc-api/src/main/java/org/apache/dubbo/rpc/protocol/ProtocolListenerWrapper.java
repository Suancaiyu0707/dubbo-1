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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.ExporterListener;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.listener.ListenerExporterWrapper;
import org.apache.dubbo.rpc.listener.ListenerInvokerWrapper;

import java.util.Collections;
import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.EXPORTER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INVOKER_LISTENER_KEY;

/**
 * ListenerProtocol
 * Protocol 的 Wrapper 拓展实现类，用于给 Exporter 增加 ExporterListener ，监听 Exporter 暴露完成和取消暴露完成
 */
public class ProtocolListenerWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolListenerWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
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
     * 3、服务暴露后，根据exporter.listener属性初始化一个 ExporterListener 实例，用于暴露服务的结果，并将该 ExporterListener 和 Exporter进行一一对应。
     *      注意：从这里，我们可以知道。通过自己拓展实现一个ExporterListener类来监听对应服务的暴露和移除的结果。
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 是否需要将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支
        if (UrlUtils.isRegistry(invoker.getUrl())) {//判断是否是service-discovery-registry或registry协议的url
            return protocol.export(invoker);
        }
        // 如果本地暴露，也就是不需要对外暴露，这个时候协议是通过InjvmProtocol.export将自己的暴露关系维护到内存中，并不会通过网络暴露出去。
        // 如果是远程暴露的话，除了上面它会把自己地址添加到注册中心后，就需要通过NettyServer将自己真正暴露出去，这样可以保证能接收到远程服务的请求。
        //服务暴露完后，会未exporter绑定一个ExporterListener监听器，这个监听器可以监听服务的暴露情况，默认是不做任何处理，用户可以通过filter进行指定监听器
        //  eg：<dubbo:service interface="com.alibaba.dubbo.demo.DemoService" ref="demoService" filter="demo" />
        return new ListenerExporterWrapper<T>(protocol.export(invoker),
                Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)
                        .getActivateExtension(invoker.getUrl(), EXPORTER_LISTENER_KEY)));
    }

    /***
     * 服务引用时会通过该方法进行引用
     * @param type Service class 被引用的服务类型
     * @param url  被引用的服务的远程地址
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 注册中心分支
        // 远程引用会将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支。不管是生产者还是消费，如果需要通过注册中心暴露服务的话，都会先走这个分支，然后在这个分支里调用下面的分支
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
        /**
         * 引用服务
         * 1、引用服务，并获得 Invoker
         * 2、将将 Invoker 跟监听器InvokerListener进行绑定，该监听器用于监听服务的引用
         * 3、返回绑定后的ListenerInvokerWrapper
         */
        return new ListenerInvokerWrapper<T>(protocol.refer(type, url),
                Collections.unmodifiableList(
                        ExtensionLoader.getExtensionLoader(InvokerListener.class)
                                .getActivateExtension(url, INVOKER_LISTENER_KEY)));
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

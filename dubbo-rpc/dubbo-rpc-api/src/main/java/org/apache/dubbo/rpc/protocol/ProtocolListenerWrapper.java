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
     *      1、通过RegistryProtocol将自己注册到注册中心上去，维护跟注册中心的zk连接(一个服务端注册中心配置只要维护一个zkclient)
     *      2、通过NettyServer将自己暴露到网络层
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     * 1、判断是registry或者service-discovery-registry的url，
     *          表示将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支
     *
     * 2、如果不是registry或者service-discovery-registry的url，
     *      则表示开始真正将服务暴露出去，通过NettyServer将自己暴露到网络层。DubboProtocol的export会走到这个分支
     *
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 将自己注册到注册中心上去，RegistryProtocol的export会走到这个分支
        if (UrlUtils.isRegistry(invoker.getUrl())) {//判断是否是service-discovery-registry或registry协议的url
            return protocol.export(invoker);
        }
        // 真正将服务暴露出去，通过NettyServer将自己暴露到网络层。DubboProtocol的export会走到这个分支:
        //      先为Invoker绑定一个过滤链，然后暴露服务提供者
        return new ListenerExporterWrapper<T>(protocol.export(invoker),
                Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)//interface org.apache.dubbo.rpc.ExporterListener
                        .getActivateExtension(invoker.getUrl(), EXPORTER_LISTENER_KEY)));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
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

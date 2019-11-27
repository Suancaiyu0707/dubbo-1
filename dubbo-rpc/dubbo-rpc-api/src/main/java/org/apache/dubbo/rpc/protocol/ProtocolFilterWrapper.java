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
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class)//获得Filter的拓展类加载器
                .getActivateExtension(invoker.getUrl(), key, group);
        //如果满足条件的filers不为空
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

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        if (UrlUtils.isRegistry(invoker.getUrl())) {
            return protocol.export(invoker);
        }
        return protocol.export(buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
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

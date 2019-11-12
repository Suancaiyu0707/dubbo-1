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
package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;

/**
 * 如果一个扩展类包含了其他拓展点作为构造函数的参数，则这个扩展类被认为是Wrapper类
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        Invoker<T> last = invoker;
        /***加载拓展类中的过滤器
         * 0 = {HashMap$Node@2498} "exception" -> "class com.alibaba.dubbo.rpc.filter.ExceptionFilter"
         * 1 = {HashMap$Node@2499} "cache" -> "class com.alibaba.dubbo.cache.filter.CacheFilter"
         *      在缓存过滤器中会加载拓展类中的缓存类型工程
         *          0 = {HashMap$Node@2626} "jcache" -> "class com.alibaba.dubbo.cache.support.jcache.JCacheFactory"
         *          1 = {HashMap$Node@2627} "lru" -> "class com.alibaba.dubbo.cache.support.lru.LruCacheFactory"
         *          2 = {HashMap$Node@2628} "threadlocal" -> "class com.alibaba.dubbo.cache.support.threadlocal.ThreadLocalCacheFactory"
         * 2 = {HashMap$Node@2500} "genericimpl" -> "class com.alibaba.dubbo.rpc.filter.GenericImplFilter"
         * 3 = {HashMap$Node@2501} "deprecated" -> "class com.alibaba.dubbo.rpc.filter.DeprecatedFilter"
         * 4 = {HashMap$Node@2502} "classloader" -> "class com.alibaba.dubbo.rpc.filter.ClassLoaderFilter"
         * 5 = {HashMap$Node@2503} "monitor" -> "class com.alibaba.dubbo.monitor.support.MonitorFilter"
         * 6 = {HashMap$Node@2504} "echo" -> "class com.alibaba.dubbo.rpc.filter.EchoFilter"
         * 7 = {HashMap$Node@2505} "generic" -> "class com.alibaba.dubbo.rpc.filter.GenericFilter"
         * 8 = {HashMap$Node@2506} "timeout" -> "class com.alibaba.dubbo.rpc.filter.TimeoutFilter"
         * 9 = {HashMap$Node@2507} "token" -> "class com.alibaba.dubbo.rpc.filter.TokenFilter"
         * 10 = {HashMap$Node@2508} "accesslog" -> "class com.alibaba.dubbo.rpc.filter.AccessLogFilter"
         * 11 = {HashMap$Node@2509} "trace" -> "class com.alibaba.dubbo.rpc.protocol.dubbo.filter.TraceFilter"
         * 12 = {HashMap$Node@2510} "compatible" -> "class com.alibaba.dubbo.rpc.filter.CompatibleFilter"
         * 13 = {HashMap$Node@2511} "executelimit" -> "class com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter"
         * 14 = {HashMap$Node@2512} "future" -> "class com.alibaba.dubbo.rpc.protocol.dubbo.filter.FutureFilter"
         * 15 = {HashMap$Node@2513} "context" -> "class com.alibaba.dubbo.rpc.filter.ContextFilter"
         * 16 = {HashMap$Node@2514} "activelimit" -> "class com.alibaba.dubbo.rpc.filter.ActiveLimitFilter"
         * 17 = {HashMap$Node@2515} "validation" -> "class com.alibaba.dubbo.validation.filter.ValidationFilter"
         *          "jvalidation" -> "class com.alibaba.dubbo.validation.support.jvalidation.JValidation"
         * 18 = {HashMap$Node@2516} "consumercontext" -> "class com.alibaba.dubbo.rpc.filter.ConsumerContextFilter"
         */
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);
        if (!filters.isEmpty()) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new Invoker<T>() {

                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    public Result invoke(Invocation invocation) throws RpcException {
                        return filter.invoke(next, invocation);
                    }

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
        //invoker: com.alibaba.dubbo.demo.DemoService -> injvm://127.0.0.1/com.alibaba.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.1.13&bind.port=20880&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&methods=sayHello&pid=37821&qos.port=22222&side=provider&timestamp=1527411329854
        //
        return last;
    }

    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);//通过协议将invoker暴露出去
        }
        return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));//暴露invoker链
    }

    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        return buildInvokerChain(protocol.refer(type, url), Constants.REFERENCE_FILTER_KEY, Constants.CONSUMER);
    }

    public void destroy() {
        protocol.destroy();
    }

}
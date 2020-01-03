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
package org.apache.dubbo.rpc.proxy.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Constructor;

import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_KEY;

/**
 * StubProxyFactoryWrapper
 * 本地存根的代理工厂实现类
 * 在根据invoker生成代理对象之前，StubProxyFactoryWrapper会先把被代理的实际的接口对象封装成一个stub/local对象，再交由具体的代理对象工厂生成代理对象
 *
 * 每一个ProxyFactory有包装类型的拓展实现，因此无论是 JdkProxyFactory还是JavassistProxyFactory实例，都会被包装成StubProxyFactoryWrapper对象
 *  所以ProxyFactory的ExtensionLoader.cachedInstances集合包含的对象是这样的：
 *      key = "javassist"
 *      value = {Holder@3126}
 *          value = {StubProxyFactoryWrapper@3152}
 *          proxyFactory = {JavassistProxyFactory@3162}
 *          protocol = {Protocol$Adaptive@3163}
 *      key = "jdk"
 *      value = {Holder@3126}
 *          value = {StubProxyFactoryWrapper@3162}
 *          proxyFactory = {JdkProxyFactory@3163}
 *          protocol = {Protocol$Adaptive@3163}
 */
public class StubProxyFactoryWrapper implements ProxyFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(StubProxyFactoryWrapper.class);
    /***
     * 本质是一个ProxyFactory$Adaptive 对象
     * 会根据URL配置，使用具体的实现：JavassistProxyFactory/JdkProxyFactory
     */
    private final ProxyFactory proxyFactory;
    /**
     * Protocol$Adaptive 对象
     */
    private Protocol protocol;

    public StubProxyFactoryWrapper(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        return proxyFactory.getProxy(invoker, generic);
    }

    /***
     *  在根据invoker生成代理对象之前，StubProxyFactoryWrapper会先把被代理的实际的接口对象封装成一个stub/local对象，再交由具体的代理对象工厂生成代理对象
     * @param invoker
     *      eg：
     * @param <T>
     * @return
     * @throws RpcException
     *StubProxyFactoryWrapper->ProxyFactory$Adaptive->JavassistProxyFactory/JdkProxyFactory
     * 1、根据实际的引用的接口信息invoker由具体的代理工厂JavassistProxyFactory/JdkProxyFactory生成一个代理对象proxy
     * 2、检查该invoker实现的接口不是泛化接口GenericService
     * 3、检查引用的配置信息，如果是本地存根调用(也就是配置了stub或local,目前比较推荐用stub属性配置)。注意服务端的服务配置也会透传到消费端
     *      a、如果是本地存根调用
     *          1）根据存根的属性获得对应的存根类信息
     *          2）检查存根类里是否包含一个构造函数，该构造函数只有一个参数，且参数为被引用的接口
     *          3）根据上面生成的代理对象和引用接口，构造一个存根类的实例
     *          4）返回这个存根实例(所以后续的调用会调用存根的方法，而不是被引用接口的代理对象)
     *      b、如果表示本地存根调用，则直接返回这个被引用接口的代理对象。
     */
    @Override
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        //根据实际的引用的接口信息invoker由具体的代理工厂JavassistProxyFactory/JdkProxyFactory生成一个代理对象：proxy = {proxy0@4454}
        //                                                                              handler = {InvokerInvocationHandler@4435}
        T proxy = proxyFactory.getProxy(invoker);
        if (GenericService.class != invoker.getInterface()) {//如果不是泛化调用
            //获得调用服务的url
            URL url = invoker.getUrl();
            //检查引用的配置信息，如果是本地存根调用(也就是配置了stub或local,目前比较推荐用stub属性配置)
            // eg：org.apache.dubbo.demo.StubServiceStub
            String stub = url.getParameter(STUB_KEY, url.getParameter(LOCAL_KEY));
            if (ConfigUtils.isNotEmpty(stub)) {//如果不为空的话，则表示是一个本地存根调用
                //开始获得存根实现类
                Class<?> serviceType = invoker.getInterface();//获得本次引用的接口  interface org.apache.dubbo.demo.StubService
                if (ConfigUtils.isDefault(stub)) {
                    // 如果配置了stub = true 的情况，使用接口 + `Stub` 字符串。
                    if (url.hasParameter(STUB_KEY)) {
                        stub = serviceType.getName() + "Stub";
                    } else {// 如果配置了local = true 的情况，使用接口 + `Local` 字符串。
                        stub = serviceType.getName() + "Local";
                    }
                }
                try {
                    // 加载存根类：Stub/Local
                    Class<?> stubClass = ReflectUtils.forName(stub);//org.apache.dubbo.demo.StubServiceStub
                    if (!serviceType.isAssignableFrom(stubClass)) {
                        throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + serviceType.getName());
                    }
                    try {
                        // 检查存根类stubClass里是否包含一个构造函数，该构造函数只有一个参数，且参数为被引用的接口 serviceType
                        Constructor<?> constructor = ReflectUtils.findConstructor(stubClass, serviceType);
                        //根据上面生成的代理对象和引用接口，构造一个存根类的实例
                        proxy = (T) constructor.newInstance(new Object[]{proxy});
                        //
                        URLBuilder urlBuilder = URLBuilder.from(url);
                        if (url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT)) {
                            urlBuilder.addParameter(STUB_EVENT_METHODS_KEY, StringUtils.join(Wrapper.getWrapper(proxy.getClass()).getDeclaredMethodNames(), ","));
                            urlBuilder.addParameter(IS_SERVER_KEY, Boolean.FALSE.toString());
                            try {
                                export(proxy, (Class) invoker.getInterface(), urlBuilder.build());
                            } catch (Exception e) {
                                LOGGER.error("export a stub service error.", e);
                            }
                        }
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException("No such constructor \"public " + stubClass.getSimpleName() + "(" + serviceType.getName() + ")\" in stub implementation class " + stubClass.getName(), e);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to create stub implementation class " + stub + " in consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", cause: " + t.getMessage(), t);
                    // ignore
                }
            }
        }
        return proxy;//创建一个本地存根的对象
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException {
        return proxyFactory.getInvoker(proxy, type, url);
    }

    private <T> Exporter<T> export(T instance, Class<T> type, URL url) {
        return protocol.export(proxyFactory.getInvoker(instance, type, url));
    }

}

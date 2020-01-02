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
 *          value = {StubProxyFactoryWrapper@3152}
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
     *
     * 1、检查该invoker实现的接口不是泛化接口GenericService
     * 2、从invoker的url里提取stub属性配置，没有的获取local属性配置
     * 3、如果配置stub或者local，则表示本地获得一个用于本地调用（stub或者local）的代理对象
     * 4、加载相应的类(本地存根STUB或者本地调用LOCAL)并检查它们是包含持有接口的构造函数
     * 5、根据具体代理对象和存根类的构造方法生成相应的存根或者local对象(这一步实际的实例对象被封装为一个stub对象或local对象了)
     * 6、代理工厂(默认JavassistProxyFactory)根据这个存根/local对象生成一个代理对象
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        //获得被代理的接口服务
        T proxy = proxyFactory.getProxy(invoker);
        if (GenericService.class != invoker.getInterface()) {//如果不是泛化调用
            //获得调用服务的url
            URL url = invoker.getUrl();
            //获得存根或本地调用的属性的配置：stub优先级比local高
            String stub = url.getParameter(STUB_KEY, url.getParameter(LOCAL_KEY));
            if (ConfigUtils.isNotEmpty(stub)) {
                Class<?> serviceType = invoker.getInterface();
                if (ConfigUtils.isDefault(stub)) {
                    // 如果配置了stub = true 的情况，使用接口 + `Stub` 字符串。
                    if (url.hasParameter(STUB_KEY)) {
                        stub = serviceType.getName() + "Stub";
                    } else {// 如果配置了local = true 的情况，使用接口 + `Local` 字符串。
                        stub = serviceType.getName() + "Local";
                    }
                }
                try {
                    // 加载 Stub/Local 类
                    Class<?> stubClass = ReflectUtils.forName(stub);
                    if (!serviceType.isAssignableFrom(stubClass)) {
                        throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + serviceType.getName());
                    }
                    try {
                        // 查找以接口serviceType为参数的构造方法
                        Constructor<?> constructor = ReflectUtils.findConstructor(stubClass, serviceType);
                        //根据构造方法生成一个存根或本地调用的实例
                        proxy = (T) constructor.newInstance(new Object[]{proxy});
                        //export stub service
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
        return proxy;
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException {
        return proxyFactory.getInvoker(proxy, type, url);
    }

    private <T> Exporter<T> export(T instance, Class<T> type, URL url) {
        return protocol.export(proxyFactory.getInvoker(instance, type, url));
    }

}

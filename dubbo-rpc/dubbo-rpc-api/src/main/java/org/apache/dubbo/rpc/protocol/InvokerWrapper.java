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
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * InvokerWrapper
 * 这是一个用于对远程服务提供者包装的一个invoker
 */
public class InvokerWrapper<T> implements Invoker<T> {
    //interface com.springcloud.alibaba.service.HelloAnnotationProviderService -> dubbo://192.168.0.103:20880/com.springcloud.alibaba.service.HelloAnnotationProviderService?anyhost=true&application=springcloud-alibaba-dubbo-provider&bean.name=ServiceBean:com.springcloud.alibaba.service.HelloAnnotationProviderService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService&lazy=false&methods=sayHi&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2Forg.apache.dubbo.registry.RegistryService%3Fapplication%3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26pid%3D15244%26protocol%3Dregistry%26qos.enable%3Dfalse%26refer%3Dapplication%253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526interface%253Dorg.apache.dubbo.monitor.MonitorService%2526pid%253D15244%2526qos.enable%253Dfalse%2526register.ip%253D192.168.0.103%2526release%253D2.7.4.1%2526timestamp%253D1574206816273%26registry%3Dzookeeper%26release%3D2.7.4.1%26timestamp%3D1574206816272&pid=15244&qos.enable=false&register.ip=192.168.0.103&release=2.7.4.1&remote.application=springcloud-alibaba-dubbo-provider&side=consumer&sticky=false&timestamp=1574206704527
    private final Invoker<T> invoker;
    //dubbo://192.168.0.103:20880/com.springcloud.alibaba.service.HelloAnnotationProviderService?anyhost=true&application=springcloud-alibaba-dubbo-provider&bean.name=ServiceBean:com.springcloud.alibaba.service.HelloAnnotationProviderService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService&lazy=false&methods=sayHi&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2Forg.apache.dubbo.registry.RegistryService%3Fapplication%3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26pid%3D15244%26protocol%3Dregistry%26qos.enable%3Dfalse%26refer%3Dapplication%253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526interface%253Dorg.apache.dubbo.monitor.MonitorService%2526pid%253D15244%2526qos.enable%253Dfalse%2526register.ip%253D192.168.0.103%2526release%253D2.7.4.1%2526timestamp%253D1574206816273%26registry%3Dzookeeper%26release%3D2.7.4.1%26timestamp%3D1574206816272&pid=15244&qos.enable=false&register.ip=192.168.0.103&release=2.7.4.1&remote.application=springcloud-alibaba-dubbo-provider&side=consumer&sticky=false&timestamp=1574206704527
    private final URL url;

    public InvokerWrapper(Invoker<T> invoker, URL url) {
        this.invoker = invoker;
        this.url = url;
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        //调用了 ListenerInvokerWrapper.invoke
        return invoker.invoke(invocation);
    }

    @Override
    public void destroy() {
        invoker.destroy();
    }

}

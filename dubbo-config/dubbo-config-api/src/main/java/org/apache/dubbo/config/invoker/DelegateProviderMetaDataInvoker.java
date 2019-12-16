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
package org.apache.dubbo.config.invoker;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 *
 * A Invoker wrapper that wrap the invoker and all the metadata (ServiceConfig)
 */
public class DelegateProviderMetaDataInvoker<T> implements Invoker {
    protected final Invoker<T> invoker;
    private ServiceConfig<?> metadata;

    public DelegateProviderMetaDataInvoker(Invoker<T> invoker, ServiceConfig<?> metadata) {
        this.invoker = invoker;//interface org.apache.dubbo.demo.EventNotifyService -> registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.44.56%3A20880%2Forg.apache.dubbo.demo.EventNotifyService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.EventNotifyService%26bind.ip%3D192.168.44.56%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Dcn%26interface%3Dorg.apache.dubbo.demo.EventNotifyService%26methods%3Dget%26pid%3D34250%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1576073028433%26version%3D1.0.0&pid=34250&qos.port=22222&registry=zookeeper&timestamp=1576073019384
        this.metadata = metadata;//<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="true" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@740abb5" generic="false" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" deprecated="false" group="cn" dynamic="true" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return invoker.getUrl();// registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.0.108%3A20880%2Forg.apache.dubbo.demo.DemoService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.DemoService%26bind.ip%3D192.168.0.108%26bind.port%3D20880%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26methods%3DsayHello%2CsayHelloAsync%26pid%3D5410%26release%3D%26side%3Dprovider%26timestamp%3D1575332340328&pid=5410&qos.port=22222&registry=zookeeper&timestamp=1575332257645
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public void destroy() {
        invoker.destroy();
    }

    public ServiceConfig<?> getMetadata() {
        return metadata;
    }
}

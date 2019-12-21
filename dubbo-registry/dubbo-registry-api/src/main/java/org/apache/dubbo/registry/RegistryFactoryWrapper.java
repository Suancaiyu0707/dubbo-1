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

package org.apache.dubbo.registry;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.Collections;

public class RegistryFactoryWrapper implements RegistryFactory {
    private RegistryFactory registryFactory;

    public RegistryFactoryWrapper(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    /***
     * 根据注册地址url 返回一个注册中心对象 ListenerRegistryWrapper
     * @param url Registry address, is not allowed to be empty
     * @return
     * 1、根据url向注册中心发起连接，并返回一个注册中心对象Registry
     * 2、把Registry和RegistryServiceListener包装成一个ListenerRegistryWrapper对象。
     *      从这里，我们发现，可以通过<dubbo:registry>中配置registry.listeners属性来监听注册中心的注册
     */
    @Override
    public Registry getRegistry(URL url) {
        return new ListenerRegistryWrapper(registryFactory.getRegistry(url),
                Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(RegistryServiceListener.class)
                        .getActivateExtension(url, "registry.listeners")));
    }
}

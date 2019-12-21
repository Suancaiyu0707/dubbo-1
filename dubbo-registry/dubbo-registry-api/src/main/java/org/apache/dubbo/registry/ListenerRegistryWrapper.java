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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;

import java.util.List;

public class ListenerRegistryWrapper implements Registry {
    private static final Logger logger = LoggerFactory.getLogger(ListenerRegistryWrapper.class);

    private final Registry registry;
    private final List<RegistryServiceListener> listeners;


    public ListenerRegistryWrapper(Registry registry, List<RegistryServiceListener> listeners) {
        this.registry = registry;
        this.listeners = listeners;
    }

    @Override
    public URL getUrl() {
        return registry.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return registry.isAvailable();
    }

    @Override
    public void destroy() {
        registry.destroy();
    }

    /***
     * 向当前注册中心注册指定的 服务提供者/服务消费者
     * @param url  Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     *        服务提供者：dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=30672&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576489098974
     *        服务消费者：consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=consumers&check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     */
    @Override
    public void register(URL url) {
        try {
            registry.register(url);
        } finally {
            //如果当前注册中心绑定了监听器
            if (CollectionUtils.isNotEmpty(listeners)) {
                RuntimeException exception = null;
                for (RegistryServiceListener listener : listeners) {
                    if (listener != null) {
                        try {
                            listener.onRegister(url);
                        } catch (RuntimeException t) {
                            logger.error(t.getMessage(), t);
                            exception = t;
                        }
                    }
                }
                if (exception != null) {
                    throw exception;
                }
            }
        }
    }

    @Override
    public void unregister(URL url) {
        try {
            registry.unregister(url);
        } finally {
            if (CollectionUtils.isNotEmpty(listeners)) {
                RuntimeException exception = null;
                for (RegistryServiceListener listener : listeners) {
                    if (listener != null) {
                        try {
                            listener.onUnregister(url);
                        } catch (RuntimeException t) {
                            logger.error(t.getMessage(), t);
                            exception = t;
                        }
                    }
                }
                if (exception != null) {
                    throw exception;
                }
            }
        }
    }

    /***
     *
     * @param url      Subscription condition, not allowed to be empty, e.g. consumer://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     *        consumer：consumer://220.250.64.225/org.apache.dubbo.demo.StubService?category=providers,configurators,routers&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=41141&side=consumer&sticky=false&timestamp=1576810746199
     * @param listener A listener of the change event, not allowed to be empty
     */
    @Override
    public void subscribe(URL url, NotifyListener listener) {
        try {
            registry.subscribe(url, listener);
        } finally {
            if (CollectionUtils.isNotEmpty(listeners)) {
                RuntimeException exception = null;
                for (RegistryServiceListener registryListener : listeners) {
                    if (registryListener != null) {
                        try {
                            registryListener.onSubscribe(url);
                        } catch (RuntimeException t) {
                            logger.error(t.getMessage(), t);
                            exception = t;
                        }
                    }
                }
                if (exception != null) {
                    throw exception;
                }
            }
        }
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        try {
            registry.unsubscribe(url, listener);
        } finally {
            if (CollectionUtils.isNotEmpty(listeners)) {
                RuntimeException exception = null;
                for (RegistryServiceListener registryListener : listeners) {
                    if (registryListener != null) {
                        try {
                            registryListener.onUnsubscribe(url);
                        } catch (RuntimeException t) {
                            logger.error(t.getMessage(), t);
                            exception = t;
                        }
                    }
                }
                if (exception != null) {
                    throw exception;
                }
            }
        }
    }

    @Override
    public List<URL> lookup(URL url) {
        return registry.lookup(url);
    }
}

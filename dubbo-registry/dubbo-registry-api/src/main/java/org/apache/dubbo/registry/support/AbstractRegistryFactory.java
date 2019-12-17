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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.RegistryFactory
 * 实现了 RegistryFactory 接口的 getRegistry(URL url)方法，这是通用的实现，主要完成了加锁以及调用抽象模版方法createRegistry(URL url)
 * 创建具体实现等操作，抽象模版方法由子类继承并实现
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // Log output
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    // 用于在针对注册中心地址进行创建或摧毁注册中心对象的时候进行加锁，避免并发
    private static final ReentrantLock LOCK = new ReentrantLock();

    /**
     * 这个主要是维护了注册中心url跟注册中心对象的映射关系，可以避免重复多次创建注册中心对
     * key ：注册中心的地址
     *      例如：zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService
     * value：注册中心地址对应的对象
     */
    private static final Map<String, Registry> REGISTRIES = new HashMap<>();

    /**
     * Get all registries
     *
     * @return all registries
     * 返回本地缓存的已创建的注册中心对象列表
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(REGISTRIES.values());
    }

    /***
     * 返回指定的注册中心地址映射的注册中心对象
     * @param key 注册中心地址
     *             zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService
     * @return
     */
    public static Registry getRegistry(String key) {
        return REGISTRIES.get(key);
    }

    /**
     * Close all created registries
     * 销毁所有 Registry 对象
     * 1、遍历本地缓存里所有的Registry对象，一个个的进行销毁
     * 2、清流本地缓存
     */
    public static void destroyAll() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // 避免多个线程同时清理，registry.destroy会有并发问题
        LOCK.lock();
        try {
            for (Registry registry : getRegistries()) {
                try {
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            REGISTRIES.clear();
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    /***
     *
     * @param url Registry address, is not allowed to be empty
     *            zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService
     * @return
     * 1、根据注册中心地址构建一个Url，增加了interface=RegistryService，移除export属性
     * 2、检查内存里是否存在对应注册中心对象，有的话直接返回
     * 3、根据注册中心地址维护一个注册中心对象，和注册中心进行连接，并把注册中心对象存放到内存REGISTRIES里
     */
    @Override
    public Registry getRegistry(URL url) {
        //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=29060&qos.port=22222&timestamp=1576488075902
        url = URLBuilder.from(url)
                .setPath(RegistryService.class.getName())
                .addParameter(INTERFACE_KEY, RegistryService.class.getName())
                .removeParameters(EXPORT_KEY, REFER_KEY)
                .build();
        // /zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService
        String key = url.toServiceStringWithoutResolving();
        // Lock the registry access process to ensure a single instance of the registry
        LOCK.lock();
        try {
            //先检查缓存里是否存在
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            //如果注册中心还没创建，则调用抽象方法来创建，这个抽象方法由子类来创建的
            registry = createRegistry(url);//zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    protected abstract Registry createRegistry(URL url);

}

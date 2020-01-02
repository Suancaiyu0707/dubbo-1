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
package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.Destroyable;
import org.apache.dubbo.rpc.service.GenericService;

import com.alibaba.dubbo.rpc.service.EchoService;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.rpc.Constants.INTERFACES;

/**
 * AbstractProxyFactory
 * 代理工厂抽象实现
 * 职责：主要是负责收集代理对象需要实现的接口
 */
public abstract class AbstractProxyFactory implements ProxyFactory {
    /**
     * 增加EchoService是用于回声测试
     * 增加Destroyable是表示可销毁
     */
    private static final Class<?>[] INTERNAL_INTERFACES = new Class<?>[]{
            EchoService.class, Destroyable.class
    };

    @Override
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        return getProxy(invoker, false);
    }

    /***
     * 根据invoker获取代理对象
     * @param invoker 代理对象
     *                 eg：
     * @param generic 是否泛型
     *                eg：
     * @param <T>
     * @return
     * @throws RpcException
     * 1、获得这个代理对象需要实现的接口，如果配置了generic泛型，则还要实现GenericService接口
     * 2、交给具体的代理工厂生成，代理工厂实现有：JavassistProxyFactory和JdkProxyFactory，默认是JavassistProxyFactory
     * 3、添加可回声测试的接口EchoService
     */
    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        Set<Class<?>> interfaces = new HashSet<>();
        //获得 interfaces 参数，一个实现类可能实现多个接口
        String config = invoker.getUrl().getParameter(INTERFACES);
        if (config != null && config.length() > 0) {
            String[] types = COMMA_SPLIT_PATTERN.split(config);
            if (types != null && types.length > 0) {
                for (int i = 0; i < types.length; i++) {
                    // TODO can we load successfully for a different classloader?.
                    interfaces.add(ReflectUtils.forName(types[i]));
                }
            }
        }
        //如果这个invoker对象不是GenericService实现类，但是它配置了泛型。则代理对象需要同时实现接口GenericService
        if (!GenericService.class.isAssignableFrom(invoker.getInterface()) && generic) {
            interfaces.add(com.alibaba.dubbo.rpc.service.GenericService.class);
        }

        interfaces.add(invoker.getInterface());
        interfaces.addAll(Arrays.asList(INTERNAL_INTERFACES));
        //交给具体的代理工厂生成，代理工厂实现有：JavassistProxyFactory和JdkProxyFactory，默认是JavassistProxyFactory
        return getProxy(invoker, interfaces.toArray(new Class<?>[0]));
    }

    public abstract <T> T getProxy(Invoker<T> invoker, Class<?>[] types);

}

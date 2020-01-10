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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * Set the current execution thread class loader to service interface's class loader.
 */

/***
 * 使用方：服务提供者
 * 用于切换不同线程池的类加载器，服务调用完成后会切换回去
 */
@Activate(group = CommonConstants.PROVIDER, order = -30000)
public class ClassLoaderFilter implements Filter {
    /***
     *
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、从当前上下文中获得类加载器
     * 2、把上下文的类加载器设置为接口服务的类加载器：
     *      切换类加载器，以便于跟同一个类加载器加载的对象互相协同交互工作
     * 3、开始进行服务调用
     * 4、服务调用完成后，加当前线程的类加载器设置回去
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        //从当前上下文中获得类加载器
        ClassLoader ocl = Thread.currentThread().getContextClassLoader();
        //在调用invoke之前，把上下文的类加载器设置为接口服务的类加载器
        Thread.currentThread().setContextClassLoader(invoker.getInterface().getClassLoader());
        try {
            //开始进行服务调用
            return invoker.invoke(invocation);
        } finally {
            //服务调用完成后，加当前线程的类加载器设置回去
            Thread.currentThread().setContextClassLoader(ocl);
        }
    }

}

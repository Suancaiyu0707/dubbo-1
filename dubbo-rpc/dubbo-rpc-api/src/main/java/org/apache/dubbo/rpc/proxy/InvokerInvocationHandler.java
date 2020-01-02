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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * InvokerHandler
 */
public class InvokerInvocationHandler implements InvocationHandler {
    private static final Logger logger = LoggerFactory.getLogger(InvokerInvocationHandler.class);
    /***
     * 持有invoker对象
     * javassist生成的代理对象在方法调用的时候，会交给当前InvokerInvocationHandler调用
     * InvokerInvocationHandler又会交给具体的invoker进行直接调用。这个有点像代理了
     */
    private final Invoker<?> invoker;

    public InvokerInvocationHandler(Invoker<?> handler) {
        this.invoker = handler;
    }

    /***
     *
     * @param proxy 代理的对象，一般是Service实现实例
     *              eg：
     * @param method 方法
     *               eg：
     * @param args 方法参数
     *             eg：
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();//获得调用的方法名
        Class<?>[] parameterTypes = method.getParameterTypes();//获得方法参数类型
        //主要是用于执行#wait() #notify() 等方法，进行反射调用。
        if (method.getDeclaringClass() == Object.class) {//获得方法的类，如果是Object的话，则直接调用
            return method.invoke(invoker, args);
        }
        if ("toString".equals(methodName) && parameterTypes.length == 0) {
            return invoker.toString();
        }
        if ("hashCode".equals(methodName) && parameterTypes.length == 0) {
            return invoker.hashCode();
        }
        if ("equals".equals(methodName) && parameterTypes.length == 1) {
            return invoker.equals(args[0]);
        }
        if ("$destroy".equals(methodName) && parameterTypes.length == 0) {
            invoker.destroy();
        }
        //RpcInvocation [methodName=sayHelloAsync, parameterTypes=[class java.lang.String], arguments=[world], attachments={}]
        RpcInvocation rpcInvocation = new RpcInvocation(method, invoker.getInterface().getName(), args);
        rpcInvocation.setTargetServiceUniqueName(invoker.getUrl().getServiceKey());
        //RpcInvocation [methodName=sayHelloAsync, parameterTypes=[class java.lang.String], arguments=[world], attachments={}]
        //开始进行 RPC 调用远程调用，并回放调用结果
        return invoker.invoke(rpcInvocation)
                .recreate();//回放调用结果
    }
}

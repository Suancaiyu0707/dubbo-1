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
package org.apache.dubbo.rpc.proxy.jdk;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * JdkRpcProxyFactory
 * JdkProxyFactory在通过字节码技术生成代理对象后， 在调用doInvoke的时候，直接走反射了(这就是它性能不如JavassistProxyFactory原因，)，所以性能就没那么好了
 */
public class JdkProxyFactory extends AbstractProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), interfaces,
                new InvokerInvocationHandler(invoker));
    }

    /***
     * 我们可以看到，如果是jdk的话，它这边的doInvoke调用时候，是直接通过反射来调用
     * @param proxy 代理对象，实际上是通过上面getProxy生成
     * @param type
     * @param url
     * @param <T>
     * @return
     * 1、创建一个AbstractProxyInvoker对象，该对象实现了doInvoke方法：
     *      该方法在调用doInvoke的时候，直接走反射了(这就是它性能不如JavassistProxyFactory原因，)，所以性能就没那么好了。proxy实例会通过InvokerInvocationHandler交给具体的实现对象调用方法。

     */
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                Method method = proxy.getClass().getMethod(methodName, parameterTypes);
                //我们可以看到它是通过代理对象反射调用
                return method.invoke(proxy, arguments);
            }
        };
    }

}

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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * 基本知识：
 *      Javassist在通过字节码技术生成代理对象后，调用的时候是通过直接调用，相对于反射快不少
 *      而cglib和jdk在代理调用时，走的是反射调用,所以就比较慢了
 *
 * JavassistProxyFactory 在调用doInvoke的时候，会通过wrapper实例交由对应的proxy实例调用，而不是走反射了(可看Wrapper1生成的实现类)，所以性能就上去了
 */
public class JavassistProxyFactory extends AbstractProxyFactory {
    /***
     * 获得 Proxy 对象，并绑定这个处理Proxy 对象的方法的调用交由相应的InvokerInvocationHandler处理
     * @param invoker
     * @param interfaces 用于生成Proxy 对象
     * @param <T>
     * @return
     * 1、根据interfaces接口映射相应的Proxy对象实例，如果没有的话则会新建一个，并缓存到内存里。
     * 2、设置这个Proxy实例的handler为对应的new InvokerInvocationHandler(invoker)
     *      这样后面调用Proxy方法时，会交给InvokerInvocationHandler处理了。然后InvokerInvocationHandler会直接调用具体实例的方法
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy
                .getProxy(interfaces) //获得 Proxy 对象。
                .newInstance(new InvokerInvocationHandler(invoker));//得 proxy 对象。其中传入的参数是 InvokerInvocationHandler 类，通过这样的方式，让 proxy 和真正的逻辑代码解耦。
    }

    /***
     *
     * @param proxy 被代理的对象(也就是代理对象指向的具体实例)
     * @param type 服务接口类型(用于生成代理对象)
     * @param url 指定代理对象对应的注册服务信息
     * @param <T>
     * @return
     * 1、根据代理对象proxy创建一个Wrapper对象
     * 2、同时创建一个AbstractProxyInvoker对象，该对象实现了doInvoke方法：
     *      该方法保证了，在调用doInvoke的时候，会通过wrapper实例交由对应的proxy实例调用，而不是走反射了(可看Wrapper1生成的实现类)，所以性能就上去了。proxy实例会通过InvokerInvocationHandler交给具体的实现对象调用方法。
     */
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        //获得Wrapper对象
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        //创建 AbstractProxyInvoker 对象，实现doInvoke方法。从而调用 Service 的方法
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                //，调用 Wrapper#invokeMethod(...) 方法，从而调用 Service 的方法。这是它和JDK的区别，不走反射了
                return wrapper.invokeMethod(
                        proxy, //这个proxy内部是会交给 InvokerInvocationHandler 处理。然后InvokerInvocationHandler会直接调用具体实例的方法
                        methodName,
                        parameterTypes,
                        arguments);
            }
        };
    }

}

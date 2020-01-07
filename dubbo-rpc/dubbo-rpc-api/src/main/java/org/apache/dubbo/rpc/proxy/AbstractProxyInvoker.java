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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncContextImpl;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * InvokerWrapper
 * 代理 Invoker 对象的抽象类
 */
public abstract class AbstractProxyInvoker<T> implements Invoker<T> {
    Logger logger = LoggerFactory.getLogger(AbstractProxyInvoker.class);
    /**
     * 被代理的对象，一般是Service实现实例,不是代理对象哈
     *  eg: StubServiceImpl@4650
     */
    private final T proxy;
    /***
     * 接口类型
     *  eg:
     */
    private final Class<T> type;
    /**
     * URL 对象，一般是暴露服务的 URL 对象
     *  eg:
     */
    private final URL url;

    /***
     * 创建一个 AbstractProxyInvoker实例
     * @param proxy 代理的对象，一般是Service实现实例
     *              eg：
     * @param type 接口类型
     *             eg：
     * @param url URL 对象，一般是暴露服务的 URL 对象
     *            eg:
     */
    public AbstractProxyInvoker(T proxy, Class<T> type, URL url) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("interface == null");
        }
        if (!type.isInstance(proxy)) {
            throw new IllegalArgumentException(proxy.getClass().getName() + " not implement interface " + type);
        }
        this.proxy = proxy;
        this.type = type;
        this.url = url;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
    }

    /***
     * 开始处理消费端请求过来的RPC远程调用
     * @param invocation 请求信息 RpcInvocation [methodName=sayHello, parameterTypes=[class java.lang.String], arguments=[zjn], attachments={input=196, path=org.apache.dubbo.demo.StubService, dubbo=2.0.2, interface=org.apache.dubbo.demo.StubService, version=0.0.0}]
     * @return
     * @throws RpcException
     * 1、通过doInvoke调用实际对象的方法，发起请求调用，并返回结果
     * 2、将结果集包装成一个CompletableFuture对象
     * 3、返回一个异步结果对象
     */
    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        try {
            //通过doInvoke调用实际对象的方法，发起请求调用（通过JavassistProxyFactory.AbstractProxyInvoker.doInvoke），并返回结果
            Object value = doInvoke(proxy, invocation.getMethodName(), invocation.getParameterTypes(), invocation.getArguments());
            //将结果集包装成一个CompletableFuture对象
            CompletableFuture<Object> future = wrapWithFuture(value, invocation);
            //CompletableFuture在接收到处理结果后会封装成一个 AppResponse 方法
            CompletableFuture<AppResponse> appResponseFuture = future.handle((obj, t) -> {
                AppResponse result = new AppResponse();
                if (t != null) {
                    if (t instanceof CompletionException) {
                        result.setException(t.getCause());
                    } else {
                        result.setException(t);
                    }
                } else {
                    result.setValue(obj);
                }
                return result;
            });
            //返回一个异步结果对象
            return new AsyncRpcResult(appResponseFuture, invocation);
        } catch (InvocationTargetException e) {
            if (RpcContext.getContext().isAsyncStarted() && !RpcContext.getContext().stopAsync()) {
                logger.error("Provider async started, but got an exception from the original method, cannot write the exception back to consumer because an async result may have returned the new thread.", e);
            }
            return AsyncRpcResult.newDefaultAsyncResult(null, e.getTargetException(), invocation);
        } catch (Throwable e) {
            throw new RpcException("Failed to invoke remote proxy method " + invocation.getMethodName() + " to " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /***
     * 将结果集和请求信息包装成一个 CompletableFuture
     * @param value 结果集，比如 "i am StubService"
     * @param invocation RpcInvocation [methodName=sayHello, parameterTypes=[class java.lang.String], arguments=[zjn], attachments={input=196, path=org.apache.dubbo.demo.StubService, dubbo=2.0.2, interface=org.apache.dubbo.demo.StubService, version=0.0.0}]
     * @return
     */
    private CompletableFuture<Object> wrapWithFuture (Object value, Invocation invocation) {
        if (RpcContext.getContext().isAsyncStarted()) {//判断是否异步调用
            return ((AsyncContextImpl)(RpcContext.getContext().getAsyncContext())).getInternalFuture();
        } else if (value instanceof CompletableFuture) {//判断是否 CompletableFuture结果集类型
            return (CompletableFuture<Object>) value; //将结果集强转成一个CompletableFuture对象
        }
        return CompletableFuture.completedFuture(value);//将结果集包装成一个CompletableFuture对象
    }

    /***
     *
     * @param proxy 代理的对象(接口的实现实例)
     * @param methodName 方法名
     * @param parameterTypes 方法参数类型数组
     * @param arguments 方法参数数组
     * @return
     * @throws Throwable
     */
    protected abstract Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Throwable;

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }


}

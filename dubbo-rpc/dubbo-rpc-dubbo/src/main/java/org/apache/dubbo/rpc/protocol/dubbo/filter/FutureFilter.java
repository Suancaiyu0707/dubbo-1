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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE;

/**
 * EventFilter
 * 使用方：服务消费者
 *      在发起invoker或得到返回值、出现异常时触发回调事件
 * 获得<dubbo:method>里配置的异步回调方法 oninvoke/onreturn/onthrow：
 *          <dubbo:method name="get" async="true" onreturn = "eventNotifyCallback.onreturn" onthrow="eventNotifyCallback.onthrow" />
 *
 * oninvoke：服务消费者调用服务提供者之前，执行前置方法，如果配置了oninvoke属性的话，先执行oninvke方法，再调用远程的方法
 * onreturn：设置服务消费者调用服务提供者之后，如果请求返回正常，执行后置方法onreturn
 * onthrow：设置服务消费者调用服务提供者之后，如果请求返回异常，执行后置方法onthrow
 *
 */
@Activate(group = CommonConstants.CONSUMER)
public class FutureFilter implements Filter, Filter.Listener {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    /***
     * 在调用之前，判断是否配置了 oninvoke 方法
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、服务消费者调用服务提供者之前，执行前置方法：如果配置了oninvoke属性的话，先执行oninvke方法
     * 2、开始远程服务调用
     */
    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        fireInvokeCallback(invoker, invocation);
        // need to configure if there's return value before the invocation in order to help invoker to judge if it's
        // necessary to return future.
        return invoker.invoke(invocation);
    }

    /***
     * 根据执行结果的异常判断是回调onThrow方法还是回调onReturn方法
     * @param result
     * @param invoker
     * @param invocation
     * 1、判断返回结果是否存在异常
     * 2、如果存在异常信息，则回调onThrow的方法
     * 3、如果不存在异常信息，则回调onReturn的方法
     */
    @Override
    public void onMessage(Result result, Invoker<?> invoker, Invocation invocation) {
        if (result.hasException()) {
            //onthrow 配置项，设置服务消费者调用服务提供者之后，如果请求返回异常，执行后置方法onthrow
            fireThrowCallback(invoker, invocation, result.getException());
        } else {
            //onreturn 设置服务消费者调用服务提供者之后，如果请求返回正常，执行后置方法onreturn
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

    }

    /***
     *
     * 服务消费者调用服务提供者之前，执行前置方法oninvoke
     * 获得<dubbo:method>里配置的异步回调方法 oninvoke：
     *          <dubbo:method name="get" async="true" onreturn = "eventNotifyCallback.onreturn" onthrow="eventNotifyCallback.onthrow" />
     * @param invoker
     * @param invocation
     * 1、获得获得前置方法oninvoke和对象
     * 2、调用前置方法
     */
    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        //获得获得前置方法oninvoke和对象
        final ConsumerModel.AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }
        //获得<dubbo:method>里配置的onthrow的值
        final Method onInvokeMethod = asyncMethodInfo.getOninvokeMethod();
        final Object onInvokeInst = asyncMethodInfo.getOninvokeInstance();

        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a oninvoke callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }

        Object[] params = invocation.getArguments();
        try {
            //调用前置方法
            onInvokeMethod.invoke(onInvokeInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }
    /***
     * 设置服务消费者调用服务提供者之后，如果请求返回正常，执行后置方法onreturn
     *
     * 获得<dubbo:method>里配置的异步回调方法 onReturn：
     *          <dubbo:method name="get" async="true" onreturn = "eventNotifyCallback.onreturn" onthrow="eventNotifyCallback.onthrow" />
     * @param invoker
     * @param invocation
     */
    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {
        final ConsumerModel.AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }

        final Method onReturnMethod = asyncMethodInfo.getOnreturnMethod();
        final Object onReturnInst = asyncMethodInfo.getOnreturnInstance();

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        Object[] args = invocation.getArguments();
        Object[] params;
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();
        if (rParaTypes.length > 1) {
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;
            } else {
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }
        } else {
            params = new Object[]{result};
        }
        try {
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }
    /***
     * 设置服务消费者调用服务提供者之后，如果请求返回异常，执行后置方法onthrow
     *
     * 获得<dubbo:method>里配置的异步回调方法 onthrow：
     *          <dubbo:method name="get" async="true" onreturn = "eventNotifyCallback.onreturn" onthrow="eventNotifyCallback.onthrow" />
     * @param invoker
     * @param invocation
     */
    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {
        final ConsumerModel.AsyncMethodInfo asyncMethodInfo = getAsyncMethodInfo(invoker, invocation);
        if (asyncMethodInfo == null) {
            return;
        }
        //获得<dubbo:method>里配置的onthrow的值
        final Method onthrowMethod = asyncMethodInfo.getOnthrowMethod();
        final Object onthrowInst = asyncMethodInfo.getOnthrowInstance();

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {
                Object[] args = invocation.getArguments();
                Object[] params;

                if (rParaTypes.length > 1) {
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                    } else {
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                } else {
                    params = new Object[]{exception};
                }
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }
    //

    /***
     * 获得异步方法信息，只在消费端配置有效
     * @param invoker
     * @param invocation
     * @return
     * 1、判断是消费端消费模式
     * 2、判断方法里是否配置了回调onreturn = "eventNotifyCallback.onreturn" onthrow="eventNotifyCallback.onthrow"
     */
    private ConsumerModel.AsyncMethodInfo getAsyncMethodInfo(Invoker<?> invoker, Invocation invocation) {
        final ConsumerModel consumerModel = ApplicationModel.getConsumerModel(invoker.getUrl().getServiceKey());
        if (consumerModel == null) {//如果不是消费端消费服务，直接返回空
            return null;
        }
        //获得本次调用的方法
        String methodName = invocation.getMethodName();
        if (methodName.equals($INVOKE)) {
            methodName = (String) invocation.getArguments()[0];
        }
        //获得方法的回调信息：
        // onreturn = "eventNotifyCallback.onreturn" onthrow="eventNotifyCallback.onthrow"
        final ConsumerModel.AsyncMethodInfo asyncMethodInfo = consumerModel.getMethodConfig(methodName);
        if (asyncMethodInfo == null) {
            return null;
        }

        return asyncMethodInfo;
    }

}

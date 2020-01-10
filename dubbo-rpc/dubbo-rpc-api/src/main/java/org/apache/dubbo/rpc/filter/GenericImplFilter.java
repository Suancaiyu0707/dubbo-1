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

import org.apache.dubbo.common.beanutil.JavaBeanAccessor;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.common.beanutil.JavaBeanSerializeUtil;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.support.ProtocolUtils;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE;
import static org.apache.dubbo.common.constants.CommonConstants.$INVOKE_ASYNC;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_INVOCATION_PREFIX;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

/**
 * GenericImplInvokerFilter
 * 使用方：服务消费者
 * 服务消费者的泛化调用过滤器，用于实现泛化调用
 */
@Activate(group = CommonConstants.CONSUMER, value = GENERIC_KEY, order = 20000)
public class GenericImplFilter implements Filter, Filter.Listener {

    private static final Logger logger = LoggerFactory.getLogger(GenericImplFilter.class);

    private static final Class<?>[] GENERIC_PARAMETER_TYPES = new Class<?>[]{String.class, String[].class, Object[].class};

    private static final String GENERIC_PARAMETER_DESC = "Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;";

    private static final String GENERIC_IMPL_MARKER = DUBBO_INVOCATION_PREFIX + "GENERIC_IMPL";

    /***
     *
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、获得generic属性值
     * 2、获得请求的方法名、参数类型、参数值
     * 3、获得序列化方式(用于将请求信息)
     * 4、将请求转换成泛化调用
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String generic = invoker.getUrl().getParameter(GENERIC_KEY);
        // 如果是调用的是一个GenericService的实现类
        if (isCallingGenericImpl(generic, invocation)) {
            RpcInvocation invocation2 = new RpcInvocation(invocation);

            /**
             * Mark this invocation as a generic impl call, this value will be removed automatically before passing on the wire.
             * See {@link RpcUtils#sieveUnnecessaryAttachments(Invocation)}
             */
            invocation2.setAttachment(GENERIC_IMPL_MARKER, true);
            //获得调用的方法名
            String methodName = invocation2.getMethodName();
            //获得请求参数类型
            Class<?>[] parameterTypes = invocation2.getParameterTypes();
            //获得请求值
            Object[] arguments = invocation2.getArguments();

            String[] types = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                types[i] = ReflectUtils.getName(parameterTypes[i]);
            }
            //
            Object[] args;
            if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                args = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    args[i] = JavaBeanSerializeUtil.serialize(arguments[i], JavaBeanAccessor.METHOD);
                }
            } else {
                args = PojoUtils.generalize(arguments);
            }
            //设置请求方法 $invokeAsync或$invoke
            if (RpcUtils.isReturnTypeFuture(invocation)) {
                invocation2.setMethodName($INVOKE_ASYNC);
            } else {
                invocation2.setMethodName($INVOKE);
            }
            //设置请求方法的参数类型
            invocation2.setParameterTypes(GENERIC_PARAMETER_TYPES);
            invocation2.setParameterTypesDesc(GENERIC_PARAMETER_DESC);
            //设置请求参数：方法名、参数类型数组、参数值数组
            invocation2.setArguments(new Object[]{methodName, types, args});
            return invoker.invoke(invocation2);
        }
        //如果是直接调用 GenericService.$invoke
        else if (isMakingGenericCall(generic, invocation)) {

            Object[] args = (Object[]) invocation.getArguments()[2];
            if (ProtocolUtils.isJavaGenericSerialization(generic)) {

                for (Object arg : args) {
                    if (!(byte[].class == arg.getClass())) {
                        error(generic, byte[].class.getName(), arg.getClass().getName());
                    }
                }
            } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                for (Object arg : args) {
                    if (!(arg instanceof JavaBeanDescriptor)) {
                        error(generic, JavaBeanDescriptor.class.getName(), arg.getClass().getName());
                    }
                }
            }
            // 通过隐式参数，传递 `generic` 配置项
            invocation.setAttachment(
                    GENERIC_KEY, invoker.getUrl().getParameter(GENERIC_KEY));
        }
        return invoker.invoke(invocation);
    }

    private void error(String generic, String expected, String actual) throws RpcException {
        throw new RpcException("Generic serialization [" + generic + "] only support message type " + expected + " and your message type is " + actual);
    }

    @Override
    public void onMessage(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        String generic = invoker.getUrl().getParameter(GENERIC_KEY);
        String methodName = invocation.getMethodName();
        Class<?>[] parameterTypes = invocation.getParameterTypes();
        if (invocation.getAttachment(GENERIC_IMPL_MARKER) != null) {
            if (!appResponse.hasException()) {
                Object value = appResponse.getValue();
                try {
                    Method method = invoker.getInterface().getMethod(methodName, parameterTypes);
                    if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                        if (value == null) {
                            appResponse.setValue(value);
                        } else if (value instanceof JavaBeanDescriptor) {
                            appResponse.setValue(JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) value));
                        } else {
                            throw new RpcException("The type of result value is " + value.getClass().getName() + " other than " + JavaBeanDescriptor.class.getName() + ", and the result is " + value);
                        }
                    } else {
                        Type[] types = ReflectUtils.getReturnTypes(method);
                        appResponse.setValue(PojoUtils.realize(value, (Class<?>) types[0], types[1]));
                    }
                } catch (NoSuchMethodException e) {
                    throw new RpcException(e.getMessage(), e);
                }
            } else if (appResponse.getException() instanceof GenericException) {
                GenericException exception = (GenericException) appResponse.getException();
                try {
                    String className = exception.getExceptionClass();
                    Class<?> clazz = ReflectUtils.forName(className);
                    Throwable targetException = null;
                    Throwable lastException = null;
                    try {
                        targetException = (Throwable) clazz.newInstance();
                    } catch (Throwable e) {
                        lastException = e;
                        for (Constructor<?> constructor : clazz.getConstructors()) {
                            try {
                                targetException = (Throwable) constructor.newInstance(new Object[constructor.getParameterTypes().length]);
                                break;
                            } catch (Throwable e1) {
                                lastException = e1;
                            }
                        }
                    }
                    if (targetException != null) {
                        try {
                            Field field = Throwable.class.getDeclaredField("detailMessage");
                            if (!field.isAccessible()) {
                                field.setAccessible(true);
                            }
                            field.set(targetException, exception.getExceptionMessage());
                        } catch (Throwable e) {
                            logger.warn(e.getMessage(), e);
                        }
                        appResponse.setException(targetException);
                    } else if (lastException != null) {
                        throw lastException;
                    }
                } catch (Throwable e) {
                    throw new RpcException("Can not deserialize exception " + exception.getExceptionClass() + ", message: " + exception.getExceptionMessage(), e);
                }
            }
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

    }

    /***
     * 判断是否是泛化调用（就是不是直接通过调用GenericService的方法，而是通过普通方法调用，加generic标记）
     * @param generic
     * @param invocation
     * @return
     * 1、方法名不是$invoke且不是$invokeAsync
     * 2、泛化调用
     * 3、
     */
    private boolean isCallingGenericImpl(String generic, Invocation invocation) {
        return ProtocolUtils.isGeneric(generic)
                && (!$INVOKE.equals(invocation.getMethodName()) && !$INVOKE_ASYNC.equals(invocation.getMethodName()))
                && invocation instanceof RpcInvocation;
    }

    /***
     * 标记泛化调用（就是直接调用GenericService的方法）
     * @param generic
     * @param invocation
     * @return
     * 1、方法名是$invoke或$invokeAsync
     * 2、参数必须是3个
     */
    private boolean isMakingGenericCall(String generic, Invocation invocation) {
        return (invocation.getMethodName().equals($INVOKE) || invocation.getMethodName().equals($INVOKE_ASYNC))
                && invocation.getArguments() != null
                && invocation.getArguments().length == 3
                && ProtocolUtils.isGeneric(generic);
    }

}

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
package org.apache.dubbo.rpc.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

import com.alibaba.fastjson.JSON;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.rpc.Constants.FAIL_PREFIX;
import static org.apache.dubbo.rpc.Constants.FORCE_PREFIX;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.Constants.RETURN_KEY;
import static org.apache.dubbo.rpc.Constants.RETURN_PREFIX;
import static org.apache.dubbo.rpc.Constants.THROW_PREFIX;

final public class MockInvoker<T> implements Invoker<T> {
    private final static ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private final static Map<String, Invoker<?>> MOCK_MAP = new ConcurrentHashMap<String, Invoker<?>>();
    private final static Map<String, Throwable> THROWABLE_MAP = new ConcurrentHashMap<String, Throwable>();

    private final URL url;
    private final Class<T> type;

    public MockInvoker(URL url, Class<T> type) {
        this.url = url;
        this.type = type;
    }

    /***
     * 格式化mock的值
     * @param mock
     * @return
     * @throws Exception
     */
    public static Object parseMockValue(String mock) throws Exception {
        return parseMockValue(mock, null);
    }

    /***
     * 根据方法的返回类型进行格式化mock的值
     * @param mock mock的值
     * @param returnTypes 返回类型
     * @return
     * @throws Exception
     */
    public static Object parseMockValue(String mock, Type[] returnTypes) throws Exception {
        Object value = null;
        if ("empty".equals(mock)) {
            value = ReflectUtils.getEmptyObject(returnTypes != null && returnTypes.length > 0 ? (Class<?>) returnTypes[0] : null);
        } else if ("null".equals(mock)) {
            value = null;
        } else if ("true".equals(mock)) {
            value = true;
        } else if ("false".equals(mock)) {
            value = false;
        } else if (mock.length() >= 2 && (mock.startsWith("\"") && mock.endsWith("\"")
                || mock.startsWith("\'") && mock.endsWith("\'"))) {
            value = mock.subSequence(1, mock.length() - 1);
        } else if (returnTypes != null && returnTypes.length > 0 && returnTypes[0] == String.class) {
            value = mock;
        } else if (StringUtils.isNumeric(mock, false)) {
            value = JSON.parse(mock);
        } else if (mock.startsWith("{")) {
            value = JSON.parseObject(mock, Map.class);
        } else if (mock.startsWith("[")) {
            value = JSON.parseObject(mock, List.class);
        } else {
            value = mock;
        }
        if (ArrayUtils.isNotEmpty(returnTypes)) {
            value = PojoUtils.realize(value, (Class<?>) returnTypes[0], returnTypes.length > 1 ? returnTypes[1] : null);
        }
        return value;
    }

    /***
     *
     * @param invocation
     * @return
     * @throws RpcException
     * 1、通过url里获取mock的值，如果为空，则抛异常
     * 2、解码mock值
     * 3、校验mock值
     *      a、如果mock的值以 return 开头，则将return后面的值直接转换成相应的返回类型对象，并封装一个返回结果对象AsyncRpcResult进行返回
     *      b、如果mock的值以 throw 开头，则将throw后面的值直接转换成相应的Throwable对象并抛出这个异常对象
     *      c、其它情况下，则直接调用mock对象相应的方法
     */
    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        //检查 invocation 是一个 RpcInvocation
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(this);
        }
        String mock = null;
        //判断url里是否有当前请求方法名
        if (getUrl().hasMethodParameter(invocation.getMethodName())) {
            //从url里获取methodName.mock
            mock = getUrl().getParameter(invocation.getMethodName() + "." + MOCK_KEY);
        }
        //如果没有mock值，则尝试从url里获取 mock属性值
        if (StringUtils.isBlank(mock)) {
            mock = getUrl().getParameter(MOCK_KEY);
        }
        //如果未设置mock，则抛出异常
        if (StringUtils.isBlank(mock)) {
            throw new RpcException(new IllegalAccessException("mock can not be null. url :" + url));
        }
        //解码mock值
        mock = normalizeMock(URL.decode(mock));//org.apache.dubbo.demo.consumer.MockServiceMock
        if (mock.startsWith(RETURN_PREFIX)) {//如果是以return开头
            mock = mock.substring(RETURN_PREFIX.length()).trim();//从return里提取出返回值
            try {
                Type[] returnTypes = RpcUtils.getReturnTypes(invocation);//获得方法的返回类型
                Object value = parseMockValue(mock, returnTypes);//根据返回类型转换返回值
                return AsyncRpcResult.newDefaultAsyncResult(value, invocation);//构建一个默认的返回结果
            } catch (Exception ew) {
                throw new RpcException("mock return invoke error. method :" + invocation.getMethodName()
                        + ", mock:" + mock + ", url: " + url, ew);
            }
        } else if (mock.startsWith(THROW_PREFIX)) {//如果是以throw开头
            mock = mock.substring(THROW_PREFIX.length()).trim();//从throw里提取出返回值
            if (StringUtils.isBlank(mock)) {
                throw new RpcException("mocked exception for service degradation.");
            } else { // user customized class
                Throwable t = getThrowable(mock);//将返回值转换成一个throwable
                throw new RpcException(RpcException.BIZ_EXCEPTION, t);
            }
        } else { //直接调用mock对象相应的方法
            try {
                Invoker<T> invoker = getInvoker(mock);//调用mock方法
                return invoker.invoke(invocation);
            } catch (Throwable t) {
                throw new RpcException("Failed to create mock implementation class " + mock, t);
            }
        }
    }

    public static Throwable getThrowable(String throwstr) {
        Throwable throwable = THROWABLE_MAP.get(throwstr);
        if (throwable != null) {
            return throwable;
        }

        try {
            Throwable t;
            Class<?> bizException = ReflectUtils.forName(throwstr);
            Constructor<?> constructor;
            constructor = ReflectUtils.findConstructor(bizException, String.class);
            t = (Throwable) constructor.newInstance(new Object[]{"mocked exception for service degradation."});
            if (THROWABLE_MAP.size() < 1000) {
                THROWABLE_MAP.put(throwstr, t);
            }
            return t;
        } catch (Exception e) {
            throw new RpcException("mock throw error :" + throwstr + " argument error.", e);
        }
    }

    /***
     * 根据mockService名称从本地内存MOCK_MAP获得缓存的对应的invoker对象，有的话直接返回，没有的话创建一个
     * @param mockService
     * @return
     */
    @SuppressWarnings("unchecked")
    private Invoker<T> getInvoker(String mockService) {
        Invoker<T> invoker = (Invoker<T>) MOCK_MAP.get(mockService);
        if (invoker != null) {
            return invoker;
        }

        Class<T> serviceType = (Class<T>) ReflectUtils.forName(url.getServiceInterface());
        T mockObject = (T) getMockObject(mockService, serviceType);
        invoker = PROXY_FACTORY.getInvoker(mockObject, serviceType, url);
        if (MOCK_MAP.size() < 10000) {
            MOCK_MAP.put(mockService, invoker);
        }
        return invoker;
    }

    /***
     * 根据mockService名称和接口类型创建一个对应的Mock实例
     * @param mockService
     * @param serviceType
     * @return
     */
    @SuppressWarnings("unchecked")
    public static Object getMockObject(String mockService, Class serviceType) {
        if (ConfigUtils.isDefault(mockService)) {
            mockService = serviceType.getName() + "Mock";
        }

        Class<?> mockClass = ReflectUtils.forName(mockService);
        if (!serviceType.isAssignableFrom(mockClass)) {
            throw new IllegalStateException("The mock class " + mockClass.getName() +
                    " not implement interface " + serviceType.getName());
        }

        try {
            return mockClass.newInstance();
        } catch (InstantiationException e) {
            throw new IllegalStateException("No default constructor from mock class " + mockClass.getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Normalize mock string:
     *
     * <ol>
     * <li>return => return null</li>
     * <li>fail => default</li>
     * <li>force => default</li>
     * <li>fail:throw/return foo => throw/return foo</li>
     * <li>force:throw/return foo => throw/return foo</li>
     * </ol>
     *
     * @param mock mock string
     * @return normalized mock string
     * 对mock名称进行规范化
     */
    public static String normalizeMock(String mock) {
        if (mock == null) {
            return mock;
        }

        mock = mock.trim();

        if (mock.length() == 0) {
            return mock;
        }
        //如果 mock值是return，则更正为return null
        if (RETURN_KEY.equalsIgnoreCase(mock)) {
            return RETURN_PREFIX + "null";
        }
        //如果 mock值是default/true/fail/force,则更正为default
        if (ConfigUtils.isDefault(mock) || "fail".equalsIgnoreCase(mock) || "force".equalsIgnoreCase(mock)) {
            return "default";
        }
        //如果 mock值是fail:开头，则截断掉fail:
        if (mock.startsWith(FAIL_PREFIX)) {
            mock = mock.substring(FAIL_PREFIX.length()).trim();
        }
        //如果 mock值是 force:开头，则截断掉 force:
        if (mock.startsWith(FORCE_PREFIX)) {
            mock = mock.substring(FORCE_PREFIX.length()).trim();
        }
        //如果 mock值是 return 或throw 开头，则替换引号
        if (mock.startsWith(RETURN_PREFIX) || mock.startsWith(THROW_PREFIX)) {
            mock = mock.replace('`', '"');
        }

        return mock;
    }

    @Override
    public URL getUrl() {
        return this.url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
        //do nothing
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }
}

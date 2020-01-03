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
package org.apache.dubbo.rpc.cluster.support.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.util.List;

import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.INVOCATION_NEED_MOCK;

/**
 *
 * @param <T>
 */
public class MockClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(MockClusterInvoker.class);
    /***
     * 可理解为一个引用服务在注册中心目录的对象
     * eg：directory
     * serviceKey = "org.apache.dubbo.registry.RegistryService"
     * serviceType = {Class@2297} "interface org.apache.dubbo.demo.DemoService"
     * queryMap = {HashMap@3237}  size = 11
     * directoryUrl = {URL@3238} "zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello,sayHelloAsync&pid=38171&register.ip=220.250.64.225&side=consumer&sticky=false&timestamp=1575883395444"
     * multiGroup = false
     * protocol = {Protocol$Adaptive@3156}
     * registry = {ListenerRegistryWrapper@3239}
     * forbidden = true
     * overrideDirectoryUrl = {URL@3238} "zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello,sayHelloAsync&pid=38171&register.ip=220.250.64.225&side=consumer&sticky=false&timestamp=1575883395444"
     * registeredConsumerUrl = {URL@3240} "consumer://220.250.64.225/org.apache.dubbo.demo.DemoService?category=consumers&check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello,sayHelloAsync&pid=38171&side=consumer&sticky=false&timestamp=1575883395444"
     * configurators = {ArrayList@3301}  size = 0
     * urlInvokerMap = null
     * invokers = null
     * cachedInvokerUrls = {HashSet@3276}  size = 0
     * serviceConfigurationListener = {RegistryDirectory$ReferenceConfigurationListener@3268}
     * url = {URL@3198} "zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=38171&qos.port=33333&refer=check%3Dfalse%26dubbo%3D2.0.2%26init%3Dfalse%26interface%3Dorg.apache.dubbo.demo.DemoService%26lazy%3Dfalse%26methods%3DsayHello%2CsayHelloAsync%26pid%3D38171%26register.ip%3D220.250.64.225%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1575883395444&timestamp=1575883395479"
     * destroyed = false
     * consumerUrl = {URL@3235} "consumer://220.250.64.225/org.apache.dubbo.demo.DemoService?category=providers,configurators,routers&check=false&dubbo=2.0.2&init=false&interface=org.apache.dubbo.demo.DemoService&lazy=false&methods=sayHello,sayHelloAsync&pid=38171&side=consumer&sticky=false&timestamp=1575883395444"
     * routerChain = {RouterChain@3241}
     */
    private final Directory<T> directory;
    /***
     * 本地伪装对象内部包装的具体的引用服务的对象(如果是本地存根调用的话，则是一个本地存根的对象)
     */
    private final Invoker<T> invoker;

    public MockClusterInvoker(Directory<T> directory, Invoker<T> invoker) {
        this.directory = directory;
        this.invoker = invoker;
    }

    @Override
    public URL getUrl() {
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return directory.isAvailable();
    }

    @Override
    public void destroy() {
        this.invoker.destroy();
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {//RpcInvocation [methodName=sayHelloAsync, parameterTypes=[class java.lang.String], arguments=[world], attachments={}]
        Result result = null;

        /**
         *
         * 1、根据注册地址中，我们可以获得服务引用的url。
         * 2、在从服务引用的url中我们根据方法名去获得mock属性值，用于判断是否是本地伪装调用(所以我们发现本地伪装也是在consumer端配置)
         */
        String value = directory //可理解为一个引用服务在注册中心目录的对象
                                .getUrl() //获得具体的服务引用的url地址
                                .getMethodParameter(//根据方法名，从服务引用的url地址中获取对应方法的引用配置
                                invocation.getMethodName(), //方法名，eg：sayHello
                                MOCK_KEY, //mock
                                Boolean.FALSE.toString()//默认是false
                                ).trim();//获取mock属性，没有的话默认为false
        /**
         * 根据方法的具体的mock配置情况，我们决定进行调用引用的服务
         *      如果mock为空，或者mock=false,则不走本地伪装，直接调用invoker代理对象
         *      如果mock值以force开头，则强制调用mock实例，实现本地伪装
         *      如果mock不为空，也不是以force开头，则正常调用代理实例，如果代理实例调用失败，则会调用本地伪装mock，返回一个伪装的值
         */
        if (value.length() == 0
                || "false".equalsIgnoreCase(value)) {//是否未采用本地伪装？mock=false，或者未设置mock
            //no mock
            result = this.invoker.invoke(invocation);
        } else if (value.startsWith("force")) {//如果mock属性值以force开头，强制调用本地伪装
            if (logger.isWarnEnabled()) {
                logger.warn("force-mock: " + invocation.getMethodName() + " force-mock enabled , url : " + directory.getUrl());
            }
            //force:direct mock
            result = doMockInvoke(invocation, null);
        } else {//如果mock不为空，且mock值不是以force开头，也就是不是强制走本地伪装，则正常调用报异常后再走本地伪装
            //fail-mock
            try {
                result = this.invoker.invoke(invocation);

                //fix:#4585
                if(result.getException() != null && result.getException() instanceof RpcException){
                    RpcException rpcException= (RpcException)result.getException();
                    if(rpcException.isBiz()){
                        throw  rpcException;
                    }else {
                        result = doMockInvoke(invocation, rpcException);
                    }
                }

            } catch (RpcException e) {
                if (e.isBiz()) {
                    throw e;
                }

                if (logger.isWarnEnabled()) {
                    logger.warn("fail-mock: " + invocation.getMethodName() + " fail-mock enabled , url : " + directory.getUrl(), e);
                }
                result = doMockInvoke(invocation, e);
            }
        }
        return result;
    }

    /***
     * 强制调用mock的代理类
     * @param invocation：RpcInvocation [methodName=sayHelloAsync, parameterTypes=[class java.lang.String], arguments=[world], attachments={}]
     * @param e
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Result doMockInvoke(Invocation invocation, RpcException e) {
        Result result = null;
        Invoker<T> minvoker;

        List<Invoker<T>> mockInvokers = selectMockInvoker(invocation);
        if (CollectionUtils.isEmpty(mockInvokers)) {
            minvoker = (Invoker<T>) new MockInvoker(directory.getUrl(), directory.getInterface());
        } else {
            minvoker = mockInvokers.get(0);
        }
        try {
            result = minvoker.invoke(invocation);
        } catch (RpcException me) {
            if (me.isBiz()) {
                result = AsyncRpcResult.newDefaultAsyncResult(me.getCause(), invocation);
            } else {
                throw new RpcException(me.getCode(), getMockExceptionMessage(e, me), me.getCause());
            }
        } catch (Throwable me) {
            throw new RpcException(getMockExceptionMessage(e, me), me.getCause());
        }
        return result;
    }

    private String getMockExceptionMessage(Throwable t, Throwable mt) {
        String msg = "mock error : " + mt.getMessage();
        if (t != null) {
            msg = msg + ", invoke error is :" + StringUtils.toString(t);
        }
        return msg;
    }

    /**
     * Return MockInvoker
     * Contract：
     * directory.list() will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
     * if directory.list() returns more than one mock invoker, only one of them will be used.
     *
     * @param invocation  RpcInvocation [methodName=sayHelloAsync, parameterTypes=[class java.lang.String], arguments=[world], attachments={}]
     * @return
     */
    private List<Invoker<T>> selectMockInvoker(Invocation invocation) {
        List<Invoker<T>> invokers = null;
        //TODO generic invoker？
        if (invocation instanceof RpcInvocation) {
            //Note the implicit contract (although the description is added to the interface declaration, but extensibility is a problem. The practice placed in the attachment needs to be improved)
            //设置 mock=true
            ((RpcInvocation) invocation).setAttachment(INVOCATION_NEED_MOCK, Boolean.TRUE.toString());
            //directory will return a list of normal invokers if Constants.INVOCATION_NEED_MOCK is present in invocation, otherwise, a list of mock invokers will return.
            try {
                invokers = directory.list(invocation);
            } catch (RpcException e) {
                if (logger.isInfoEnabled()) {
                    logger.info("Exception when try to invoke mock. Get mock invokers error for service:"
                            + directory.getUrl().getServiceInterface() + ", method:" + invocation.getMethodName()
                            + ", will contruct a new mock with 'new MockInvoker()'.", e);
                }
            }
        }
        return invokers;
    }

    @Override
    public String toString() {
        return "invoker :" + this.invoker + ",directory: " + this.directory;
    }
}

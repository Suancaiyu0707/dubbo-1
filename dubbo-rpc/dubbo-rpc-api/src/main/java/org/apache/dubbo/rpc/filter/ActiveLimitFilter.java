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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.Constants.ACTIVES_KEY;

/**
 * ActiveLimitFilter restrict the concurrent client invocation for a service or service's method from client side.
 * To use active limit filter, configured url with <b>actives</b> and provide valid >0 integer value.
 * <pre>
 *     e.g. <dubbo:reference id="demoService" check="false" interface="org.apache.dubbo.demo.DemoService" "actives"="2"/>
 *      In the above example maximum 2 concurrent invocation is allowed.
 *      If there are more than configured (in this example 2) is trying to invoke remote method, then rest of invocation
 *      will wait for configured timeout(default is 0 second) before invocation gets kill by dubbo.
 * </pre>
 *
 * @see Filter
 */

/***
 * 使用方：消费者
 * 用于限制消费者端对服务端的最大并行调用数
 */
@Activate(group = CONSUMER, value = ACTIVES_KEY)
public class ActiveLimitFilter implements Filter, Filter.Listener {

    private static final String ACTIVELIMIT_FILTER_START_TIME = "activelimit_filter_start_time";

    /***
     *
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、获得url、method方法名、actives属性
     * 2、根据url和方法，获得方法调用的统计信息 RpcStatus
     * 3、判断当前服务的客户端请求是否超过限制的最大并行数
     * 4、如果当前客户端的请求数超过客户端最大的限制，则调用wait 挂起等待，最大等待时间可以通过timeout来配置，不然不等待。直接返回
     *   那么这个挂起的等待会在什么时候被唤醒的呢？
     *   a、对应的url执行成功，会唤醒这个rpcStatus
     *   b、对应的url执行错误，也会唤醒这个rpcStatus
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);
        //根据url和方法，获得方法调用的统计信息
        final RpcStatus rpcStatus = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        //判断当前服务的客户端请求是否超过限制的最大并行数
        if (!RpcStatus.beginCount(url, methodName, max)) {
            //获得 timeout 配置
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), TIMEOUT_KEY, 0);
            //计算开始时间
            long start = System.currentTimeMillis();
            long remain = timeout;
            synchronized (rpcStatus) {
                //校验当前是否可以获得请求的信号，如果拿不到，则等待。最大等待时间remain.
                // 这个等待在什么以后唤醒呢？
                //   1、对应的url执行成功，会唤醒这个rpcStatus
                //   2、对应的url执行错误，也会唤醒这个rpcStatus
                while (!RpcStatus.beginCount(url, methodName, max)) {
                    try {
                        //等待获取当前信号。这个等待在什么以后唤醒呢？
                        //  1、对应的url执行成功，会唤醒这个rpcStatus
                        //  2、对应的url执行错误，也会唤醒这个rpcStatus
                        rpcStatus.wait(remain);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    remain = timeout - elapsed;
                    if (remain <= 0) {
                        throw new RpcException(RpcException.LIMIT_EXCEEDED_EXCEPTION,
                                "Waiting concurrent invoke timeout in client-side for service:  " +
                                        invoker.getInterface().getName() + ", method: " + invocation.getMethodName() +
                                        ", elapsed: " + elapsed + ", timeout: " + timeout + ". concurrent invokes: " +
                                        rpcStatus.getActive() + ". max concurrent invoke limit: " + max);
                    }
                }
            }
        }

        invocation.put(ACTIVELIMIT_FILTER_START_TIME, System.currentTimeMillis());

        return invoker.invoke(invocation);
    }

    /***
     * 请求正常结束后
     * 1、会更新对应的url的RpcStatus统计信息：
     *      active： 活跃数减1
     * 2、唤醒上面url对应的被阻塞挂起等待的请求
     *
     * @param appResponse
     * @param invoker
     * @param invocation
     */
    @Override
    public void onMessage(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        String methodName = invocation.getMethodName();
        URL url = invoker.getUrl();
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

        RpcStatus.endCount(url, methodName, getElapsed(invocation), true);
        //则会释放连接数，唤醒url、methodName对应的RpcStatus
        notifyFinish(RpcStatus.getStatus(url, methodName), max);
    }
    /***
     * 请求异常结束后
     * 1、会更新对应的url的RpcStatus统计信息：
     *      active： 活跃数减1
     * 2、唤醒上面url对应的被阻塞挂起等待的请求
     *
     * @param invoker
     * @param invocation
     */
    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        String methodName = invocation.getMethodName();
        URL url = invoker.getUrl();
        int max = invoker.getUrl().getMethodParameter(methodName, ACTIVES_KEY, 0);

        if (t instanceof RpcException) {
            RpcException rpcException = (RpcException) t;
            if (rpcException.isLimitExceed()) {
                return;
            }
        }
        RpcStatus.endCount(url, methodName, getElapsed(invocation), false);
        //如果请求异常了，则会释放连接数，唤醒url、methodName对应的RpcStatus
        notifyFinish(RpcStatus.getStatus(url, methodName), max);
    }

    private long getElapsed(Invocation invocation) {
        Object beginTime = invocation.get(ACTIVELIMIT_FILTER_START_TIME);
        return beginTime != null ? System.currentTimeMillis() - (Long) beginTime : 0;
    }

    /***
     * 为什么会唤醒所有的等待的请求，让这些请求继续抢占资源
     * @param rpcStatus
     * @param max
     */
    private void notifyFinish(final RpcStatus rpcStatus, int max) {
        if (max > 0) {
            synchronized (rpcStatus) {
                rpcStatus.notifyAll();
            }
        }
    }
}

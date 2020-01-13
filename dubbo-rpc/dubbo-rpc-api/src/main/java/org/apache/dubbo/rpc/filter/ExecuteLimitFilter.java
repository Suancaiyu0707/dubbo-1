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
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcStatus;

import static org.apache.dubbo.rpc.Constants.EXECUTES_KEY;


/**
 *
 * The maximum parallel execution request count per method per service for the provider.If the max configured
 * <b>executes</b> is set to 10 and if invoke request where it is already 10 then it will throws exception. It
 * continue the same behaviour un till it is <10.
 *
 */

/***
 * 使用方：服务提供者
 * 作用：用于限制服务端的最大并行数
 * 服务提供者角度下每个方法最大可并行执行请求数。
 */
@Activate(group = CommonConstants.PROVIDER, value = EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter, Filter.Listener {

    private static final String EXECUTELIMIT_FILTER_START_TIME = "execugtelimit_filter_start_time";

    /***
     * 用于限制服务端的最大并行数
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、获取服务提供者的url和调用方法
     * 2、从提供者的url里获取当前调用方法最大的并行数：executes
     * 3、开始统计当前方法的活跃的并行数是否超过限制，如果是的话，则拒绝当前请求，抛出异常。
     * 4、记录当前调用的时间
     * 5、进行服务调用
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        //获取服务提供者的url和调用方法
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        //从提供者的url里获取当前调用方法最大的并行数
        int max = url.getMethodParameter(methodName, EXECUTES_KEY, 0);
        //开始统计当前方法的活跃的并行数是否超过限制，如果是的话，则拒绝当前请求，抛出异常
        if (!RpcStatus.beginCount(url, methodName, max)) {
            throw new RpcException(RpcException.LIMIT_EXCEEDED_EXCEPTION,
                    "Failed to invoke method " + invocation.getMethodName() + " in provider " +
                            url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max +
                            "\" /> limited.");
        }

        invocation.put(EXECUTELIMIT_FILTER_START_TIME, System.currentTimeMillis());
        try {
            return invoker.invoke(invocation);
        } catch (Throwable t) {
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        }
    }

    /***
     * 如果服务调用成功，则更新活跃的并行数，会将当前服务方法的并行调用次数减去1
     * @param appResponse
     * @param invoker
     * @param invocation
     */
    @Override
    public void onMessage(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        RpcStatus.endCount(invoker.getUrl(), invocation.getMethodName(), getElapsed(invocation), true);
    }

    /***
     * 如果服务调用失败，则更新活跃的并行数，会将当前服务方法的并行调用次数减去1
     * @param t
     * @param invoker
     * @param invocation
     */
    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        if (t instanceof RpcException) {
            RpcException rpcException = (RpcException) t;
            if (rpcException.isLimitExceed()) {
                return;
            }
        }
        RpcStatus.endCount(invoker.getUrl(), invocation.getMethodName(), getElapsed(invocation), false);
    }

    private long getElapsed(Invocation invocation) {
        Object beginTime = invocation.get(EXECUTELIMIT_FILTER_START_TIME);
        return beginTime != null ? System.currentTimeMillis() - (Long) beginTime : 0;
    }
}

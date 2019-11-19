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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * BroadcastClusterInvoker
 *
 */

/****
 * @param <T>
 *    问题：
 *     当广播出去100个请求，为什么我要等100个全响应返回？
 *     如果等待所有的都返回了，然后拿到最后一个响应进行返回，这样不得生生的被最慢响应的那个拖死吗？
 */
public class BroadcastClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(BroadcastClusterInvoker.class);

    public BroadcastClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    /***
     *
     * @param invocation
     * @param invokers
     * @param loadbalance
     * @return
     * @throws RpcException
     * 1、检查 invokers列表是否为空
     * 2、在rpc上下文中维护 Invoker 列表
     * 3、遍历所有的服务提供者，并进行调用
     *      记录返回的最后一个异常和返回的最后一个结果
     *      所以后面的result和exception会覆盖掉更早返回的result和exception
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        RpcContext.getContext().setInvokers((List) invokers);
        RpcException exception = null;
        Result result = null;
        //通过下面的遍历，我们可以发现，只要有一个正常返回result即可
        for (Invoker<T> invoker : invokers) {
            try {
                result = invoker.invoke(invocation);
                // 加个这个是不是更好，竟然有请求返回了，干嘛要花无用的时间去等待后面的响应
                //break;
            } catch (RpcException e) {
                //这里面我们发现，后面响应的异常会覆盖掉前面响应的异常
                exception = e;
                logger.warn(e.getMessage(), e);
            } catch (Throwable e) {
                exception = new RpcException(e.getMessage(), e);
                logger.warn(e.getMessage(), e);
            }
        }
        if (exception != null) {
            throw exception;
        }
        return result;
    }

}

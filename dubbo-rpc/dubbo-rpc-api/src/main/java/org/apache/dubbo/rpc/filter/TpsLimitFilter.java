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

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.filter.tps.DefaultTPSLimiter;
import org.apache.dubbo.rpc.filter.tps.TPSLimiter;

import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_RATE_KEY;

/**
 * TpsLimitFilter limit the TPS (transaction per second) for all method of a service or a particular method.
 * Service or method url can define <b>tps</b> or <b>tps.interval</b> to control this control.It use {@link DefaultTPSLimiter}
 * as it limit checker. If a provider service method is configured with <b>tps</b>(optionally with <b>tps.interval</b>),then
 * if invocation count exceed the configured <b>tps</b> value (default is -1 which means unlimited) then invocation will get
 * RpcException.
 * */

/***
 * 使用方：服务提供者
 * 用于服务端的限流
 */
@Activate(group = CommonConstants.PROVIDER, value = TPS_LIMIT_RATE_KEY)
public class TpsLimitFilter implements Filter {

    private final TPSLimiter tpsLimiter = new DefaultTPSLimiter();

    /***
     *
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、检查服务端是否设置了限流属性：tps
     * 2、如果服务设置了tps，则会维护一个StatsItem,这个StatsItem是一个服务一个对象。
     *  每个StatsItem维护了一个滑动窗口大小，以及滑动窗口内服务调用次数，并判断是否对本次服务的调用进行限流
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        if (!tpsLimiter.isAllowable(invoker.getUrl(), invocation)) {
            throw new RpcException(
                    "Failed to invoke service " +
                            invoker.getInterface().getName() +
                            "." +
                            invocation.getMethodName() +
                            " because exceed max service tps.");
        }

        return invoker.invoke(invocation);
    }

}

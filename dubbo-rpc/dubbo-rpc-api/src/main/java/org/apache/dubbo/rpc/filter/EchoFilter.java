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
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import static org.apache.dubbo.rpc.Constants.$ECHO;

/**
 * Dubbo provided default Echo echo service, which is available for all dubbo provider service interface.
 */

/***
 * 使用方：服务提供者
 * 用于回声测试
 */
@Activate(group = CommonConstants.PROVIDER, order = -110000)
public class EchoFilter implements Filter {
    /***
     *
     * @param invoker
     * @param inv
     * @return
     * @throws RpcException
     * 1、如果是回声测试调用，则直接返回一个异步结果对象。
     * 2、如果不是回声测试的，则继续下过滤链传递
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        /***
         * 方法名( $echo ) 且方法参数数量为 1，则直接返回参数
         */
        if (inv.getMethodName().equals($ECHO) //方法名$echo
                && inv.getArguments() != null //参数不为空
                && inv.getArguments().length == 1//且只有一个参数
                ) {
            return AsyncRpcResult.newDefaultAsyncResult(inv.getArguments()[0], inv);
        }
        return invoker.invoke(inv);
    }

}

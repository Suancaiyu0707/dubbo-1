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

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;

/**
 * ConsumerContextFilter set current RpcContext with invoker,invocation, local host, remote host and port
 * for consumer invoker.It does it to make the requires info available to execution thread's RpcContext.
 *
 * @see org.apache.dubbo.rpc.Filter
 * @see RpcContext
 */

/**
 * 使用方：消费者
 * 作用：负责发起调用时，为RpcContext初始化一些调用方的信息，RpcContext会传到服务端
 * RpcContext中存放的是当前调用过程中所需的环境信息。所有配置信息都将转换为 URL 的参数
 *
 *
 * 怎么将客户端设置的隐式参数传递给服务端呢？
 * 载体就是Invocation对象，在客户端调用Invoker.invoke方法时候，会去取当前状态记录器RpcContext中的attachments属性，然后设置到RpcInvocation对象中，
 * 在RpcInvocation传递到provider的时候会通过另外一个过滤器ContextFilter将RpcInvocation对象重新设置回RpcContext中供服务端逻辑重新获取隐式参数。
 * 这就是为什么RpcContext只能记录一次请求的状态信息，因为在第二次调用的时候参数已经被新的RpcInvocation覆盖掉，第一次的请求信息对于第二次执行是不可见的。
 */
@Activate(group = CONSUMER, order = -10000)
public class ConsumerContextFilter implements Filter {
    /***
     *
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     * 1、设置消费者的当前线程的RpcContext，RpcContext会传到服务端
     *      invoker：调用服务信息
     *      localAddress：客户端host
     *      remoteAddress：远程服务地址
     *      remoteApplicationName：远程服务应用
     *      attachment：remote.application设置为
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 设置 RpcContext 对象
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
                .setLocalAddress(NetUtils.getLocalHost(), 0)// 本地地址
                .setRemoteAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort())
                .setRemoteApplicationName(invoker.getUrl().getParameter(REMOTE_APPLICATION_KEY))
                .setAttachment(REMOTE_APPLICATION_KEY, invoker.getUrl().getParameter(APPLICATION_KEY));
        if (invocation instanceof RpcInvocation) {// 设置 RpcInvocation 对象的 `invoker` 属性
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        //调用futureFilter
        return invoker.invoke(invocation);
    }

}

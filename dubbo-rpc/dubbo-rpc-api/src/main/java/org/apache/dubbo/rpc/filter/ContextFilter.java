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
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

import java.util.HashMap;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.ASYNC_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;


/**
 * ContextFilter set the provider RpcContext with invoker, invocation, local port it is using and host for
 * current execution thread.
 *
 * @see RpcContext
 */

/***
 *
 * ContextFilter和ConsumerContextFilter是结合使用的
 * 使用方：服务提供者
 * 作用：负责发起调用时，初始化当前线程的RpcContext
 *
 * 在客户端调用Invoker.invoke方法时候，会去取当前状态记录器RpcContext中的attachments属性，然后设置到RpcInvocation对象中，
 * 在RpcInvocation传递到provider的时候会通过另外一个过滤器ContextFilter将RpcInvocation对象重新设置回RpcContext中供服务端逻辑重新获取隐式参数。
 * 这就是为什么RpcContext只能记录一次请求的状态信息，因为在第二次调用的时候参数已经被新的RpcInvocation覆盖掉，第一次的请求信息对于第二次执行是不可见的。

 */
@Activate(group = PROVIDER, order = -10000)
public class ContextFilter implements Filter, Filter.Listener {
    private static final String TAG_KEY = "dubbo.tag";

    /***
     *
     * @param invoker
     * @param invocation 是一个RpcInvocation，是一个代表客户端向服务端发起请求的在地，包含了客户端的请求信息
     * @return
     * @throws RpcException
     * 1、为提供者把一些上下文的信息设置到当前线程的RpcContext中
     * 2、如果隐式参数不为空，则先移除一些额外的参数，这些参数包括：
     *      path：服务路径
     *      interface：服务名
     *      group：组
     *      token：认证信息
     *      timeout：超时时间
     *      async：异步标志
     *      tag：tag过滤标签
     *
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得客户端传过来的attachments
        Map<String, Object> attachments = invocation.getAttachments();
        if (attachments != null) {//清空消费端的异步参数
            attachments = new HashMap<>(attachments);
            attachments.remove(PATH_KEY);
            attachments.remove(INTERFACE_KEY);
            attachments.remove(GROUP_KEY);
            attachments.remove(VERSION_KEY);
            attachments.remove(DUBBO_VERSION_KEY);
            attachments.remove(TOKEN_KEY);
            attachments.remove(TIMEOUT_KEY);
            // Remove async property to avoid being passed to the following invoke chain.
            attachments.remove(ASYNC_KEY);
            attachments.remove(TAG_KEY);
            attachments.remove(FORCE_USE_TAG);
        }
        RpcContext context = RpcContext.getContext();

        context.setInvoker(invoker)
                .setInvocation(invocation)
//                .setAttachments(attachments)  // merged from dubbox
                .setLocalAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort());
        String remoteApplication = (String) invocation.getAttachment(REMOTE_APPLICATION_KEY);
        if (StringUtils.isNotEmpty(remoteApplication)) {
            context.setRemoteApplicationName(remoteApplication);
        } else {
            context.setRemoteApplicationName((String) RpcContext.getContext().getAttachment(REMOTE_APPLICATION_KEY));

        }


        // merged from dubbox
        // we may already added some attachments into RpcContext before this filter (e.g. in rest protocol)
        // 在此过滤器(例如rest协议)之前，我们可能已经在RpcContext中添加了一些附件
        if (attachments != null) {//将客户端的隐式参数设置到服务端的额上下文RpcContext里
            if (RpcContext.getContext().getAttachments() != null) {
                RpcContext.getContext().getAttachments().putAll(attachments);
            } else {
                RpcContext.getContext().setAttachments(attachments);
            }
        }
        // 设置 RpcInvocation 对象的 `invoker` 属性
        if (invocation instanceof RpcInvocation) {//表示消费端发过来的请求上下文对象
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        try {
            // 服务调用
            RpcContext.getContext().clearAfterEachInvoke(false);
            return invoker.invoke(invocation);
        } finally {
            // 移除上下文
            RpcContext.getContext().clearAfterEachInvoke(true);
            // IMPORTANT! For async scenario, we must remove context from current thread, so we always create a new RpcContext for the next invoke for the same thread.
            RpcContext.removeContext();
            RpcContext.removeServerContext();
        }
    }

    /***
     *
     * @param appResponse
     * @param invoker
     * @param invocation
     */
    @Override
    public void onMessage(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        // pass attachments to result
        appResponse.addAttachments(RpcContext.getServerContext().getAttachments());
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

    }
}

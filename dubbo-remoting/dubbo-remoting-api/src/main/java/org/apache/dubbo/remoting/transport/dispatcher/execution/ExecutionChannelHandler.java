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
package org.apache.dubbo.remoting.transport.dispatcher.execution;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.ChannelState;
import org.apache.dubbo.remoting.transport.dispatcher.WrappedChannelHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Only request message will be dispatched to thread pool. Other messages like response, connect, disconnect,
 * heartbeat will be directly executed by I/O thread.
 *
 * 只有请求消息派发到线程池，不含响应，响应和其它连接断开事件，心跳等消息，直接在 IO 线程上执行。
 */
public class ExecutionChannelHandler extends WrappedChannelHandler {
    /**
     *
     * @param handler  通常是一个解码的 DecodeHandler
     * @param url
     */
    public ExecutionChannelHandler(ChannelHandler handler, URL url) {
        super(handler, url);
    }

    /***
     * 只有请求消息派发到线程池，不含响应，响应和其它连接断开事件，心跳等消息，直接在 IO 线程上执行。
     * @param channel channel.
     * @param message message.
     * @throws RemotingException
     * 1、获得请求共享线程池
     * 2、如果接收到的是一个来自于消费端的请求消息，则会根据请求消息创建一个可执行的任务ChannelEventRunnable，并丢给线程池处理。
     *      对请求处理的结果异常进行响应：
     *          如果返回异常，且是一个请求消息被拒绝，则向请求方发送响应，该响应信息的status：SERVER_THREADPOOL_EXHAUSTED_ERROR
     * 3、如果是一个ThreadlessExecutor线程池，则直接丢给线程池处理，请求异常
     * 4、如果不是一个request请求信息，则直接在 IO 线程上执行
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        ExecutorService executor = getPreferredExecutorService(message);
        /**
         * 我们可以看到，这里只有是request 消息才会交给业务线程池来处理，
         */
        if (message instanceof Request) {
            try {
                executor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
            } catch (Throwable t) {
                // FIXME: when the thread pool is full, SERVER_THREADPOOL_EXHAUSTED_ERROR cannot return properly,
                // therefore the consumer side has to wait until gets timeout. This is a temporary solution to prevent
                // this scenario from happening, but a better solution should be considered later.
                if (t instanceof RejectedExecutionException) {
                    sendFeedback(channel, (Request) message, t);
                }
                throw new ExecutionException(message, channel, getClass() + " error when process received event.", t);
            }
        } else if (executor instanceof ThreadlessExecutor) {
            executor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
        } else {//如果不是一个request请求信息，则直接在 IO 线程上执行
            handler.received(channel, message);
        }
    }
}

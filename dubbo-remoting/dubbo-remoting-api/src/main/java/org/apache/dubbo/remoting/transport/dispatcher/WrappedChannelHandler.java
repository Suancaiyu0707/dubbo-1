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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.util.concurrent.ExecutorService;

public class WrappedChannelHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(WrappedChannelHandler.class);

    protected final ChannelHandler handler;

    protected final URL url;

    public WrappedChannelHandler(ChannelHandler handler, URL url) {
        this.handler = handler;
        this.url = url;
    }

    public void close() {

    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        handler.connected(channel);
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        handler.disconnected(channel);
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        handler.sent(channel, message);
    }

    /***
     * 接收到服务端请求时调用
     * @param channel channel.
     * @param message message.
     * @throws RemotingException
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        handler.received(channel, message);
    }

    /***
     * 捕获异常时调用
     * @param channel   channel.
     * @param exception exception.
     * @throws RemotingException
     */
    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        handler.caught(channel, exception);
    }

    protected void sendFeedback(Channel channel, Request request, Throwable t) throws RemotingException {
        if (request.isTwoWay()) {
            String msg = "Server side(" + url.getIp() + "," + url.getPort()
                    + ") thread pool is exhausted, detail msg:" + t.getMessage();
            Response response = new Response(request.getId(), request.getVersion());
            response.setStatus(Response.SERVER_THREADPOOL_EXHAUSTED_ERROR);
            response.setErrorMessage(msg);
            channel.send(response);
            return;
        }
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    public URL getUrl() {
        return url;
    }

    /**
     * 目前，这种方法主要是为了方便消费端的线程模型而定制的。
     * 1、使用ThreadlessExecutor将服务端的回调直接委托给发起调用的线程。
     * 2、使用共享执行器执行回调
     * Currently, this method is mainly customized to facilitate the thread model on consumer side.
     * 1. Use ThreadlessExecutor, aka., delegate callback directly to the thread initiating the call.
     * 2. Use shared executor to execute the callback.
     *
     * @param msg
     * @return
     */
    public ExecutorService getPreferredExecutorService(Object msg) {
        //如果该消息是一条响应消息(说明此时当前是一个发起最初请求的consumer)
        if (msg instanceof Response) {
            Response response = (Response) msg;
            //从本地内存中找出对应的客户端请求，用于跟reponse一一匹配
            DefaultFuture responseFuture = DefaultFuture.getFuture(response.getId());
            // a typical scenario is the response returned after timeout, the timeout response may has completed the future
            //一个特殊的情况是，响应在请求超时之后返回过来，则responseFuture==null
            if (responseFuture == null) {
                return getSharedExecutorService();
            } else {
                //返回response对应的request所在的线程池
                ExecutorService executor = responseFuture.getExecutor();
                if (executor == null || executor.isShutdown()) {
                    executor = getSharedExecutorService();
                }
                return executor;
            }
        } else {
            //获得共享线程池
            return getSharedExecutorService();
        }
    }

    /**
     * get the shared executor for current Server or Client
     *
     * @return
     * 获得共享线程池.（2019-11-8日对这个共享线程池进行修改了：
     *      粒度改为根据端口号、Consumer/service 来共享线程池）
     */
    public ExecutorService getSharedExecutorService() {
        ExecutorRepository executorRepository =
                ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
        ExecutorService executor = executorRepository.getExecutor(url);
        if (executor == null) {
            executor = executorRepository.createExecutorIfAbsent(url);
        }
        return executor;
    }

    @Deprecated
    public ExecutorService getExecutorService() {
        return getSharedExecutorService();
    }


}

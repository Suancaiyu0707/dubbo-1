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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * ExchangeChannel. (API/SPI, Prototype, ThreadSafe)
 * 用于网络传输过程中，作为消息传输通道
 */
public interface ExchangeChannel extends Channel {

    /**
     * send request.
     *
     * @param request
     * @return response future
     * @throws RemotingException
     * 发送请求信息。并返回结果占位符对象
     */
    @Deprecated
    CompletableFuture<Object> request(Object request) throws RemotingException;

    /**
     * send request.
     * 发送请求
     * @param request
     * @param timeout 请求的超时时间
     * @return response future
     * @throws RemotingException
     * 发送请求信息。并返回结果占位符对象
     */
    @Deprecated
    CompletableFuture<Object> request(Object request, int timeout) throws RemotingException;

    /***
     *
     * @param request
     * @param executor 自定义的用于发送请求的线程池
     * @return
     * @throws RemotingException
     * 发送请求信息。并返回结果占位符对象
     */
    CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException;

    /***
     *
     * @param request
     * @param timeout 请求的超时时间
     * @param executor 自定义的用于发送请求的线程池
     * @return
     * @throws RemotingException
     */
    CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException;

    /**
     * get message handler.
     *  获得信息交换处理器
     * @return message handler
     */
    ExchangeHandler getExchangeHandler();

    /**
     * graceful close.
     *  优雅关闭
     * @param timeout
     */
    @Override
    void close(int timeout);
}

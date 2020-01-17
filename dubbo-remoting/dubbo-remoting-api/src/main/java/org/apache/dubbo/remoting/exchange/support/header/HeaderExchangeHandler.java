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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

import static org.apache.dubbo.common.constants.CommonConstants.READONLY_EVENT;


/**
 * ExchangeReceiver
 * 提供了对接收到的请求/响应进行处理
 * 1、提供对接收到的消息进行分类：
 *      请求、响应、事件、心跳等消息，然后决定是否需要交给下一个handler处理
 *
 */
public class HeaderExchangeHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(HeaderExchangeHandler.class);

    private final ExchangeHandler handler;

    public HeaderExchangeHandler(ExchangeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    /***
     *
     * @param channel
     * @param response
     * @throws RemotingException
     * 1、如果是对心跳消息的响应，则不做处理了。
     * 2、如果不是心跳消息，则开始处理响应消息：
     *   a、根据responseId从内存里获得在之前对应的请求时的占位符DefaultFuture
     *   b、如果DefaultFuture存在，则检查该 DefaultFuture是否已等待超时：
     *      如果请求等待响应超时了，则取消这个DefaultFuture
     *      如果请求等待响应未超时，则交给这个占位符DefaultFuture进行处理接收到的响应。
     *   c、从内存里移除当前的请求等待的占位符缓存
     */
    static void handleResponse(Channel channel, Response response) throws RemotingException {
        //再次判断不是心跳消息
        if (response != null && !response.isHeartbeat()) {
            DefaultFuture.received(channel, response);
        }
    }

    /***
     * 判断渠道是否是客户端连接
     * @param channel
     * @return
     */
    private static boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(url.getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    /***
     * 客户端接收到 READONLY_EVENT 事件请求，进行记录到通道。后续，不再向该服务器，发送新的请求
     * @param channel 发送事件消息的客户端通道
     * @param req 事件请求
     * @throws RemotingException
     */
    void handlerEvent(Channel channel, Request req) throws RemotingException {
        if (req.getData() != null && req.getData().equals(READONLY_EVENT)) {
            channel.setAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY, Boolean.TRUE);
        }
    }

    /***
     * 对双向请求的话，调用该方法处理channel发来的请求消息
     * @param channel 发送事件消息的客户端通道
     * @param req 事件请求
     * @throws RemotingException
     * 1、如果请求是损坏的，也就是无法解析，则返回 BAD_REQUEST 响应
     * 2、处理正常的请求消息，会交给channel的reply进行处理，并最终返回响应
     */
    void handleRequest(final ExchangeChannel channel, Request req) throws RemotingException {
        Response res = new Response(req.getId(), req.getVersion());//根据reqeust id 初始化一个对应的response
        if (req.isBroken()) {//如果请求是损坏的，也就是无法解析，则返回 BAD_REQUEST 响应
            Object data = req.getData();

            String msg;
            if (data == null) {
                msg = null;
            } else if (data instanceof Throwable) {
                msg = StringUtils.toString((Throwable) data);
            } else {
                msg = data.toString();
            }
            res.setErrorMessage("Fail to decode request due to: " + msg);
            res.setStatus(Response.BAD_REQUEST);

            channel.send(res);
            return;
        }
        // find handler by message class.
        Object msg = req.getData();//获得请求信息
        try {
            //返回结果，并设置到响应( Response) 最终返回。
            CompletionStage<Object> future = handler.reply(channel, msg);
            //请求消息执行结束后，会向请求端发送响应
            future.whenComplete((appResult, t) -> {
                try {
                    if (t == null) {
                        res.setStatus(Response.OK);
                        res.setResult(appResult);
                    } else {
                        res.setStatus(Response.SERVICE_ERROR);
                        res.setErrorMessage(StringUtils.toString(t));
                    }
                    channel.send(res);
                } catch (RemotingException e) {
                    logger.warn("Send result to consumer failed, channel is " + channel + ", msg is " + e);
                }
            });
        } catch (Throwable e) {
            res.setStatus(Response.SERVICE_ERROR);
            res.setErrorMessage(StringUtils.toString(e));
            channel.send(res);
        }
    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        handler.connected(exchangeChannel);
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.disconnected(exchangeChannel);
        } finally {
            DefaultFuture.closeChannel(channel);
            HeaderExchangeChannel.removeChannel(channel);
        }
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        Throwable exception = null;
        try {
            ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
            handler.sent(exchangeChannel, message);
        } catch (Throwable t) {
            exception = t;
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
        if (message instanceof Request) {
            Request request = (Request) message;
            DefaultFuture.sent(channel, request);
        }
        if (exception != null) {
            if (exception instanceof RuntimeException) {
                throw (RuntimeException) exception;
            } else if (exception instanceof RemotingException) {
                throw (RemotingException) exception;
            } else {
                throw new RemotingException(channel.getLocalAddress(), channel.getRemoteAddress(),
                        exception.getMessage(), exception);
            }
        }
    }

    /**
     * 接收到消息开始做处理
     * @param channel channel.
     * @param message message.
     * @throws RemotingException
     * 1、如果消息是一条request消息，也就是客户端发来的请求消息
     *      a、如果是一条处理事件请求，则只是记录，并不做任何事
     *      b、如果是普通消息的话(我们平常看到的消费端发起的服务请求都属于这一种)
     *          如果是双向需要响应的：则开始处理请求，并将响应写回请求方。
     *          如果不需要向请求方返回响应的：则直接交给包装的handler进行处理(也就是交给DecodeHandler进行解码，并继续往下传递)
     * 2、如果消息是一条响应消息的话，则直接调用handleResponse进行响应
     *      如果是对心跳消息的响应，则不做处理了。
     *      如果不是心跳消息：
     *          a、根据responseId从内存里获得在之前对应的请求时的占位符DefaultFuture
     *          b、根据DefaultFuture判断是否对应的请求已超时，如果超时了，则直接取消；如果未超时，则交给DefaultFuture处理响应的结果
     * 3、如果是一条string
     *    如果当前channel端是客户端连接，则报错，因为客户端侧，不支持 String
     *    如果是服务端测，则当成一条telnet命令处理。
     * 4、其它消息，直接交给下一个Handler处理
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        final ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        if (message instanceof Request) {//请求消息：Person [name=charles]
            // handle request.
            Request request = (Request) message;
            if (request.isEvent()) {
                handlerEvent(channel, request);
            } else {//如果是普通消息的话(我们平常看到的消费端发起的服务请求都属于这一种)
                if (request.isTwoWay()) {//如果是双向需要响应的
                    handleRequest(exchangeChannel, request);
                } else {//如果不需要向请求方返回响应的
                    handler.received(exchangeChannel, request.getData());
                }
            }
        } else if (message instanceof Response) {
            //处理响应结果，移除本地内存中对应的占位符
            handleResponse(channel, (Response) message);
        } else if (message instanceof String) {// 客户端侧，不支持 String
            if (isClientSide(channel)) {
                Exception e = new Exception("Dubbo client can not supported string message: " + message + " in channel: " + channel + ", url: " + channel.getUrl());
                logger.error(e.getMessage(), e);
            } else {
                String echo = handler.telnet(channel, (String) message);
                if (echo != null && echo.length() > 0) {
                    channel.send(echo);
                }
            }
        } else {
            handler.received(exchangeChannel, message);
        }
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        if (exception instanceof ExecutionException) {
            ExecutionException e = (ExecutionException) exception;
            Object msg = e.getRequest();
            if (msg instanceof Request) {
                Request req = (Request) msg;
                if (req.isTwoWay() && !req.isHeartbeat()) {
                    Response res = new Response(req.getId(), req.getVersion());
                    res.setStatus(Response.SERVER_ERROR);
                    res.setErrorMessage(StringUtils.toString(e));
                    channel.send(res);
                    return;
                }
            }
        }
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.caught(exchangeChannel, exception);
        } finally {
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
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
}

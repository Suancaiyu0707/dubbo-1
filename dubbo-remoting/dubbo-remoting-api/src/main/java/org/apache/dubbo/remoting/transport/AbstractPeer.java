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
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.Endpoint;
import org.apache.dubbo.remoting.RemotingException;

/**
 * AbstractPeer
 * AbstractPeer是对一个ChannelHandler的包装，主要用于在执行ChannelHandler做一些必要的校验。
 * 设计模式：装饰模式
 */
public abstract class AbstractPeer implements Endpoint, ChannelHandler {
    /***
     * 通道处理器
     */
    private final ChannelHandler handler;
    /***
     * 对应的url
     */
    private volatile URL url;

    // closing closed means the process is being closed and close is finished
    /***
     * 正在关闭
     */
    private volatile boolean closing;
    /***
     * 已关闭
     */
    private volatile boolean closed;

    /**
     * 为url绑定一个channelHandler，后续的事件操作都会传递给ChannelHandler
     * @param url
     * @param handler 绑定的 ChannelHandler
     */
    public AbstractPeer(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    /***
     * 发送消息
     * @param message
     * @throws RemotingException
     */
    @Override
    public void send(Object message) throws RemotingException {
        /***
         * 发送消息，可配置 sent 属性；
         *  true：等待消息发出，消息发送失败将抛出异常。
         *  false：不等待消息发出，将消息放入 IO 队列，即刻返回。
         */
        send(message, url.getParameter(Constants.SENT_KEY, false));
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void close(int timeout) {
        close();
    }

    /***
     *  标记正在关闭
     */
    @Override
    public void startClose() {
        if (isClosed()) {
            return;
        }
        closing = true;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        this.url = url;
    }

    @Override
    public ChannelHandler getChannelHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    /**
     * @return ChannelHandler
     */
    @Deprecated
    public ChannelHandler getHandler() {
        return getDelegateHandler();
    }

    /**
     * Return the final handler (which may have been wrapped). This method should be distinguished with getChannelHandler() method
     *
     * @return ChannelHandler
     */
    public ChannelHandler getDelegateHandler() {
        return handler;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing && !closed;
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        if (closed) {//判断当前NettyServer是否关闭状态
            return;
        }//AbstractChannelHandlerDelegate->HeartbeatHandler->AllChannelHandler/DirectChannelHandler等
        handler.connected(ch);//交给Handler处理，也就是Transporters.bind方法绑定的channelHandler
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        handler.disconnected(ch);
    }

    /**
     * 发送消息消息，判断当前的通道时候否关闭了，如果通道为关闭，交给绑定的handler处理
     * @param ch
     * @param msg
     * @throws RemotingException
     */
    @Override
    public void sent(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.sent(ch, msg);
    }

    /***
     * 接收到消息，判断当前的通道时候否关闭了，如果通道为关闭，交给绑定的handler处理
     * @param ch
     * @param msg
     * @throws RemotingException
     */
    @Override
    public void received(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        //调用 MultiMessageHandler
        handler.received(ch, msg);
    }

    /***
     * 捕获到异常，并交给绑定的handler处理
     * @param ch
     * @param ex
     * @throws RemotingException
     */
    @Override
    public void caught(Channel ch, Throwable ex) throws RemotingException {
        handler.caught(ch, ex);
    }
}

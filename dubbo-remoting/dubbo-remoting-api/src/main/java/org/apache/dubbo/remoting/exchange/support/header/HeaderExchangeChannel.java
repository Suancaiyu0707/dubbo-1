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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * ExchangeReceiver
 * HeaderExchangeChannel是 ExchangeChannel 的一个实现类
 * 基于消息头部( Header )的信息交换通道实现类。HeaderExchangeChannel 是传入 channel 属性的装饰器
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";
    /***
     * 具体绑定的通道
     * HeaderExchangeChannel 是 channel 属性的装饰器
     */
    private final Channel channel;

    private volatile boolean closed = false;

    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    /***
     * 创建 HeaderExchangeChannel 对象，并绑定channel
     * @param ch
     * @return
     * 1、检查channel是否绑定了HeaderExchangeChannel
     * 2、如果未绑定，则新建一个并绑定(互相引用)
     * 3、返回绑定的 HeaderExchangeChannel
     */
    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        //检查channel是否绑定了HeaderExchangeChannel
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        //如果未绑定，则新建一个并绑定(互相引用)
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            if (ch.isConnected()) {
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        //返回绑定的 HeaderExchangeChannel
        return ret;
    }

    /***
     * 移除 HeaderExchangeChannel 对象
     * @param ch
     */
    static void removeChannelIfDisconnected(Channel ch) {
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    static void removeChannel(Channel ch) {
        if (ch != null) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }
    //发送消息
    @Override
    public void send(Object message) throws RemotingException {
        send(message, false);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            channel.send(message, sent);
        } else {
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    /***
     * 发送请求
     * @param request
     * @return
     * @throws RemotingException
     */
    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return request(request, null);
    }

    /**
     * 发送请求
     * @param request
     * @param timeout 请求的超时时间
     * @return
     * @throws RemotingException
     */
    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return request(request, timeout, null);
    }

    /***
     * 发送请求
     * @param request
     * @param executor 自定义的用于发送请求的线程池
     * @return
     * @throws RemotingException
     */
    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        return request(request, channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), executor);
    }

    /***
     * 调用网络连接发起请求
     * @param request RpcInvocation [methodName=sayHello, parameterTypes=[class java.lang.String], arguments=[xuzf], attachments={path=org.apache.dubbo.demo.MockService, interface=org.apache.dubbo.demo.MockService, version=0.0.0}]
     * @param timeout
     * @param executor
     * @return
     * @throws RemotingException
     * 1、创建一个请求对象 Request
     * 2、设置请求是双向的，表示需要响应
     * 3、根据通道和请求创建 DefaultFuture 对象
     * 4、向通道发起请求
     * 5、如果发生了异常，则调用DefaultFuture进行取消
     */
    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        if (closed) {//如果该通道已经关闭了，则肯定不允许再发送请求了
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // create request.
        Request req = new Request();
        req.setVersion(Version.getProtocolVersion());//协议版本号：2。0。2
        req.setTwoWay(true);//双向的
        req.setData(request);//请求数据
        DefaultFuture future = DefaultFuture.newFuture(channel, req, timeout, executor);
        try {
            channel.send(req);
        } catch (RemotingException e) {// 发生异常，取消 DefaultFuture
            future.cancel();
            throw e;
        }
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /***
     * 1、遍历DefaultFuture中本地维护的所有的 请求占位符 DefaultFuture对象
     * 2、检查DefaultFuture，如果是采用该channel请求传输的request,则全部返回错误
     * 3、关闭channel
     */
    @Override
    public void close() {
        try {
            // graceful close
            DefaultFuture.closeChannel(channel);
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    /***
     * 优雅的关闭该通道（和close()唯一区别是多了一个等待channel下的请求执行完成，只等待一定的时间）
     * @param timeout
     * 1、如果通道已经关闭，则直接返回
     * 2、修改closed状态，这样不会接收新的请求。
     * 3、如果设置了超时等待关闭的时间，则在超时时间内竟然等待和该channel相关的request尽量响应完成。该channel下可能存在多个request
     *      如果和该channel相关的request响应都完成了，则可以开始关闭channel了
     * 4、超时时间过后，开始调用close()关闭channel
     *
     */
    @Override
    public void close(int timeout) {
        if (closed) {
            return;
        }
        closed = true;
        if (timeout > 0) {
            long start = System.currentTimeMillis();
            while (DefaultFuture.hasFuture(channel)//判断已发起的已经是否已经都响应了。若否，等待完成或超时。
                    && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) {
                return false;
            }
        } else if (!channel.equals(other.channel)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}

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
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.AbstractChannel;
import org.apache.dubbo.remoting.utils.PayloadDropper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * NettyChannel 是一个Dubbo channel
 * 实现 AbstractChannel 抽象类，封装 Netty Channel 的通道实现类。可理解为是Netty的channel的装饰器
 */
final class NettyChannel extends AbstractChannel {

    private static final Logger logger = LoggerFactory.getLogger(NettyChannel.class);
    /**
     * 维护Netty Channel和dubbo channel的映射，这样，可以在channelHandler处理的时候，可先根据handler拿到Netty channel，再根据Netty Channel获取Dubbo Channel，进行业务逻辑操作
     * key：NettyChannel，在创建客户端连接的时候创建的(可参考NettyClient.doConnect)
     * value：dubbo channel
     */
    private static final ConcurrentMap<Channel, NettyChannel> CHANNEL_MAP = new ConcurrentHashMap<Channel, NettyChannel>();
    /**
     * netty channel
     * 它是一个netty的通道，在调用NettyClient.doConnect()时创建的
     */
    private final Channel channel;

    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();

    private final AtomicBoolean active = new AtomicBoolean(false);


    /***
     *
     * @param channel netty的channel
     * @param url 连接地址
     * @param handler dubbo handler处理器，里面包装了netty的handler
     */
    private NettyChannel(Channel channel, URL url, ChannelHandler handler) {
        super(url, handler);
        if (channel == null) {
            throw new IllegalArgumentException("netty channel == null;");
        }
        this.channel = channel;
    }

    /***
     * 从缓存里，根据Netty Channel获得Dubbo Channel
     * @param ch netty channel
     * @param url
     * @param handler dubbo handler,内部包含了netty的handler
     * @return
     * 1、根据Netty Channel从缓存里获取Dubbo Channel。
     *      如果缓存里已存在，直接返回
     *      如果缓存里没有，则根据Netty Channel、url、Dubbo Handler创建一个Dubbo channel,并更新本地缓存
     * 2、返回Dubbo channel
     *
     */
    static NettyChannel getOrAddChannel(Channel ch, URL url, ChannelHandler handler) {
        if (ch == null) {
            return null;
        }
        //根据Netty Channel从缓存里获取Dubbo Channel
        NettyChannel ret = CHANNEL_MAP.get(ch);
        if (ret == null) {
            //根据Netty Channel、url、Dubbo Handler创建一个Dubbo channel
            NettyChannel nettyChannel = new NettyChannel(ch, url, handler);
            if (ch.isActive()) {// 连接中
                nettyChannel.markActive(true);
                ret = CHANNEL_MAP.putIfAbsent(ch, nettyChannel);
            }
            if (ret == null) {
                ret = nettyChannel;
            }
        }
        return ret;
    }

    /***
     * 如果某个Netty channel断开的话，则根据Netty channel从本地缓存移除Dubbo channel
     * @param ch Netty channel
     */
    static void removeChannelIfDisconnected(Channel ch) {
        if (ch != null && !ch.isActive()) {
            NettyChannel nettyChannel = CHANNEL_MAP.remove(ch);
            if (nettyChannel != null) {
                nettyChannel.markActive(false);
            }
        }
    }
    /***
     * 根据Netty channel从本地缓存移除Dubbo channel
     * @param ch Netty channel
     */
    static void removeChannel(Channel ch) {
        if (ch != null) {
            NettyChannel nettyChannel = CHANNEL_MAP.remove(ch);
            if (nettyChannel != null) {
                nettyChannel.markActive(false);
            }
        }
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    @Override
    public boolean isConnected() {
        return !isClosed() && active.get();
    }

    public boolean isActive() {
        return active.get();
    }

    public void markActive(boolean isActive) {
        active.set(isActive);
    }

    /***
     * 发送消息
     * @param message
     * @param sent
     * @throws RemotingException
     * 1、判断dubbo channel是否已关闭
     * 2、利用dubbo channel绑定的netty channel将消息写入
     * 3、判断是否等待超时，如果设置了超时时间，则等待请求响应
     * 4、判断返回结果是否存在异常，是的话，则直接抛出异常。
     */
    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 判断dubbo channel是否已关闭
        super.send(message, sent);//Request [id=1, version=2.0.2, twoway=true, event=false, broken=false, data=RpcInvocation [methodName=sayHello, parameterTypes=[class java.lang.String], arguments=[xuzf], attachments={path=org.apache.dubbo.demo.MockService, interface=org.apache.dubbo.demo.MockService, version=0.0.0}]]

        boolean success = true;
        int timeout = 0;
        try {
            //利用dubbo channel绑定的netty channel将消息写入
            ChannelFuture future = channel.writeAndFlush(message);
            if (sent) {//如果设置了超时时间，则等待请求响应
                // wait timeout ms
                timeout = getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
                success = future.await(timeout);
            }
            Throwable cause = future.cause();
            //判断返回结果是否存在异常，是的话，则直接抛出异常。
            if (cause != null) {
                throw cause;
            }
        } catch (Throwable e) {
            removeChannelIfDisconnected(channel);
            throw new RemotingException(this, "Failed to send message " + PayloadDropper.getRequestWithoutData(message) + " to " + getRemoteAddress() + ", cause: " + e.getMessage(), e);
        }
        if (!success) {
            throw new RemotingException(this, "Failed to send message " + PayloadDropper.getRequestWithoutData(message) + " to " + getRemoteAddress()
                    + "in timeout(" + timeout + "ms) limit");
        }
    }

    /**
     * 关闭通道
     * 1、关闭Duubo channel
     * 2、移除本地netty channel到dubbo channel的映射
     * 3、清空属性
     * 4、关闭Netty channel
     */
    @Override
    public void close() {
        try {
            super.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            removeChannelIfDisconnected(channel);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            attributes.clear();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close netty channel " + channel);
            }
            channel.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        // The null value is unallowed in the ConcurrentHashMap.
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @Override
    public void removeAttribute(String key) {
        attributes.remove(key);
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

        // FIXME: a hack to make org.apache.dubbo.remoting.exchange.support.DefaultFuture.closeChannel work
        if (obj instanceof NettyClient) {
            NettyClient client = (NettyClient) obj;
            return channel.equals(client.getNettyChannel());
        }

        if (getClass() != obj.getClass()) {
            return false;
        }
        NettyChannel other = (NettyChannel) obj;
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
        return "NettyChannel [channel=" + channel + "]";
    }

}

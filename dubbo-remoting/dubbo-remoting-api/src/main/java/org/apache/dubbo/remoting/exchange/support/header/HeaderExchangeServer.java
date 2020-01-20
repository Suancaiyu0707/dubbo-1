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
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Request;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.unmodifiableCollection;
import static org.apache.dubbo.common.constants.CommonConstants.READONLY_EVENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_CHECK_TICK;
import static org.apache.dubbo.remoting.Constants.LEAST_HEARTBEAT_DURATION;
import static org.apache.dubbo.remoting.Constants.TICKS_PER_WHEEL;
import static org.apache.dubbo.remoting.utils.UrlUtils.getHeartbeat;
import static org.apache.dubbo.remoting.utils.UrlUtils.getIdleTimeout;

/**
 * ExchangeServerImpl
 * 1、服务端提供了一个定时任务，用于检查长时间未进行通信的channel，这些channel对应的客户端可能已经关闭了，所以需要关掉，节省资源
 * 2、添加了对channel的本地管理
 */
public class HeaderExchangeServer implements ExchangeServer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    /***
     * 服务器(用于提供服务）
     */
    private final RemotingServer server;
    /***
     * 是否关闭
     */
    private AtomicBoolean closed = new AtomicBoolean(false);
    /***
     * 空闲心跳检查的定时器
     */
    private static final HashedWheelTimer IDLE_CHECK_TIMER = new HashedWheelTimer(new NamedThreadFactory("dubbo-server-idleCheck", true), 1,
            TimeUnit.SECONDS, TICKS_PER_WHEEL);

    private CloseTimerTask closeTimerTask;

    /****
     * @param server
     * 1、将服务端RemotingServer封装成一个HeaderExchangeServer
     * 2、为服务端提供了一个检查空闲客户端的定时器：
     *      服务端用于检查长时间未进行通信的channel，这些channel对应的客户端可能已经关闭了，所以需要关掉，节省资源
     */
    public HeaderExchangeServer(RemotingServer server) {
        Assert.notNull(server, "server == null");
        this.server = server;
        startIdleCheckTask(getUrl());
    }

    public RemotingServer getServer() {
        return server;
    }

    @Override
    public boolean isClosed() {
        return server.isClosed();
    }

    /***
     * 如果跟当前服务端关联的客户端channel，只要还有一个channel是连接状态，则返回true
     * @return
     */
    private boolean isRunning() {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {

            /**
             *  If there are any client connections,
             *  our server should be running.
             */

            if (channel.isConnected()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        doClose();
        server.close();
    }

    /***
     * 关闭服务器
     * @param timeout
     * 1、设置服务器状态为closed，不接受新的请求
     * 2、关闭所有的客户端连接channel，不接受新的请求
     * 3、检查所有的channel是否完成关闭
     * 4、等所有的channel都关闭好，或者超时了，则关闭当前服务器
     */
    @Override
    public void close(final int timeout) {
        //1、设置服务器状态为closed，不接受新的请求
        startClose();

        if (timeout > 0) {
            final long max = (long) timeout;
            final long start = System.currentTimeMillis();
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                //广播客户端，READONLY_EVENT 事件
                sendChannelReadOnlyEvent();
            }
            //等所有的channel都关闭好，或者超时了
            while (HeaderExchangeServer.this.isRunning()
                    && System.currentTimeMillis() - start < max) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        //关闭当前服务器
        doClose();
        server.close(timeout);
    }

    /****
     * Server 关闭的过程，分成两个阶段：正在关闭和已经关闭
     * startClose：标记正在关闭
     */
    @Override
    public void startClose() {
        server.startClose();
    }

    /***
     * 遍历广播所有的客户端，发起READONLY_EVENT 事件，
     * 客户端收到READONLY_EVENT，会修改自己channel的状态为只读，不接受新的连接
     * 1、初始化一个只读事件READONLY_EVENT
     * 2、遍历所有的连接状态是已连接的(断开的不管)，设置这些连接为只读
     */
    private void sendChannelReadOnlyEvent() {
        Request request = new Request();
        request.setEvent(READONLY_EVENT);
        request.setTwoWay(false);
        request.setVersion(Version.getProtocolVersion());

        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            try {
                if (channel.isConnected()) {
                    channel.send(request, getUrl().getParameter(Constants.CHANNEL_READONLYEVENT_SENT_KEY, true));
                }
            } catch (RemotingException e) {
                logger.warn("send cannot write message error.", e);
            }
        }
    }

    /***
     * 1、设置关闭状态为已关闭
     * 2、关闭心跳定时器
     */
    private void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelCloseTask();
    }

    /***
     * 关闭心跳定时器
     */
    private void cancelCloseTask() {
        if (closeTimerTask != null) {
            closeTimerTask.cancel();
        }
    }

    @Override
    public Collection<ExchangeChannel> getExchangeChannels() {
        Collection<ExchangeChannel> exchangeChannels = new ArrayList<ExchangeChannel>();
        Collection<Channel> channels = server.getChannels();
        if (CollectionUtils.isNotEmpty(channels)) {
            for (Channel channel : channels) {
                exchangeChannels.add(HeaderExchangeChannel.getOrAddChannel(channel));
            }
        }
        return exchangeChannels;
    }

    /***
     * 提供了根据远程地址查询连接到当前服务端的Channel
     * @param remoteAddress
     * @return
     */
    @Override
    public ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress) {
        Channel channel = server.getChannel(remoteAddress);
        return HeaderExchangeChannel.getOrAddChannel(channel);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Collection<Channel> getChannels() {
        return (Collection) getExchangeChannels();
    }

    /**
     * 提供了根据远程地址查询连接到当前服务端的Channel
     * @param remoteAddress
     * @return
     */
    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return getExchangeChannel(remoteAddress);
    }

    @Override
    public boolean isBound() {
        return server.isBound();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return server.getLocalAddress();
    }

    @Override
    public URL getUrl() {
        return server.getUrl();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return server.getChannelHandler();
    }

    @Override
    public void reset(URL url) {
        server.reset(url);
        try {
            int currHeartbeat = getHeartbeat(getUrl());
            int currIdleTimeout = getIdleTimeout(getUrl());
            int heartbeat = getHeartbeat(url);
            int idleTimeout = getIdleTimeout(url);
            if (currHeartbeat != heartbeat || currIdleTimeout != idleTimeout) {
                cancelCloseTask();
                startIdleCheckTask(url);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    @Deprecated
    public void reset(org.apache.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    /***
     * 消息发送，交给真正的server进行发送
     * @param message
     * @throws RemotingException
     */
    @Override
    public void send(Object message) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message
                    + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message);
    }

    /***
     * 消息发送，交给真正的server进行发送
     * @param message
     * @param sent
     * @throws RemotingException
     */
    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message
                    + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message, sent);
    }

    /**
     * Each interval cannot be less than 1000ms.
     * 计算心跳时间，最少每隔1s
     */
    private long calculateLeastDuration(int time) {
        if (time / HEARTBEAT_CHECK_TICK <= 0) {
            return LEAST_HEARTBEAT_DURATION;
        } else {
            return time / HEARTBEAT_CHECK_TICK;
        }
    }

    /***
     * 开启定时心跳检查的任务
     * @param url
     * 1、获取连接到这个Server端的所有的channel渠道
     * 2、获得服务端的定时心跳的时间的配置heartbeat值，默认是60s
     * 3、将空闲超时的心跳时间转换成秒，最少1s
     * 4、创建一个定时任务：
     *      遍历每个channel，如果渠道的最后ping的时间或最后pong的时间都超过设置的超时时间，则关闭这个渠道
     * 5、启动定时任务
     */
    private void startIdleCheckTask(URL url) {
        if (!server.canHandleIdle()) {
            AbstractTimerTask.ChannelProvider cp = () -> unmodifiableCollection(HeaderExchangeServer.this.getChannels());
            int idleTimeout = getIdleTimeout(url);
            long idleTimeoutTick = calculateLeastDuration(idleTimeout);
            CloseTimerTask closeTimerTask = new CloseTimerTask(cp, idleTimeoutTick, idleTimeout);
            this.closeTimerTask = closeTimerTask;

            // init task and start timer.
            IDLE_CHECK_TIMER.newTimeout(closeTimerTask, idleTimeoutTick, TimeUnit.MILLISECONDS);
        }
    }
}

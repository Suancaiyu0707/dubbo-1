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
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CLIENT_THREADPOOL;
import static org.apache.dubbo.common.constants.CommonConstants.THREADPOOL_KEY;

/**
 * AbstractClient
 * AbstractClient->AbstractEndpoint->AbstractPeer->ChannelHandler
 * 客户端连接，每个客户端连接都绑定一个channel，用于发送消息
 */
public abstract class AbstractClient extends AbstractEndpoint implements Client {

    protected static final String CLIENT_THREAD_POOL_NAME = "DubboClientHandler";
    private static final Logger logger = LoggerFactory.getLogger(AbstractClient.class);
    private final Lock connectLock = new ReentrantLock();
    /**
     * 发送消息时，若断开，是否重连
     */
    private final boolean needReconnect;
    protected volatile ExecutorService executor;
    //初始化绑定一个 DefaultExecutorRepository
    private ExecutorRepository executorRepository = ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();

    /***
     * 创建一个指向url地址的客户端连接
     * @param url
     * @param handler
     * @throws RemotingException
     */
    public AbstractClient(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        /***
         * 从url中获取 send.reconnect
         */
        needReconnect = url.getParameter(Constants.SEND_RECONNECT_KEY, false);
        //初始化一个客户端线程池
        initExecutor(url);

        try {//初始化客户端
            doOpen();
        } catch (Throwable t) {
            close();
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }
        try {
            // connect.
            connect();// 连接服务器
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress() + " connect to the server " + getRemoteAddress());
            }
        } catch (RemotingException t) {
            if (url.getParameter(Constants.CHECK_KEY, true)) {
                close();
                throw t;
            } else {
                logger.warn("Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                        + " connect to the server " + getRemoteAddress() + " (check == false, ignore and retry later!), cause: " + t.getMessage(), t);
            }
        } catch (Throwable t) {
            close();// 失败，则关闭
            throw new RemotingException(url.toInetSocketAddress(), null,
                    "Failed to start " + getClass().getSimpleName() + " " + NetUtils.getLocalAddress()
                            + " connect to the server " + getRemoteAddress() + ", cause: " + t.getMessage(), t);
        }
    }

    /***
     * 初始化一个连接池
     * 可通过threadpool配置，默认是 cached
     * @param url
     */
    private void initExecutor(URL url) {
        url = ExecutorUtil.setThreadName(url, CLIENT_THREAD_POOL_NAME);
        url = url.addParameterIfAbsent(THREADPOOL_KEY, DEFAULT_CLIENT_THREADPOOL);
        executor = executorRepository.createExecutorIfAbsent(url);
    }

    /***
     * 包装channel的处理器
     * @param url
     * @param handler
     * @return
     */
    protected static ChannelHandler wrapChannelHandler(URL url, ChannelHandler handler) {
        return ChannelHandlers.wrap(handler, url);
    }

    public InetSocketAddress getConnectAddress() {
        return new InetSocketAddress(NetUtils.filterLocalHost(getUrl().getHost()), getUrl().getPort());
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        Channel channel = getChannel();
        if (channel == null) {
            return getUrl().toInetSocketAddress();
        }
        return channel.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        Channel channel = getChannel();
        if (channel == null) {
            return InetSocketAddress.createUnresolved(NetUtils.getLocalHost(), 0);
        }
        return channel.getLocalAddress();
    }

    /***
     * 判断当前channel的连接状态
     * @return
     */
    @Override
    public boolean isConnected() {
        Channel channel = getChannel();
        if (channel == null) {
            return false;
        }
        return channel.isConnected();
    }

    @Override
    public Object getAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return null;
        }
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        Channel channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return;
        }
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        Channel channel = getChannel();
        if (channel == null) {
            return false;
        }
        return channel.hasAttribute(key);
    }

    /**
     * 发送消息
     * @param message
     * @param sent 是否阻塞发送
     * @throws RemotingException
     * 1、如果当前连接还未连接，且配置了重连，则重新连接服务端
     * 2、如果客户端的channel还是关闭或为空，则抛出异常
     * 3、用过channel发送消息
     */
    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (needReconnect && !isConnected()) {
            connect();
        }
        Channel channel = getChannel();
        //TODO Can the value returned by getChannel() be null? need improvement.
        if (channel == null || !channel.isConnected()) {
            throw new RemotingException(this, "message can not send, because channel is closed . url:" + getUrl());
        }
        channel.send(message, sent);
    }

    /***
     * 连接服务器
     * @throws RemotingException
     *
     */
    protected void connect() throws RemotingException {

        connectLock.lock();

        try {
            //如果当前客户端已连接，则直接返回
            if (isConnected()) {
                return;
            }
            //向客户端发起连接，交给具体的实现类来实现
            doConnect();

            if (!isConnected()) {
                throw new RemotingException(this, "Failed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                        + ", cause: Connect wait timeout: " + getConnectTimeout() + "ms.");

            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("Successed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                            + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                            + ", channel is " + this.getChannel());
                }
            }

        } catch (RemotingException e) {
            throw e;

        } catch (Throwable e) {
            throw new RemotingException(this, "Failed connect to server " + getRemoteAddress() + " from " + getClass().getSimpleName() + " "
                    + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion()
                    + ", cause: " + e.getMessage(), e);

        } finally {
            connectLock.unlock();
        }
    }

    /***
     * 断开连接
     */
    public void disconnect() {
        connectLock.lock();
        try {
            try {
                //关闭连接对应的channel
                Channel channel = getChannel();
                if (channel != null) {
                    channel.close();
                }
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
            try {
                //断开连接，交给具体的子类实现
                doDisConnect();
            } catch (Throwable e) {
                logger.warn(e.getMessage(), e);
            }
        } finally {
            connectLock.unlock();
        }
    }

    /***
     * 重连
     * @throws RemotingException
     */
    @Override
    public void reconnect() throws RemotingException {
        //判断是否已经连接
        if (!isConnected()) {//如果channel还未连接到服务端(中途可能断开了)，则开始向服务端发起连接
            connectLock.lock();
            try {
                if (!isConnected()) {
                    disconnect();
                    connect();
                }
            } finally {
                connectLock.unlock();
            }
        }
    }

    /***
     * 关闭客户端连接
     */
    @Override
    public void close() {

        try {
            super.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        try {
            if (executor != null) {
                ExecutorUtil.shutdownNow(executor, 100);
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        try {
            disconnect();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        try {
            doClose();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public void close(int timeout) {
        ExecutorUtil.gracefulShutdown(executor, timeout);
        close();
    }

    @Override
    public String toString() {
        return getClass().getName() + " [" + getLocalAddress() + " -> " + getRemoteAddress() + "]";
    }

    /**
     * Open client.
     *
     * @throws Throwable
     */
    protected abstract void doOpen() throws Throwable;

    /**
     * Close client.
     *
     * @throws Throwable
     */
    protected abstract void doClose() throws Throwable;

    /**
     * Connect to server.
     *
     * @throws Throwable
     */
    protected abstract void doConnect() throws Throwable;

    /**
     * disConnect to server.
     *
     * @throws Throwable
     */
    protected abstract void doDisConnect() throws Throwable;

    /**
     * Get the connected channel.
     *
     * @return channel
     */
    protected abstract Channel getChannel();
}

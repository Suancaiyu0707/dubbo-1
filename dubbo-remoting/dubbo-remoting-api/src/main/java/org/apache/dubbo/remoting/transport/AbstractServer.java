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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.remoting.Constants.ACCEPTS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_ACCEPTS;
import static org.apache.dubbo.remoting.Constants.DEFAULT_IDLE_TIMEOUT;
import static org.apache.dubbo.remoting.Constants.IDLE_TIMEOUT_KEY;

/**
 * AbstractServer
 * 代表一个服务端
 */
public abstract class AbstractServer extends AbstractEndpoint implements RemotingServer {

    protected static final String SERVER_THREAD_POOL_NAME = "DubboServerHandler";
    private static final Logger logger = LoggerFactory.getLogger(AbstractServer.class);
    /***
     * 线程池，用于接收客户端连接，默认是cached的连接池，可通过url的threadpool配置修改
     */
    ExecutorService executor;
    /***
     * 本地地址
     */
    private InetSocketAddress localAddress;
    /***
     * 绑定的服务端地址
     */
    private InetSocketAddress bindAddress;
    /***
     * 可接受的客户端的连接数，请与execute、active、tps等区分开
     * 每个客户端会创建一个Channel，该参数就是用于控制最大的Channel数
     */
    private int accepts;
    /***
     * 最大的空闲时间
     */
    private int idleTimeout;

    private ExecutorRepository executorRepository = ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();

    /***
     * 初始化一个服务端，并绑定指定的端口
     * @param url
     * @param handler
     * @throws RemotingException
     * 1、获得本地地址
     * 2、获取绑定的端口和ip，可分别通过bind.ip、bind.port配置
     * 3、获得最大的可连接的客户端channel数，可通过accepts 配置
     * 4、获得空闲时间
     * 5、绑定端口并创建一个服务端对象
     * 6、初始化一个用于接收客户端请求的连接池，默认是cached
     */
    public AbstractServer(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
        //获得本地地址
        localAddress = getUrl().toInetSocketAddress();
        //获取绑定的端口和ip，可分别通过bind.ip、bind.port配置
        String bindIp = getUrl().getParameter(Constants.BIND_IP_KEY, getUrl().getHost());
        int bindPort = getUrl().getParameter(Constants.BIND_PORT_KEY, getUrl().getPort());
        if (url.getParameter(ANYHOST_KEY, false) || NetUtils.isInvalidLocalHost(bindIp)) {
            bindIp = ANYHOST_VALUE;
        }
        bindAddress = new InetSocketAddress(bindIp, bindPort);
        //获得最大的可连接的客户端channel数，可通过accepts 配置
        this.accepts = url.getParameter(ACCEPTS_KEY, DEFAULT_ACCEPTS);
        //获得空闲时间
        this.idleTimeout = url.getParameter(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT);
        try {
            //绑定端口并创建一个服务端
            doOpen();
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
            }
        } catch (Throwable t) {
            throw new RemotingException(url.toInetSocketAddress(), null, "Failed to bind " + getClass().getSimpleName()
                    + " on " + getLocalAddress() + ", cause: " + t.getMessage(), t);
        }
        //初始化一个用于接收客户端请求的连接池，默认是cached
        executor = executorRepository.createExecutorIfAbsent(url);
    }

    protected abstract void doOpen() throws Throwable;

    protected abstract void doClose() throws Throwable;

    @Override
    public void reset(URL url) {
        if (url == null) {
            return;
        }
        try {
            if (url.hasParameter(ACCEPTS_KEY)) {
                int a = url.getParameter(ACCEPTS_KEY, 0);
                if (a > 0) {
                    this.accepts = a;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            if (url.hasParameter(IDLE_TIMEOUT_KEY)) {
                int t = url.getParameter(IDLE_TIMEOUT_KEY, 0);
                if (t > 0) {
                    this.idleTimeout = t;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        executorRepository.updateThreadpool(url, executor);
        super.setUrl(getUrl().addParameters(url.getParameters()));
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            if (channel.isConnected()) {
                channel.send(message, sent);
            }
        }
    }

    @Override
    public void close() {
        if (logger.isInfoEnabled()) {
            logger.info("Close " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
        }
        ExecutorUtil.shutdownNow(executor, 100);
        try {
            super.close();
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
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public InetSocketAddress getBindAddress() {
        return bindAddress;
    }

    public int getAccepts() {
        return accepts;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    /***
     * 被客户端连接(如果接收到客户端连接，会调用该方法)
     * @param ch
     * @throws RemotingException
     * 1、判断当前服务对象是否打开
     * 2、获得连接到当前Server的channel的总数
     * 3、判断连接的channel是否超过限制了
     * 4、创建连接
     */
    @Override
    public void connected(Channel ch) throws RemotingException {
        // If the server has entered the shutdown process, reject any new connection
        if (this.isClosing() || this.isClosed()) {
            logger.warn("Close new channel " + ch + ", cause: server is closing or has been closed. For example, receive a new connect request while in shutdown process.");
            ch.close();
            return;
        }

        Collection<Channel> channels = getChannels();
        if (accepts > 0 && channels.size() > accepts) {
            logger.error("Close channel " + ch + ", cause: The server " + ch.getLocalAddress() + " connections greater than max config " + accepts);
            ch.close();
            return;
        }
        super.connected(ch);
    }

    /***
     * 当客户端发来和Server断开连接时，调用该方法
     * @param ch
     * @throws RemotingException
     * 1、获得客户端的channel列表
     * 2、断开客户端的连接
     */
    @Override
    public void disconnected(Channel ch) throws RemotingException {
        Collection<Channel> channels = getChannels();
        if (channels.isEmpty()) {
            logger.warn("All clients has disconnected from " + ch.getLocalAddress() + ". You can graceful shutdown now.");
        }
        super.disconnected(ch);
    }

}

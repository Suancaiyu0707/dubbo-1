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
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.transport.AbstractServer;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;
import org.apache.dubbo.remoting.utils.UrlUtils;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.CommonConstants.IO_THREADS_KEY;
import static org.apache.dubbo.remoting.Constants.SSL_ENABLED_KEY;

/**
 * NettyServer.
 */
public class NettyServer extends AbstractServer implements RemotingServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);
    /**
     * 某个服务端下的dubbo channel的集合
     * key：ip:port
     * value：dubbo channel
     */
    private Map<String, Channel> channels;
    /**
     * netty server的启动类
     */
    private ServerBootstrap bootstrap;
    /**
     * Netty的NioServerSocketChannel，用于接收客户端创立连接的请求
     */
	private io.netty.channel.Channel channel;
    /***
     * 为ServerSocketChannel分配线程，用于接收客户端创立连接的请求
     */
    private EventLoopGroup bossGroup;
    /***
     * 为创建的客户端连接绑定线程，用于接收对应客户端的读写请求
     */
    private EventLoopGroup workerGroup;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        // you can customize name and type of client thread pool by THREAD_NAME_KEY and THREADPOOL_KEY in CommonConstants.
        // the handler will be warped: MultiMessageHandler->HeartbeatHandler->handler
        super(ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME), ChannelHandlers.wrap(handler, url));
    }

    /**
     * Init and start netty server
     * @throws Throwable
     */
    /***
     * 初始化并启动一个netty 服务
     * @throws Throwable
     * 1、创建Netty Server的启动类，以及为ServerSocketChannel和SocketChannel分配线程的group组
     * 2、创建一个NettyServerHandler。当一个新的客户端连接被创刊成功后，会被添加到新建的channel的ChannelPipeline上
     * 3、初始化启动类ServerBootstrap
     * 4、绑定端口并创建服务Netty Server，并返回绑定响应的占位符
     *
     */
    @Override
    protected void doOpen() throws Throwable {
        bootstrap = new ServerBootstrap();
        //为ServerSocketChannel创建用于分配接收客户端连接的线程的group(可以看到这边的线程池的大小只有一个线程)
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("NettyServerBoss", true));
        //为SocketChannel创建用于分配接收读写请求的线程的group
        workerGroup = new NioEventLoopGroup(getUrl().getPositiveParameter(IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS),
                new DefaultThreadFactory("NettyServerWorker", true));
        //创建一个NettyServerHandler。当一个新的客户端连接被创刊成功后，会被添加到新建的channel的ChannelPipeline上
        final NettyServerHandler nettyServerHandler = new NettyServerHandler(getUrl(), this);
        channels = nettyServerHandler.getChannels();
        //初始化
        bootstrap.group(bossGroup, workerGroup)
                // 设置 Channel类型,是一个服务端的channel，会接收客户端的连接请求
                .channel(NioServerSocketChannel.class)
                // 设置可选项
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                // 为新建的SocketChannel绑定一个ChannelPipeline,并添加默认的ChannelHandler
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        // FIXME: should we use getTimeout()?
                        int idleTimeout = UrlUtils.getIdleTimeout(getUrl());
                        NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                        if (getUrl().getParameter(SSL_ENABLED_KEY, false)) {
                            ch.pipeline().addLast("negotiation",
                                    SslHandlerInitializer.sslServerHandler(getUrl(), nettyServerHandler));
                        }
                        ch.pipeline()
                                .addLast("decoder", adapter.getDecoder())
                                .addLast("encoder", adapter.getEncoder())
                                .addLast("server-idle-handler", new IdleStateHandler(0, 0, idleTimeout, MILLISECONDS))
                                .addLast("handler", nettyServerHandler);
                    }
                });
        // 绑定端口并创建服务Netty Server，并返回绑定响应的占位符
        ChannelFuture channelFuture = bootstrap.bind(getBindAddress());
        channelFuture.syncUninterruptibly();
        channel = channelFuture.channel();

    }

    /***
     * 关闭这个netty server服务器
     * @throws Throwable
     * 1、先关闭NioServerSocketChannel，拒绝新的客户端连接。
     * 2、遍历所有的客户端连接Dubbo channel，并一个个的进行关闭
     * 3、优雅关闭bossGroup和workerGroup，释放资源
     * 4、清空本地缓存
     */
    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            //遍历所有的客户端连接Dubbo channel，并一个个的进行关闭
            Collection<org.apache.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                for (org.apache.dubbo.remoting.Channel channel : channels) {
                    try {
                        //关闭Dubbo channel的时候，内部会先关闭绑定的SocketChannel
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    /***
     * 获得连接到这个Netty server上的所有通道(会移除已关闭的客户端)
     * @return
     */
    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        //获得连接到这个Netty server上的所有通道
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {//移除已关闭的客户端
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean canHandleIdle() {
        return true;
    }

    @Override
    public boolean isBound() {
        return channel.isActive();
    }

}

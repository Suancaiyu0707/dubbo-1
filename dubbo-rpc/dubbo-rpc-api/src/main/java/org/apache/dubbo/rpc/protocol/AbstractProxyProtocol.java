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

package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;

/**
 * AbstractProxyProtocol
 */
public abstract class AbstractProxyProtocol extends AbstractProtocol {
    /**
     * 需要抛出的异常类集合
     */
    private final List<Class<?>> rpcExceptions = new CopyOnWriteArrayList<Class<?>>();
    /**
     * ProxyFactory 对象
     */
    protected ProxyFactory proxyFactory;

    public AbstractProxyProtocol() {
    }

    public AbstractProxyProtocol(Class<?>... exceptions) {
        for (Class<?> exception : exceptions) {
            addRpcException(exception);
        }
    }

    public void addRpcException(Class<?> exception) {
        this.rpcExceptions.add(exception);
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    /**
     * Exporter 集合
     *
     * key: 服务键 {@link #serviceKey(URL)}
     * 1、获得暴露服务的servicekey
     * 2、检查本地内存是否有servicekey对应的暴露对象，有的话，表示已暴露，则直接返回
     * 3、为暴露的服务端绑定一个Server,同时为暴露的具体服务地址绑定一个JsonRpcServer
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        //获得待暴露的服务的key
        final String uri = serviceKey(invoker.getUrl());
        // 获得 Exporter 对象
        Exporter<T> exporter = (Exporter<T>) exporterMap.get(uri);
        if (exporter != null) {//若已经暴露，直接返回
            // When modifying the configuration through override, you need to re-expose the newly modified service.
            if (Objects.equals(exporter.getInvoker().getUrl(), invoker.getUrl())) {
                return exporter;
            }
        }
        /**
         * 为暴露的服务端绑定一个Server,同时为暴露的具体服务地址绑定一个JsonRpcServer，并返回一个线程
         */
        final Runnable runnable = doExport(proxyFactory.getProxy(invoker, true), invoker.getInterface(), invoker.getUrl());
        //创建一个暴露服务的对象，该对象包含解除暴露的方法
        exporter = new AbstractExporter<T>(invoker) {
            @Override
            public void unexport() {
                super.unexport();
                exporterMap.remove(uri);
                //执行取消暴露的回调
                if (runnable != null) {
                    try {
                        runnable.run();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        };
        exporterMap.put(uri, exporter);
        return exporter;
    }

    /***
     * 根据协议绑定服务引用对象
     * @param type 引用类型
     *
     * @param url
     *
     * @param <T>
     * @return
     * @throws RpcException
     * 1、创建一个服务实际的引用对象target
     * 2、把实际的target包装成一个AbstractInvoker，添加了对调用返回的异常的处理：
     *      若返回结果带有异常，并且需要抛出( 异常在 rpcExceptions 中)，则抛出异常。
     */
    @Override
    protected <T> Invoker<T> protocolBindingRefer(final Class<T> type, final URL url) throws RpcException {
        //根据服务引用，创建一个引用对象：interface org.apache.dubbo.rpc.protocol.http.HttpServiceTest -> http://127.0.0.1:9999/org.apache.dubbo.rpc.protocol.http.HttpServiceTest?server=jetty&version=1.0.0
        final Invoker<T> target = proxyFactory.getInvoker(doRefer(type, url), type, url);
        //创建 Invoker 对象
        Invoker<T> invoker = new AbstractInvoker<T>(type, url) {
            @Override
            protected Result doInvoke(Invocation invocation) throws Throwable {
                try {
                    //通过引用对象调用引用的方法
                    Result result = target.invoke(invocation);
                    // FIXME result is an AsyncRpcResult instance.
                    Throwable e = result.getException();
                    if (e != null) {// 若返回结果带有异常，并且需要抛出，则抛出异常。
                        for (Class<?> rpcException : rpcExceptions) {
                            if (rpcException.isAssignableFrom(e.getClass())) {
                                throw getRpcException(type, url, invocation, e);
                            }
                        }
                    }
                    return result;
                } catch (RpcException e) {
                    // 若是未知异常，获得异常对应的错误码
                    if (e.getCode() == RpcException.UNKNOWN_EXCEPTION) {
                        e.setCode(getErrorCode(e.getCause()));
                    }
                    throw e;
                } catch (Throwable e) {
                    throw getRpcException(type, url, invocation, e);
                }
            }
        };
        invokers.add(invoker);
        return invoker;
    }

    protected RpcException getRpcException(Class<?> type, URL url, Invocation invocation, Throwable e) {
        RpcException re = new RpcException("Failed to invoke remote service: " + type + ", method: "
                + invocation.getMethodName() + ", cause: " + e.getMessage(), e);
        re.setCode(getErrorCode(e));
        return re;
    }

    protected String getAddr(URL url) {
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (url.getParameter(ANYHOST_KEY, false)) {
            bindIp = ANYHOST_VALUE;
        }
        return NetUtils.getIpByHost(bindIp) + ":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    }

    protected int getErrorCode(Throwable e) {
        return RpcException.UNKNOWN_EXCEPTION;
    }

    protected abstract <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException;

    protected abstract <T> T doRefer(Class<T> type, URL url) throws RpcException;

    protected class ProxyProtocolServer implements ProtocolServer {

        private RemotingServer server;
        private String address;

        public ProxyProtocolServer(RemotingServer server) {
            this.server = server;
        }

        @Override
        public RemotingServer getRemotingServer() {
            return server;
        }

        @Override
        public String getAddress() {
            return StringUtils.isNotEmpty(address) ? address : server.getUrl().getAddress();
        }

        @Override
        public void setAddress(String address) {
            this.address = address;
        }

        @Override
        public URL getUrl() {
            return server.getUrl();
        }

        @Override
        public void close() {
            server.close();
        }
    }

    protected abstract class RemotingServerAdapter implements RemotingServer {

        public abstract Object getDelegateServer();

        /**
         * @return
         */
        @Override
        public boolean isBound() {
            return false;
        }

        @Override
        public Collection<Channel> getChannels() {
            return null;
        }

        @Override
        public Channel getChannel(InetSocketAddress remoteAddress) {
            return null;
        }

        @Override
        public void reset(Parameters parameters) {

        }

        @Override
        public void reset(URL url) {

        }

        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public ChannelHandler getChannelHandler() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public void send(Object message) throws RemotingException {

        }

        @Override
        public void send(Object message, boolean sent) throws RemotingException {

        }

        @Override
        public void close() {

        }

        @Override
        public void close(int timeout) {

        }

        @Override
        public void startClose() {

        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }


}

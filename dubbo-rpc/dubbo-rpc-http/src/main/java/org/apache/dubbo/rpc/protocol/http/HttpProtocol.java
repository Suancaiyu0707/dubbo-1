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
package org.apache.dubbo.rpc.protocol.http;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;

import com.googlecode.jsonrpc4j.HttpException;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean;
import org.springframework.remoting.RemoteAccessException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/***
 * 为 HttpProtocol 、RestProtocol 等子类，提供公用的服务暴露、服务引用的公用方法
 *
 *  请求处理过程为  HttpServlet => DispatcherServlet => InternalHandler => JsonRpcServer
 */
public class HttpProtocol extends AbstractProxyProtocol {

    public static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";
    /***
     * key：暴露的服务的path
     * value：new JsonRpcServer(impl, type)
     */
    private final Map<String, JsonRpcServer> skeletonMap = new ConcurrentHashMap<>();
    //HttpBinder$Adaptive 对象，通过 #setHttpBinder(httpBinder) 方法，Dubbo SPI 调用设置。默认是Jetty
    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(HttpException.class, JsonRpcClientException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }
    /**
     * 默认服务器端口
     */
    @Override
    public int getDefaultPort() {
        return 80;
    }

    /***
     * 用于接收请求并处理结果响应
     */
    private class InternalHandler implements HttpHandler {

        private boolean cors;

        public InternalHandler(boolean cors) {
            this.cors = cors;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws ServletException {
            String uri = request.getRequestURI();
            JsonRpcServer skeleton = skeletonMap.get(uri);
            if (cors) {//是否跨域
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "POST");
                response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
            }
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(200);
            } else if ("POST".equalsIgnoreCase(request.getMethod())) {// 必须是 POST 请求
                // 执行调用
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    skeleton.handle(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            } else {
                response.setStatus(500);
            }
        }

    }
    /**
     * 执行暴露，并返回取消暴露的回调 Runnable
     *
     * @param impl 服务代理对象
     *              eg：Proxy0@1935
     * @param type 服务接口
     *             eg;    interface org.apache.dubbo.rpc.protocol.http.HttpServiceTest
     * @param url URL
     *              eg: http://127.0.0.1:9999/org.apache.dubbo.rpc.protocol.http.HttpServiceTest?server=jetty&version=1.0.0
     * @param <T> 服务接口
     * @return 消暴露的回调 Runnable
     * @throws RpcException 当发生异常
     * 1、获得服务暴露的地址，默认是host：port
     * 2、检查当前服务实例是否已为该暴露地址(ip:host)创建一个Server对象，有就不需要再创建了，没有则创建并绑定一个：
     *      注意，只跟ip:host有关
     * 3、为暴露的服务地址维护一个JsonRpcServer，并返回
     */
    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        //获得服务器地址，默认是host：port
        String addr = getAddr(url);//127.0.0.1:9999
        //检查当前服务实例是否已为该暴露地址(ip:host)创建一个Server对象，有就不需要再创建了，没有则创建并绑定一个
        ProtocolServer protocolServer = serverMap.get(addr);
        if (protocolServer == null) {
            RemotingServer remotingServer = httpBinder.bind(url, new InternalHandler(url.getParameter("cors", false)));
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        //为暴露的服务地址维护一个JsonRpcServer，并返回
        final String path = url.getAbsolutePath();//http://127.0.0.1:9999/org.apache.dubbo.rpc.protocol.http.HttpServiceTest?server=jetty&version=1.0.0
        JsonRpcServer skeleton = new JsonRpcServer(impl, type);
        skeletonMap.put(path, skeleton);
        //返回一个线程，线程只做了内存移除JsonRpcServer的操作
        return () -> skeletonMap.remove(path);
    }
    /**
     * 执行引用，并返回调用远程服务的 Service 对象
     *
     * @param url URL
     *
     * @param <T> 服务接口
     *
     * @return 调用远程服务的 Service 对象
     *
     * @throws RpcException 当发生异常
     *
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        JsonProxyFactoryBean jsonProxyFactoryBean = new JsonProxyFactoryBean();
        jsonProxyFactoryBean.setServiceUrl(url.setProtocol("http").toIdentityString());
        jsonProxyFactoryBean.setServiceInterface(serviceType);

        jsonProxyFactoryBean.afterPropertiesSet();
        return (T) jsonProxyFactoryBean.getObject();
    }

    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close jsonrpc server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

}
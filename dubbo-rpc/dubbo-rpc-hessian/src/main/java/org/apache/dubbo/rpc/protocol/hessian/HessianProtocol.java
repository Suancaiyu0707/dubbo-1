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
package org.apache.dubbo.rpc.protocol.hessian;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import com.caucho.hessian.HessianException;
import com.caucho.hessian.client.HessianConnectionException;
import com.caucho.hessian.client.HessianConnectionFactory;
import com.caucho.hessian.client.HessianProxyFactory;
import com.caucho.hessian.io.HessianMethodSerializationException;
import com.caucho.hessian.server.HessianSkeleton;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_EXCHANGER;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.DEFAULT_HESSIAN2_REQUEST;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.DEFAULT_HESSIAN_OVERLOAD_METHOD;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.DEFAULT_HTTP_CLIENT;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.HESSIAN2_REQUEST_KEY;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.HESSIAN_OVERLOAD_METHOD_KEY;

/**
 * http rpc support.
 * 参考HttpProtocol
 * Hessian 协议用于集成 Hessian 的服务，Hessian 底层采用 Http 通讯，采用 Servlet 暴露服务，Dubbo 缺省内嵌 Jetty 作为服务器实现。
 */
public class HessianProtocol extends AbstractProxyProtocol {

    private final Map<String, HessianSkeleton> skeletonMap = new ConcurrentHashMap<String, HessianSkeleton>();

    private HttpBinder httpBinder;

    public HessianProtocol() {
        super(HessianException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return 80;
    }

    /***
     *
     * @param impl 代理对象
     *
     * @param type 接口类型
     *      interface org.apache.dubbo.rpc.protocol.hessian.HessianService
     * @param url 服务地址
     *      hessian://127.0.0.1:5342/org.apache.dubbo.rpc.protocol.hessian.HessianService?hessian.overload.method=true&version=1.0.0
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException {
        // 获得服务器地址
        String addr = getAddr(url);//127.0.0.1:5342
        // 获得 HttpServer 对象。若不存在，进行创建。
        ProtocolServer protocolServer = serverMap.get(addr);
        if (protocolServer == null) {
            RemotingServer remotingServer = httpBinder.bind(url, new HessianHandler());
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        final String path = url.getAbsolutePath();// /org.apache.dubbo.rpc.protocol.hessian.HessianService
        final HessianSkeleton skeleton = new HessianSkeleton(impl, type);
        skeletonMap.put(path, skeleton);

        final String genericPath = path + "/" + GENERIC_KEY;
        skeletonMap.put(genericPath, new HessianSkeleton(impl, GenericService.class));

        return new Runnable() {
            @Override
            public void run() {
                skeletonMap.remove(path);
                skeletonMap.remove(genericPath);
            }
        };
    }

    /***
     * 暴露服务
     * @param serviceType
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(Class<T> serviceType, URL url) throws RpcException {
        //判断是否泛化调用：generic
        String generic = url.getParameter(GENERIC_KEY);
        boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        if (isGeneric) {
            RpcContext.getContext().setAttachment(GENERIC_KEY, generic);
            url = url.setPath(url.getPath() + "/" + GENERIC_KEY);
        }
        //创建一个Hessian代理工厂
        HessianProxyFactory hessianProxyFactory = new HessianProxyFactory();
        //判断是否hessian请求
        boolean isHessian2Request = url.getParameter(HESSIAN2_REQUEST_KEY, DEFAULT_HESSIAN2_REQUEST);
        hessianProxyFactory.setHessian2Request(isHessian2Request);
        boolean isOverloadEnabled = url.getParameter(HESSIAN_OVERLOAD_METHOD_KEY, DEFAULT_HESSIAN_OVERLOAD_METHOD);
        hessianProxyFactory.setOverloadEnabled(isOverloadEnabled);
        //创建连接器工厂为 HttpClientConnectionFactory 对象，即 Apache HttpClient
        String client = url.getParameter(CLIENT_KEY, DEFAULT_HTTP_CLIENT);
        if ("httpclient".equals(client)) {
            HessianConnectionFactory factory = new HttpClientConnectionFactory();
            factory.setHessianProxyFactory(hessianProxyFactory);
            hessianProxyFactory.setConnectionFactory(factory);
        } else if (client != null && client.length() > 0 && !DEFAULT_HTTP_CLIENT.equals(client)) {
            throw new IllegalStateException("Unsupported http protocol client=\"" + client + "\"!");
        } else {
            HessianConnectionFactory factory = new DubboHessianURLConnectionFactory();
            factory.setHessianProxyFactory(hessianProxyFactory);
            hessianProxyFactory.setConnectionFactory(factory);
        }
        // 设置超时时间
        int timeout = url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        hessianProxyFactory.setConnectTimeout(timeout);
        hessianProxyFactory.setReadTimeout(timeout);
        return (T) hessianProxyFactory.create(serviceType, url.setProtocol("http").toJavaURL(), Thread.currentThread().getContextClassLoader());
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof HessianConnectionException) {
            if (e.getCause() != null) {
                Class<?> cls = e.getCause().getClass();
                if (SocketTimeoutException.class.equals(cls)) {
                    return RpcException.TIMEOUT_EXCEPTION;
                }
            }
            return RpcException.NETWORK_EXCEPTION;
        } else if (e instanceof HessianMethodSerializationException) {
            return RpcException.SERIALIZATION_EXCEPTION;
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);
            if (protocolServer != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close hessian server " + protocolServer.getUrl());
                    }
                    protocolServer.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    /***
     * 用于处理消费端请求的处理器
     */
    private class HessianHandler implements HttpHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            String uri = request.getRequestURI();
            HessianSkeleton skeleton = skeletonMap.get(uri);
            if (!"POST".equalsIgnoreCase(request.getMethod())) {//必须是Post情趣
                response.setStatus(500);
            } else {
                // 执行调用
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());

                Enumeration<String> enumeration = request.getHeaderNames();
                while (enumeration.hasMoreElements()) {
                    String key = enumeration.nextElement();
                    if (key.startsWith(DEFAULT_EXCHANGER)) {
                        RpcContext.getContext().setAttachment(key.substring(DEFAULT_EXCHANGER.length()),
                                request.getHeader(key));
                    }
                }

                try {
                    skeleton.invoke(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }

    }

}

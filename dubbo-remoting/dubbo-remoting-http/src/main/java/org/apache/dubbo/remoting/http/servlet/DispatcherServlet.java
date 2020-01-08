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
package org.apache.dubbo.remoting.http.servlet;

import org.apache.dubbo.remoting.http.HttpHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service dispatcher Servlet.
 */
public class DispatcherServlet extends HttpServlet {

    private static final long serialVersionUID = 5766349180380479888L;
    /***
     * 像JettyHttpServer这种服务器在初始化的时候，会维护 handlers，
     *  当这个DispatcherServlet收到前端的请求时，会把不同的端口请求发给不同的HttpHandler处理。
     * key：端口号
     *      eg：9999
     * value：HttpHandler 实例对象
     *      eg：HttpProtocol$InternalHandler
     */
    private static final Map<Integer, HttpHandler> handlers = new ConcurrentHashMap<Integer, HttpHandler>();
    private static DispatcherServlet INSTANCE;

    public DispatcherServlet() {
        DispatcherServlet.INSTANCE = this;
    }

    /***
     * 维护端口和处理器的映射关系
     * @param port 端口号
     *             eg：9999
     * @param processor 处理发往对应端口的请求的处理器
     *                  eg：HttpProtocol$InternalHandler
     */
    public static void addHttpHandler(int port, HttpHandler processor) {
        handlers.put(port, processor);
    }

    public static void removeHttpHandler(int port) {
        handlers.remove(port);
    }

    public static DispatcherServlet getInstance() {
        return INSTANCE;
    }

    /***
     * 接收浏览器的请求，根据端口号交由不同的HttpHandler进行处理
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpHandler handler = handlers.get(request.getLocalPort());
        if (handler == null) {// service not found.
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Service not found.");
        } else {
            handler.handle(request, response);
        }
    }

}

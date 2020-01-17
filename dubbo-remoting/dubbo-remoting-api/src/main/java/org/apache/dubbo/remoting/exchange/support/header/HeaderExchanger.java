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
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporters;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchanger;
import org.apache.dubbo.remoting.transport.DecodeHandler;

/**
 * DefaultMessenger
 * 用于进行请求信息的交换
 *
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    /***
     * 创建一个服务端的客户端连接
     * @param url
     * @param handler
     * @return
     * @throws RemotingException
     * 1、将handler包装成一个HeaderExchangeHandler，提供对接收到的消息进行分类：请求、响应、事件、心跳等消息，然后决定是否需要交给下一个handler处理
     * 2、将HeaderExchangeHandler包装成一个DecodeHandler，对接收到的消息提供了解码功能，解析成Request/Response对象
     * 3、根据服务器的url绑定一个RemotingServer(默认是NettyServer实现)，解析并处理DecodeHandler解码后的消息，在处理接收到的消息的时候，RemotingServer会根据配置交给对应的线程池处理：
     *      all->AllDispatcher->AllChannelHandler
     *      direct->DirectDispatcher->DirectChannelHandler
     *      connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *      connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *      execution->ExecutionDispatcher->ExecutionChannelHandler
     *      message->MessageOnlyDispatcher->MessageOnlyChannelHandler
     * 4、将RemotingServer包装成HeaderExchangeServer进行返回：
     *      添加了一个用于心跳的检查并剔除过期channel的任务，同时添加了channel的管理
     */
    @Override
    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        return new HeaderExchangeClient(Transporters.connect(url, new DecodeHandler(new HeaderExchangeHandler(handler))), true);
    }
    //dubbo://192.168.0.108:20880/org.apache.dubbo.demo.DemoService?anyhost=true&bean.name=org.apache.dubbo.demo.DemoService&bind.ip=192.168.0.108&bind.port=20880&channel.readonly.sent=true&codec=dubbo&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&heartbeat=60000&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=5410&release=&side=provider&timestamp=1575332340328

    /**
     *
     * @param url
     * @param handler
     * @return
     * @throws RemotingException
     *
     * 1、将handler包装成一个HeaderExchangeHandler，提供对接收到的消息进行分类：请求、响应、事件、心跳等消息，然后决定是否需要交给下一个handler处理
     * 2、将HeaderExchangeHandler包装成一个DecodeHandler，对接收到的消息提供了解码功能，解析成Request/Response对象
     * 3、根据服务器的url绑定一个RemotingServer(默认是NettyServer实现)，解析并处理DecodeHandler解码后的消息，在处理接收到的消息的时候，RemotingServer会根据配置交给对应的线程池处理：
     *      all->AllDispatcher->AllChannelHandler
     *      direct->DirectDispatcher->DirectChannelHandler
     *      connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *      connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *      execution->ExecutionDispatcher->ExecutionChannelHandler
     *      message->MessageOnlyDispatcher->MessageOnlyChannelHandler
     * 4、将RemotingServer包装成HeaderExchangeServer进行返回：
     *      添加了一个用于心跳的检查并剔除过期channel的任务，同时添加了channel的管理
     */
    @Override
    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
    }

}

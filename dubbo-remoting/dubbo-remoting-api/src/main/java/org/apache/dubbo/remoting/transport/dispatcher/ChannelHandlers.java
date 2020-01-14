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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Dispatcher;
import org.apache.dubbo.remoting.exchange.support.header.HeartbeatHandler;
import org.apache.dubbo.remoting.transport.MultiMessageHandler;

/***
 * 通道处理器工厂
 */
public class ChannelHandlers {

    private static ChannelHandlers INSTANCE = new ChannelHandlers();

    protected ChannelHandlers() {
    }

    /**
     * 根据url中的dispather配置获得相应的ChannelHandler
     * @param handler
     * @param url
     * @return
     *  all->AllDispatcher->AllChannelHandler
     *  direct->DirectDispatcher->DirectChannelHandler
     *  connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *  connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *  execution->ExecutionDispatcher->ExecutionChannelHandler
     *  message->MessageOnlyDispatcher->MessageOnlyChannelHandler
     */
    public static ChannelHandler wrap(ChannelHandler handler, URL url) {
        return ChannelHandlers.getInstance().wrapInternal(handler, url);
    }

    protected static ChannelHandlers getInstance() {
        return INSTANCE;
    }

    static void setTestingChannelHandlers(ChannelHandlers instance) {
        INSTANCE = instance;
    }

    /***
     *
     * @param handler
     * @param url
     * @return
     * 1、根据 url 中的 dispatcher属性初始化一个Dispatcher实现类对象
     * 2、根据不同的 Dispatcher 实现类创建一个对应的ChannelHandler
     *  all->AllDispatcher->AllChannelHandler
     *  direct->DirectDispatcher->DirectChannelHandler
     *  connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *  connection->ConnectionOrderedDispatcher->ConnectionOrderedChannelHandler
     *  execution->ExecutionDispatcher->ExecutionChannelHandler
     *  message->MessageOnlyDispatcher->MessageOnlyChannelHandler
     * 3、将ChannelHandler包装成一个HeartbeatHandler
     * 4、将HeartbeatHandler包装成MultiMessageHandler
     */
    protected ChannelHandler wrapInternal(ChannelHandler handler, URL url) {
        return new MultiMessageHandler(new HeartbeatHandler(ExtensionLoader.getExtensionLoader(Dispatcher.class)
                .getAdaptiveExtension().dispatch(handler, url)));
    }
}

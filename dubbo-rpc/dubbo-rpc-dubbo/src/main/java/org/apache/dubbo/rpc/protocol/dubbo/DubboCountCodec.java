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

package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.MultiMessage;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.RpcInvocation;

import java.io.IOException;

import static org.apache.dubbo.rpc.Constants.INPUT_KEY;
import static org.apache.dubbo.rpc.Constants.OUTPUT_KEY;

/**
 * 实现 Codec2 接口，支持多消息的编解码器。
 */
public final class DubboCountCodec implements Codec2 {
    /**
     * 编解码器
     */
    private DubboCodec codec = new DubboCodec();

    /***
     * 编码
     * @param channel
     * @param buffer
     * @param msg Request [id=0, version=2.0.2, twoway=true, event=false, broken=false, data=RpcInvocation [methodName=sayHello, parameterTypes=[class java.lang.String], arguments=[zjn], attachments={path=org.apache.dubbo.demo.StubService, interface=org.apache.dubbo.demo.StubService, version=0.0.0}]]
     * @throws IOException
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        codec.encode(channel, buffer, msg);
    }

    /***
     * 解码
     * @param channel
     * @param buffer
     * @return
     * @throws IOException
     * 1、记录当前读位置
     * 2、创建 MultiMessage 对象
     * 3、解码并校验读取内容，同时返回解析到的消息
     *
     * 注意：
     *      支持读取多消息的解析
     *      记录每条消息的长度用于监控
     */
    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int save = buffer.readerIndex();// 记录当前读位置
        MultiMessage result = MultiMessage.create();//创建一个复合消息
        do {
            // 解码
            Object obj = codec.decode(channel, buffer);
            if (Codec2.DecodeResult.NEED_MORE_INPUT == obj) {//输入不够，重置读进度
                buffer.readerIndex(save);
                break;
            } else {// 解析到消息
                result.addMessage(obj);// 添加结果消息
                logMessageLength(obj, buffer.readerIndex() - save);// 记录消息长度到隐式参数集合，用于 MonitorFilter 监控
                save = buffer.readerIndex();// 记录当前读位置
            }
        } while (true);
        // 需要更多的输入
        if (result.isEmpty()) {
            return Codec2.DecodeResult.NEED_MORE_INPUT;
        }
        // 返回解析到的消息
        if (result.size() == 1) {
            return result.get(0);
        }
        return result;
    }

    /***
     * 记录每条消息的长度
     * @param result
     * @param bytes
     * 记录消息长度到隐式参数集合
     */
    private void logMessageLength(Object result, int bytes) {
        if (bytes <= 0) {
            return;
        }
        if (result instanceof Request) {
            try {
                ((RpcInvocation) ((Request) result).getData()).setAttachment(INPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        } else if (result instanceof Response) {
            try {
                ((AppResponse) ((Response) result).getResult()).setAttachment(OUTPUT_KEY, String.valueOf(bytes));
            } catch (Throwable e) {
                /* ignore */
            }
        }
    }

}

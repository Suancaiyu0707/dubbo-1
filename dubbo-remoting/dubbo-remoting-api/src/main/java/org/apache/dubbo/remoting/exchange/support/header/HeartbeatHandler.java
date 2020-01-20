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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.transport.AbstractChannelHandlerDelegate;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/***
 * 用于处理心跳消息，记录最新的读和写的时间
 */
public class HeartbeatHandler extends AbstractChannelHandlerDelegate {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatHandler.class);

    public static final String KEY_READ_TIMESTAMP = "READ_TIMESTAMP";

    public static final String KEY_WRITE_TIMESTAMP = "WRITE_TIMESTAMP";

    public HeartbeatHandler(ChannelHandler handler) {
        super(handler);
    }
    /***
     * 记录读/写时间
     */

    @Override
    public void connected(Channel channel) throws RemotingException {
        setReadTimestamp(channel);
        setWriteTimestamp(channel);
        handler.connected(channel);
    }

    /***
     * 断开连接，清理读/写时间
     * @param channel
     * @throws RemotingException
     */
    @Override
    public void disconnected(Channel channel) throws RemotingException {
        clearReadTimestamp(channel);
        clearWriteTimestamp(channel);
        handler.disconnected(channel);
    }

    /***
     * 发送消息，记录写时间
     * @param channel
     * @param message
     * @throws RemotingException
     */
    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        setWriteTimestamp(channel);
        handler.sent(channel, message);
    }

    /***
     * 接收请求消息，记录读时间
     * @param channel
     * @param message
     * @throws RemotingException
     * 1、记录读时间
     * 2、判断消息类型
     *  a、如果是心跳请求信息：
     *      如果是双向的心跳通信，则直接向发送方响应一条心跳的响应消息
     *  b、如果是心跳响应信息，则忽略
     *  c、如果是服务的请求/响应消息，则交给下一个Handler去处理
     */
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        //更新 读取时间：READ_TIMESTAMP
        setReadTimestamp(channel);
        //如果是心跳请求消息
        if (isHeartbeatRequest(message)) {
            Request req = (Request) message;
            if (req.isTwoWay()) {// 如果是双向的心跳通信，则直接向发送方响应一条心跳的响应消息
                Response res = new Response(req.getId(), req.getVersion());
                res.setEvent(HEARTBEAT_EVENT);//设置这是一条心跳时间
                channel.send(res);//发送心跳响应
                if (logger.isInfoEnabled()) {
                    int heartbeat = channel.getUrl().getParameter(Constants.HEARTBEAT_KEY, 0);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Received heartbeat from remote channel " + channel.getRemoteAddress()
                                + ", cause: The channel has no data-transmission exceeds a heartbeat period"
                                + (heartbeat > 0 ? ": " + heartbeat + "ms" : ""));
                    }
                }
            }
            return;
        }
        //如果是心跳响应
        if (isHeartbeatResponse(message)) {//如果是心跳响应信息，则忽略
            if (logger.isDebugEnabled()) {
                logger.debug("Receive heartbeat response in thread " + Thread.currentThread().getName());
            }
            return;
        }
        //正式处理 客户端请求的消息(非心跳消息)
        handler.received(channel, message);
    }

    private void setReadTimestamp(Channel channel) {
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
    }

    private void setWriteTimestamp(Channel channel) {
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
    }

    private void clearReadTimestamp(Channel channel) {
        channel.removeAttribute(KEY_READ_TIMESTAMP);
    }

    private void clearWriteTimestamp(Channel channel) {
        channel.removeAttribute(KEY_WRITE_TIMESTAMP);
    }

    private boolean isHeartbeatRequest(Object message) {
        return message instanceof Request && ((Request) message).isHeartbeat();
    }

    private boolean isHeartbeatResponse(Object message) {
        return message instanceof Response && ((Response) message).isHeartbeat();
    }
}

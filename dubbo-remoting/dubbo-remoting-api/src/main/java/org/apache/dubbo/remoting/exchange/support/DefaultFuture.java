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
package org.apache.dubbo.remoting.exchange.support;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * DefaultFuture.
 * 作为某一次请求的响应的占位符，代表了一次请求
 */
public class DefaultFuture extends CompletableFuture<Object> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);
    /***
     * 【静态变量，所以类的】
     * key：某次请求的请求id
     * value:某次请求使用的通道channel
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<>();
    /***
     * 【静态变量，所以类的】
     * key：某次请求的请求id
     * value:某次请求结果占位符(响应到达时，可根据id找到对应的占位符，并进行回调)
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<>();
    /***
     * 通过一个定时任务，每隔一定时间间隔就扫描所有的future，逐个判断是否超时
     */
    public static final Timer TIME_OUT_TIMER = new HashedWheelTimer(
            new NamedThreadFactory("dubbo-future-timeout", true),
            30,
            TimeUnit.MILLISECONDS);

    /***
     * 某次请求的请求编号
     */
    private final Long id;
    /***
     * 某次请求使用的通道
     */
    private final Channel channel;
    /***
     * 某次请求
     */
    private final Request request;
    /***
     * 某次请求设置的超时时间
     */
    private final int timeout;
    /**
     * 请求创建的开始时间
     */
    private final long start = System.currentTimeMillis();
    /***
     * 请求发送到的时间
     */
    private volatile long sent;
    private Timeout timeoutCheckTask;

    private ExecutorService executor;

    public ExecutorService getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    private DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        this.id = request.getId();
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        // put into waiting map.
        FUTURES.put(id, this);
        CHANNELS.put(id, channel);
    }

    /**
     * 检查占位符代表的请求是否超时
     */
    private static void timeoutCheck(DefaultFuture future) {
        TimeoutCheckTask task = new TimeoutCheckTask(future.getId());
        future.timeoutCheckTask = TIME_OUT_TIMER.newTimeout(task, future.getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * 初始化一个DefaultFuture
     * 1.初始化DefaultFuture
     * 2.超时时间检查
     *
     * @param channel channel
     * @param request the request
     * @param timeout timeout
     * @return a new DefaultFuture
     */
    public static DefaultFuture newFuture(Channel channel, Request request, int timeout, ExecutorService executor) {
        final DefaultFuture future = new DefaultFuture(channel, request, timeout);//初始化一个本地调用的DefaultFuture
        future.setExecutor(executor);//设置当前DefaultFuture对应的线程池
        // timeout check
        timeoutCheck(future);//创建一个针对DefaultFuture超时的任务检查
        return future;
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    /***
     * 判断某个通道下是否还有请求等待响应（也就是是否还有未结束的请求）
     * @param channel
     * @return
     */
    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    /***
     * 使用某个通道发送某次请求
     * @param channel
     * @param request
     */
    public static void sent(Channel channel, Request request) {
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {//记录发送请求的时间
            future.doSent();
        }
    }

    /**
     * close a channel when a channel is inactive
     * directly return the unfinished requests.
     *
     * @param channel channel to close
     */
    /***
     *
     * @param channel
     * 1、遍历DefaultFuture中本地维护的所有的 请求占位符 DefaultFuture对象
     * 2、检查DefaultFuture，如果是采用该channel请求传输的request,如果请求还没响应，则直接返回错误，不等待了
     */
    public static void closeChannel(Channel channel) {
        //遍历DefaultFuture中本地维护的所有的 请求占位符 DefaultFuture对象
        for (Map.Entry<Long, Channel> entry : CHANNELS.entrySet()) {
            //检查DefaultFuture，如果是采用该channel请求传输的request,则全部返回错误
            if (channel.equals(entry.getValue())) {
                DefaultFuture future = getFuture(entry.getKey());
                //如果请求还没响应，则直接返回错误，不等待了
                if (future != null && !future.isDone()) {
                    Response disconnectResponse = new Response(future.getId());
                    disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                    disconnectResponse.setErrorMessage("Channel " +
                            channel +
                            " is inactive. Directly return the unFinished request : " +
                            future.getRequest());
                    DefaultFuture.received(channel, disconnectResponse);
                }
            }
        }
    }

    public static void received(Channel channel, Response response) {
        received(channel, response, false);
    }

    /***
     *
     * @param channel
     * @param response
     * @param timeout
     *开始处理响应消息：
     *   1、根据responseId从内存里获得在之前对应的请求时的占位符DefaultFuture
     *   2、如果DefaultFuture存在，则检查该 DefaultFuture是否已等待超时：
     *      如果请求等待响应超时了，则取消这个DefaultFuture
     *      如果请求等待响应未超时，则交给这个占位符DefaultFuture进行处理接收到的响应。
     *   3、从内存里移除当前的请求等待的占位符缓存
     */
    public static void received(Channel channel, Response response, boolean timeout) {
        try {
            //根据响应id从本地内存里找到对应的占位符并移除
            DefaultFuture future = FUTURES.remove(response.getId());

            if (future != null) {
                //判断是否等待响应超时
                Timeout t = future.timeoutCheckTask;
                if (!timeout) {//如果超时
                    // decrease Time
                    t.cancel();
                }
                future.doReceived(response);
            } else {
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response " + response
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()));
            }
        } finally {
            CHANNELS.remove(response.getId());
        }
    }

    /**
     * 取消某次请求，U会从内存FUTURES里删除请求对应的占位符对象
     * @param mayInterruptIfRunning
     * @return
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Response errorResult = new Response(id);
        errorResult.setStatus(Response.CLIENT_ERROR);
        errorResult.setErrorMessage("request future has been canceled.");
        this.doReceived(errorResult);
        FUTURES.remove(id);
        CHANNELS.remove(id);
        return true;
    }

    public void cancel() {
        this.cancel(true);
    }

    /***
     * 处理接收到的响应结果
     * @param res
     * 1、
     *      如果响应状态码为成功，设置DefaultFuture返回值，并设置Future执行完成
     *      如果响应状态码为超时，设置DefaultFuture返回值为超时异常，并设置Future执行完成
     *      其它状态码，设置DefaultFuture返回值为远程调用异常，并设置Future执行完成
     * 2、如果DefaultFuture由指定的线程池（不是默认的共享吃）执行,请求的线程线程可能还在等待，未了避免无谓的等待，所以需要通知请求方返回（可参考下面的TimeoutCheckTask.run方法）
     */
    private void doReceived(Response res) {
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        //如果响应状态为成功
        if (res.getStatus() == Response.OK) {
            this.complete(res.getResult());
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            this.completeExceptionally(new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage()));
        } else {
            this.completeExceptionally(new RemotingException(channel, res.getErrorMessage()));
        }

        // the result is returning, but the caller thread may still waiting
        // to avoid endless waiting for whatever reason, notify caller thread to return.
        //当响应返回时，请求的线程线程可能还在等待，未了避免无谓的等待，所以需要通知请求方返回
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
            if (threadlessExecutor.isWaiting()) {
                threadlessExecutor.notifyReturn();
            }
        }
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + (logger.isDebugEnabled() ? request : getRequestWithoutData()) + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    private Request getRequestWithoutData() {
        Request newRequest = request;
        newRequest.setData(null);
        return newRequest;
    }

    private static class TimeoutCheckTask implements TimerTask {

        private final Long requestID;

        TimeoutCheckTask(Long requestID) {
            this.requestID = requestID;
        }

        /***
         *
         * @param timeout 是指被指派用于在指定时间后执行指定任务的对象
         *  1、如果某次请求指定了线程池
         *  2、使用指定线程池调度并执行请求任务
         */
        @Override
        public void run(Timeout timeout) {
            DefaultFuture future = DefaultFuture.getFuture(requestID);
            if (future == null || future.isDone()) {
                return;
            }
            if (future.getExecutor() != null) {
                future.getExecutor().execute(() -> {
                    // create exception response.
                    Response timeoutResponse = new Response(future.getId());
                    // set timeout status.
                    timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
                    timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
                    // 处理接收到的响应
                    DefaultFuture.received(future.getChannel(), timeoutResponse, true);
                });
            }
        }
    }
}

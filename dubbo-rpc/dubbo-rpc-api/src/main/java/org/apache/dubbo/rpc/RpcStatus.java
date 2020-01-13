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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)
 *
 * @see org.apache.dubbo.rpc.filter.ActiveLimitFilter
 * @see org.apache.dubbo.rpc.filter.ExecuteLimitFilter
 * @see org.apache.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalanceØ
 */
public class RpcStatus {
    /**
     * 基于服务 URL 维度的 RpcStatus 集合
     *
     * key1：URL
     * key2：方法名
     */
    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String, RpcStatus>();
    /**
     * 基于服务 URL + 方法维度的 RpcStatus 集合
     *
     * key1：URL
     * key2：方法名
     */
    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS = new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();
    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();
    //活跃数，也就是保持链接的请求数
    private final AtomicInteger active = new AtomicInteger();
    //总数
    private final AtomicLong total = new AtomicLong();
    //失败数
    private final AtomicInteger failed = new AtomicInteger();
    //总的调用时间
    private final AtomicLong totalElapsed = new AtomicLong();
    //总调用失败的时长
    private final AtomicLong failedElapsed = new AtomicLong();
    //最大的调用时长
    private final AtomicLong maxElapsed = new AtomicLong();
    //最大调用失败时长
    private final AtomicLong failedMaxElapsed = new AtomicLong();
    //最大调用成功时长
    private final AtomicLong succeededMaxElapsed = new AtomicLong();

    private RpcStatus() {
    }

    /**
     * 获得服务的统计对象 RpcStatus
     * @param url
     * @return status
     * 1、获得服务的uri：protocol://username:password@host:port/org.apache.dubbo.demo.DemoService
     * 2、检查本地内存，获得服务的统计信息 RpcStatus
     * 3、如果本地内存不存在服务的统计对象，则新建一个，不然直接获取
     */
    public static RpcStatus getStatus(URL url) {
        /**
         * protocol://username:password@host:port/org.apache.dubbo.demo.DemoService
         */
        String uri = url.toIdentityString();
        //获得对应服务的统计信息
        RpcStatus status = SERVICE_STATISTICS.get(uri);
        if (status == null) {
            SERVICE_STATISTICS.putIfAbsent(uri, new RpcStatus());
            status = SERVICE_STATISTICS.get(uri);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * 获得服务方法的统计对象 RpcStatus
     * @param url url
     * @param methodName 方法名
     * @return status 状态
     * 1、根据返回 url获得对应uri
     *      protocol://username:password@host:port/path
     * 2、根据uri获得当前客户端对于某个服务的调用统计信息 map
     * 3、根据方法名从map获得指定方法的统计状态 RpcStatus
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        // protocol://username:password@host:port/path
        String uri = url.toIdentityString();
        //根据uri获得uri的方法统计信息,返回一个map
        //key：methodName
        //value：RpcStatus(RpcStatus包含了各种状态的统计信息)
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map == null) {
            METHOD_STATISTICS.putIfAbsent(uri, new ConcurrentHashMap<String, RpcStatus>());
            map = METHOD_STATISTICS.get(uri);
        }
        RpcStatus status = map.get(methodName);
        if (status == null) {
            map.putIfAbsent(methodName, new RpcStatus());
            status = map.get(methodName);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    public static void beginCount(URL url, String methodName) {
        beginCount(url, methodName, Integer.MAX_VALUE);
    }

    /**
     * @param url url
     * @param methodName 统计的方法名
     * @param max 允许的最大并行请求数
     * 1、根据服务，从本地内存获取服务调用状态的统计对象 RpcStatus
     *             protocol://username:password@host:port/org.apache.dubbo.demo.DemoService
     * 2、根据方法，从本地内存获取服务方法调用状态的统计对象 RpcStatus
     *            protocol://username:password@host:port/org.apache.dubbo.demo.DemoService&method=sayHello
     * 2、判断保持链接的请求数是否超过指定的最大值.
     * 3、维护统计信息
     * 4、如果成功通过校验，则返回true，否则返回false
     */
    public static boolean beginCount(URL url, String methodName, int max) {
        max = (max <= 0) ? Integer.MAX_VALUE : max;
        RpcStatus appStatus = getStatus(url);
        RpcStatus methodStatus = getStatus(url, methodName);
        if (methodStatus.active.get() == Integer.MAX_VALUE) {
            return false;
        }
        //判断保持链接的请求数是否超过指定的最大值.
        if (methodStatus.active.incrementAndGet() > max) {
            methodStatus.active.decrementAndGet();
            return false;
        } else {
            appStatus.active.incrementAndGet();
            return true;
        }
    }

    /**
     * @param url
     * @param elapsed
     * @param succeeded
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        endCount(getStatus(url), elapsed, succeeded);
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
        //统计次数
        status.active.decrementAndGet();
        status.total.incrementAndGet();
        status.totalElapsed.addAndGet(elapsed);
        //记录最大时长
        if (status.maxElapsed.get() < elapsed) {
            status.maxElapsed.set(elapsed);
        }
        //如果调用成功了，记录成功的最大调用时长
        if (succeeded) {
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }
        } else {
            status.failed.incrementAndGet();
            status.failedElapsed.addAndGet(elapsed);
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }


}

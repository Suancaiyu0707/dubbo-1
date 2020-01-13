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
package org.apache.dubbo.rpc.filter.tps;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_RATE_KEY;
import static org.apache.dubbo.rpc.Constants.TPS_LIMIT_INTERVAL_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_TPS_LIMIT_INTERVAL;

/**
 * DefaultTPSLimiter is a default implementation for tps filter. It is an in memory based implementation for storing
 * tps information. It internally use
 *
 * @see org.apache.dubbo.rpc.filter.TpsLimitFilter
 */
public class DefaultTPSLimiter implements TPSLimiter {
    /**
     * key：服务名称
     *
     */
    private final ConcurrentMap<String, StatItem> stats = new ConcurrentHashMap<String, StatItem>();

    /***
     *
     * @param url        url
     * @param invocation invocation
     * @return
     * 1、获取服务设置参数tps，默认-1
     * 2、获取限流滑动窗口时间，默认60s
     * 3、根据服务名称获取统计信息对象 StatItem
     * 4、从 StatItem 中尝试获取信号量，获取成功，则返回true，获取失败，则返回false
     */
    @Override
    public boolean isAllowable(URL url, Invocation invocation) {
        int rate = url.getParameter(TPS_LIMIT_RATE_KEY, -1);
        //获取限流滑动窗口时间，默认60s
        long interval = url.getParameter(TPS_LIMIT_INTERVAL_KEY, DEFAULT_TPS_LIMIT_INTERVAL);
        //获得服务名
        String serviceKey = url.getServiceKey();

        if (rate > 0) { //如果rate>0，表示需要限流
            StatItem statItem = stats.get(serviceKey);
            //为每个服务维护一个对应的StatItem
            if (statItem == null) {
                stats.putIfAbsent(serviceKey, new StatItem(serviceKey, rate, interval));
                statItem = stats.get(serviceKey);
            } else {
                //rate or interval has changed, rebuild
                if (statItem.getRate() != rate || statItem.getInterval() != interval) {
                    stats.put(serviceKey, new StatItem(serviceKey, rate, interval));
                    statItem = stats.get(serviceKey);
                }
            }
            //尝试服务的限流
            return statItem.isAllowable();
        } else {//如果不限流的话
            StatItem statItem = stats.get(serviceKey);
            if (statItem != null) {//移除对应的StatItem对象
                stats.remove(serviceKey);
            }
        }

        return true;
    }

}

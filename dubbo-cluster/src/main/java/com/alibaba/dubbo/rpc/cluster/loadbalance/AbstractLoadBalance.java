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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * AbstractLoadBalance
 *
 */
public abstract class AbstractLoadBalance implements LoadBalance {

    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        int ww = (int) ((float) uptime / ((float) warmup / (float) weight));
        return ww < 1 ? 1 : (ww > weight ? weight : ww);
    }

    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (invokers == null || invokers.isEmpty())
            return null;
        if (invokers.size() == 1)
            return invokers.get(0);
        return doSelect(invokers, url, invocation);
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);

    /***
     * 获取提供者服务(服务里的某哥方法)的权重配置
     *      1、获得服务提供者显式配置的权重，如果方法未配置权重，默认是100
     *      2、如果提供者权重大于0，则根据服务器启动的时间来重新换算权重
     *          2-1、获取服务器提供者接口的启动的时间点，默认是0
     *          2-2、计算服务器提供者已启动运行的时间uptime
     *          2-3、获取服务提供者的配置的预热时间warmup，如果未配置，默认是60s
     *          2-4、如果服务提供者的启动时间uptime<预热时间warmup，则表示接口还处于预热阶段，这个时候权重还要重新计算
     *
     * 从这里，我们可以看到，只要跟权重有关的负载均衡算法，都可能涉及到预热功能
     * @param invoker
     * @param invocation
     * @return
     */
    protected int getWeight(Invoker<?> invoker, Invocation invocation) {
        int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT);
        if (weight > 0) {//配置了权重
            //获取提供者启动服务器的时间，默认为0
            long timestamp = invoker.getUrl().getParameter(Constants.REMOTE_TIMESTAMP_KEY, 0L);
            if (timestamp > 0L) {//如果url中配置了提供者启动的时间
                int uptime = (int) (System.currentTimeMillis() - timestamp);//启动到现在经过的时间
                //获得服务提供者的预热的时间warmup，默认是60s
                int warmup = invoker.getUrl().getParameter(Constants.WARMUP_KEY, Constants.DEFAULT_WARMUP);
                if (uptime > 0 && uptime < warmup) {//如果提供者的预热时间还没到，则需要根据提供者破诶值的权重和预热时间重新计算权重
                    weight = calculateWarmupWeight(uptime, warmup, weight);
                }
            }
        }
        return weight;
    }

}
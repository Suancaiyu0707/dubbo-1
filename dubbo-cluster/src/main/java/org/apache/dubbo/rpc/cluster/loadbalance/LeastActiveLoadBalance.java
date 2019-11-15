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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */

/***
 * 最近最少活跃次数
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    /*****
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     * 1、遍历所有的服务提供者，并记录下持有最小的活跃次数的提供者，并记录信息
     *      持有相同的最小的活跃次数的提供者的个数：leastCount。
     *      持有相同的最小的活跃次数的提供者在invokers对应的索引：leastIndexes(维护索引的数组里的元素可能大于leastCount，但是通过leastCount控制遍历的元素)
     *      持有相同的最小的活跃次数的提供者的总的权重：totalWeight
     *      持有相同的最小的活跃次数的提供者的权重是否都是一样：sameWeight
     *
     * 2、
     *      如果 持有相同的最小的活跃次数的提供者的个数为1，则直接返回leastIndexes[0]
     *      如果 持有相同的最小的活跃次数的提供者的个数为大于1，则根据总权重进行随机选择其中一个
     *
     *
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {

        // 获得服务提供者列表
        int length = invokers.size();
        // 初始化 leastActive，用来遍历前面提供者的最小活跃数
        int leastActive = -1;
        // 持有相同的最小活跃次数的提供者的消费者个数，和leastIndexes对应，这样可以控制需要遍历的leastIndexes次数
        int leastCount = 0;
        // 持有最小活跃次数的提供者在 invokers列表中的索引，可能存在多个提供者持有相同的最小活跃次数
        int[] leastIndexes = new int[length];
        // 数组维护每个提供者的权重
        int[] weights = new int[length];
        // 持有相同的最小活跃次数的提供者的总的权重
        int totalWeight = 0;
        // The weight of the first least active invoker
        int firstWeight = 0;
        // 持有相同的最小活跃次数的提供者的权重是否都相等的标记
        boolean sameWeight = true;


        // 遍历所有的提供者
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            //  活动的提供者的活跃次数(也就是被调用的次数)
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // 获得提供者当前权重，默认100
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            //如果它是遍历的第一个服务提供者或当前服务提供者的活动数小于前面遍历的最小活跃数
            if (leastActive == -1
                    || active < leastActive
                    ) {
                // 将当前invoker 的活跃数更新到leastActive，更新保存当前最小活跃数
                leastActive = active;
                // 记录(活跃数=最小活跃数）的提供者的个数，一开始肯定是1
                leastCount = 1;
                // 记录(活跃数=最小活跃数）的提供者在提供者列表中的索引
                leastIndexes[0] = i;
                // 记录(活跃数=最小活跃数）的所有提供者 的总的权重
                totalWeight = afterWarmup;
                // 记录第一个 (活跃数=最小活跃数）的提供者的权重
                firstWeight = afterWarmup;
                //记录标记 当前(活跃数=最小活跃数）的所有提供者的权重是否一样 (这边因为是开始，所以是肯定一样)
                sameWeight = true;
                // 如果 当前提供者的活跃数 就等于前面保存的最小活跃数
            } else if (active == leastActive) {
                // 则记录(活跃数=最小活跃数）的提供者在提供者列表中的索引
                leastIndexes[leastCount++] = i;
                // 计算 当前(活跃数=最小活跃数）的所有提供者 的总的权重
                totalWeight += afterWarmup;
                //记录标记 当前(活跃数=最小活跃数）的所有提供者的权重是否一样
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        //如果只有一个提供者独享最少活跃次数，则直接返回这个提供者
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }
        //如果不止有一个提供者独享最少活跃次数，则要根据权重做负载均衡
        if (!sameWeight && totalWeight > 0) {//这些具有相同active的提供者是否权重不一样
            //根据这几个具有相同的active的提供者的总的权重值生成一个随机数
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            //判断这个权重值落到哪个提供者上
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}

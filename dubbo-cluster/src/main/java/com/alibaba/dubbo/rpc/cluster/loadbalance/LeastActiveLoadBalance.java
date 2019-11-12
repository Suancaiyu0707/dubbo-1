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
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance
 *
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    private final Random random = new Random();

    /*****
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     * @describe
     * <p>
     *     1、循环遍历服务提供者的列表
     *     2、循环服务提供者列表
     *          a、根据提供者url和methodName查看方法的active值，每调用一次，则active增加1
     *          b、找出active最小的值的服务提供者的列表(可能存在几个active一样的提供者)，记录每个提供者的权重，并统计总的权重值totalWeight
     *     3、返回服务提供者url地址
     *          active值最小的提供者只有一个，则直接返回。
     *          active值最小的提供者有多个，则根据totalWeight生成一个随机值，并按顺序检查权重落到哪个服务提供者，从而返回url
     *
     *      总结：
     *          我们可以看到，如果遇到有多个active值一样的提供者，则会随机选中一个。
     *          统计active时候，只跟protocol、host、ip、service、username，跟其它参数无关，某个提供者的url的相关细节配置发生改变，则active不会变，
     *              除非protocol、host、ip、service、username发生了改变
     * </p>
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); //统计服务提供者个数
        int leastActive = -1; // 存在活动的提供者
        int leastCount = 0; // The number of invokers having the same least active value (leastActive)
        int[] leastIndexs = new int[length]; // The index of invokers having the same least active value (leastActive)
        int totalWeight = 0; // The sum of weights
        int firstWeight = 0; // Initial value, used for comparision
        boolean sameWeight = true; // Every invoker has the same weight value?
        for (int i = 0; i < length; i++) {//循环提供者列表，找出活跃次数最小的服务提供者
            Invoker<T> invoker = invokers.get(i);
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive(); // 活动的提供者的活跃次数(也就是被调用的次数)
            //获取请求url的权重
            int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT); // Weight
            //如果循环的是列表的第一个提供者(leastActive == -1 )或者轮询到列表中的某个提供者有有更小的活跃次数
            if (leastActive == -1 || active < leastActive) { // Restart, when find a invoker having smaller least active value.
                leastActive = active; //更新当前最小的活跃次数
                leastCount = 1; // 重新计算leastCount
                leastIndexs[0] = i; // Reset
                totalWeight = weight; // Reset
                firstWeight = weight; // Record the weight the first invoker
                sameWeight = true; // Reset, every invoker has the same weight value?
            } else if (active == leastActive) { // 如果遍历到的活跃次数和最小活跃次数一样。也就是说同时有几个提供者的活跃数都是最小的
                leastIndexs[leastCount++] = i; // 记录该invoker在整个提供者列表中的索引
                totalWeight += weight; // 统计同一活跃数的权重
                // If every invoker has the same weight?
                if (sameWeight && i > 0
                        && weight != firstWeight) {//判断这些活跃数最小的提供者列表是否连权重都是一样的
                    sameWeight = false;
                }
            }
        }
        // assert(leastCount > 0)
        if (leastCount == 1) {//标记只存在一个活跃数最小的提供者，也就是不会出现几个提供者的active值一样小
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexs[0]);
        }
        //如果存在几个提供者，它们的active一样都是最小的
        if (!sameWeight && totalWeight > 0) {//这些具有相同active的提供者是否权重不一样
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            //根据这几个具有相同的active的提供者的总的权重值生成一个随机数
            int offsetWeight = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            //判断这个权重值落到哪个提供者上
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexs[i];
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);
                if (offsetWeight <= 0)
                    return invokers.get(leastIndex);
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}
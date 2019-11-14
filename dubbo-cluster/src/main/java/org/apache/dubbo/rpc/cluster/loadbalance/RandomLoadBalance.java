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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This class select one provider from multiple providers randomly.
 * You can define weights for each provider:
 * If the weights are all the same then it will use random.nextInt(number of invokers).
 * If the weights are different then it will use random.nextInt(w1 + w2 + ... + wn)
 * Note that if the performance of the machine is better than others, you can set a larger weight.
 * If the performance is not so good, you can set a smaller weight.
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    /**
     * Select one invoker between a list using a random criteria
     * @param invokers List of possible invokers
     * @param url URL
     * @param invocation Invocation
     * @param <T>
     * @return The selected invoker
     */
    /***
     * 采用随机的策略获得服务提供者(这个随机数是根据总的权重来生成的，所以这个算法在获得权重时也是存在预热的)
     *      1、轮询服务的每个提供者，并统计服务下所有提供者总的权重
     *      2、判断所有的提供者的权重是否一致来获取服务提供者
     *          2-1、一致，则根据服务列表的长度随机生成一个随机数
     *          2-2、不一致，则根据总的权重生成一个随机数，并判断权重的落点（落到哪个提供者的权重范围内）
     *      3、返回服务提供者
     *
     * 这个算法的问题在于，太过依赖随机数的生成，无法根据不同提供者当前的忙碌状态去调整相应的权重
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     *
     *
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获取invoker列表的大小，也就是invoker提供者个数
        int length = invokers.size();
        // 该标计用来标记所有的服务提供者的权重是否一致
        boolean sameWeight = true;
        // 通过一个数组维护每个提供者的权重
        int[] weights = new int[length];
        // 计算第一个服务提供者的权重
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // 开始遍历提供所有提供者的权重
        int totalWeight = firstWeight;
        for (int i = 1; i < length; i++) {
            //遍历计算每个提供者的权重
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            weights[i] = weight;
            // 判断权重是否一样，如果不一样，则把sameWeight设置为false
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        //如果总的权重大于0，且每个提供者的权重不一致，则重新计算权重
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            //根据总的权重获得随机数
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            //遍历所有的服务提供者
            for (int i = 0; i < length; i++) {
                offset -= weights[i];//计算这个随机数在对应提供者的数组范围内
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}

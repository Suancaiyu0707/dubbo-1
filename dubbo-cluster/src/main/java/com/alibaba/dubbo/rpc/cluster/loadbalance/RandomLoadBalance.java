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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.List;
import java.util.Random;

/**
 * random load balance.
 *
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    private final Random random = new Random();

    /***
     * 采用随机的策略获得服务提供者(这个随机数是根据总的权重来生成的，所以这个算法在获得权重时也是存在预热的)
     *      1、轮询服务的每个提供者，并统计服务下所有提供者总的权重
     *      2、判断所有的提供者的权重是否一致来获取服务提供者
     *          2-1、一致，则根据服务列表的长度随机生成一个随机数
     *          2-2、不一致，则根据总的权重生成一个随机数，并判断权重的落点
     *      3、返回服务提供者
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // 统计服务提供者的个数
        int totalWeight = 0; //统计服务总的权重
        boolean sameWeight = true; // 标志是否所有的提供者的权重都是一样的
        for (int i = 0; i < length; i++) {//1、轮询服务的每个提供者，并统计服务下所有提供者总的权重
            int weight = getWeight(invokers.get(i), invocation);
            totalWeight += weight; // Sum
            if (sameWeight && i > 0
                    && weight != getWeight(invokers.get(i - 1), invocation)) {//如果当前提供者的weight!=前一个提供者的weight
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) {//如果服务器的提供者权重不一样
            // 根据统计的总的权重生成一个随机数
            int offset = random.nextInt(totalWeight);
            // 根据随机数返回服务提供者.
            for (int i = 0; i < length; i++) {
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(random.nextInt(length));
    }

}
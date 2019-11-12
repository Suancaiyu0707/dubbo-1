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
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Round robin load balance.
 *轮询的负载均衡算法
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "roundrobin";

    private final ConcurrentMap<String, AtomicPositiveInteger> sequences = new ConcurrentHashMap<String, AtomicPositiveInteger>();

    /***
     * 根据每个方法的权重进行轮询。
     *      在计算提供者权重的时候会有一个预热的过程，也就是如果一个提供的启动时间还没有完成预热，则这个提供者的权重会根据时间来重新计算
     *
     *      1、查询某个服务接口每个提供者的权重，并统计某个服务所有的提供者总的权重weightSum
     *      2、读取服务所有提供者当前被调用的次数currentSequence
     *      3、计算当前调用该落到哪个权重上，利用currentSequence%weightSum
     *          3-1、如果所有权重都一样，则直接根据提供者列表轮询
     *          3-2、如果存在权重不一样的提供者，则需要计算权重落到哪一个提供者上
     *      4、返回该服务提供者的代理对象
     * @param invokers ：包含某个服务的所有提供者
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        //获取轮询的key名称 group/interfaceName:version/methodName
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        int length = invokers.size(); // 获取提供者个数
        int maxWeight = 0; // 所有某个服务所有提供者中最大的权重
        int minWeight = Integer.MAX_VALUE; // // 所有某个服务所有提供者中最小的权重
        final LinkedHashMap<Invoker<T>, IntegerWrapper> invokerToWeightMap = new LinkedHashMap<Invoker<T>, IntegerWrapper>();//临时缓存
        int weightSum = 0;//统计为所有提供者分配的总的权重
        for (int i = 0; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);//获得每个提供者的权重
            maxWeight = Math.max(maxWeight, weight); // Choose the maximum weight
            minWeight = Math.min(minWeight, weight); // Choose the minimum weight
            if (weight > 0) {
                invokerToWeightMap.put(invokers.get(i), new IntegerWrapper(weight));//临时缓存每个提供者的权重
                weightSum += weight;//统计该服务的所有提供者总的权重
            }
        }
        //获得这个服务所有的提供者总的被调用的次数
        AtomicPositiveInteger sequence = sequences.get(key);
        if (sequence == null) {
            sequences.putIfAbsent(key, new AtomicPositiveInteger());
            sequence = sequences.get(key);
        }
        int currentSequence = sequence.getAndIncrement();//递增这个服务所有的提供者总的被调用的次数，从0开始递增
        if (maxWeight > 0 && minWeight < maxWeight) {//表示某个服务的每个提供者的权重存在不一样
            int mod = currentSequence % weightSum;//调用次数除以总的权重，计算本次调用该落到哪个权重上
            //只是为了轮询权重 写的这么复杂
            for (int i = 0; i < maxWeight; i++) {
                //轮询服务的所有提供者
                //invokerToWeightMap:{service1=weight1,service2=weight2,service3=weight3}
                for (Map.Entry<Invoker<T>, IntegerWrapper> each : invokerToWeightMap.entrySet()) {
                    final Invoker<T> k = each.getKey();
                    final IntegerWrapper v = each.getValue();//获得每个集合元素保存的权重值
                    if (mod == 0 && v.getValue() > 0) {//如果mod=0  v.getValue<0,表示本次调用不是这个invoker里，就会检查下一个invoker
                        return k;
                    }
                    if (v.getValue() > 0) {
                        v.decrement();//权重值减1
                        mod--;
                    }
                }

            }
//            int sum1 =0 ;  个人感觉上面那段循环权重可用这个替换
//            for (Map.Entry<Invoker<T>, IntegerWrapper> each : invokerToWeightMap.entrySet()) {
//                final Invoker<T> k = each.getKey();
//                final IntegerWrapper v = each.getValue();//获得每个集合元素保存的权重值
//                if(sum1+v.getValue()>currentSequence){
//                    return k;
//                    break;
//                }
//                sum1+=v.getValue();
//            }
        }
        // 如果所有的服务提供者的权重都一样，则就根据提供者列表轮询就好了，不需要计算权重
        return invokers.get(currentSequence % length);
    }

    private static final class IntegerWrapper {
        private int value;

        public IntegerWrapper(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }

        public void decrement() {
            this.value--;
        }
    }

}
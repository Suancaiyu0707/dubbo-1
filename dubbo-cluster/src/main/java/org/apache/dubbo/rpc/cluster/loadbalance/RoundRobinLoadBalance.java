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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 * 轮询负载均衡
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";
    
    private static final int RECYCLE_PERIOD = 60000;
    
    protected static class WeightedRoundRobin {
        //某个服务的某个提供者的配置的权重
        private int weight;
        //每轮询到一次，就加一次当前的权重
        private AtomicLong current = new AtomicLong(0);
        private long lastUpdate;
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    /***
     *  key：接口信息
     *  value：
     *      key：某个服务的某个提供者的信息
     *      value:：某个服务的某个提供者的配置权重
     */
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    /***
     * 根据服务提供者信息，从本地获得对应的轮询权重信息（包含每个接口的每个提供者的轮询次数）
     * @param invokers
     * @param invocation
     * @param <T>
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    /******
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     * 1、从本地内存methodWeightMap里获得对应服务的提供者列表：ConcurrentMap<String, WeightedRoundRobin> map
     * 2、遍历服务的所有提供者列表：
     *      当一个提供者被选中时，它内部的current会累加当前的权重值，然后比较当前累计的权重值跟之前的比
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        //获取轮询的key名称： group/interfaceName:version/methodName
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        //从本地内存中获得对应的接口的不同提供者的 轮询分配的信息
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        //所有提供者配置爹总权重
        int totalWeight = 0;
        //固定的-2^63
        long maxCurrent = Long.MIN_VALUE;//某个服务中所有提供者中当前轮询次数最少的
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        //遍历服务提供者，遍历每一个提供者对应的weightedRoundRobin信息
        for (Invoker<T> invoker : invokers) {
            //获得某个提供者的调用信息
            String identifyString = invoker.getUrl().toIdentityString();
            //获得某个提供者的当前轮询权重信息
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            //获得某个提供者配置的服务权重
            int weight = getWeight(invoker, invocation);
            //为对应服务提供者初始化一个weightedRoundRobin权重信息
            if (weightedRoundRobin == null) {
                weightedRoundRobin = new WeightedRoundRobin();
                weightedRoundRobin.setWeight(weight);
                map.putIfAbsent(identifyString, weightedRoundRobin);
            }
            //如果服务提供者的权重发生改变了，要及时更新(预热阶段)
            //TODO 这段代码有并发问题，如果两个线程同时修改的话，后面的有可能覆盖调前面的
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            //cur = current+weightedRoundRobin.getWeight()
            //获得当前服务提供者最新的已累加的权重值
            long cur = weightedRoundRobin.current.addAndGet(weight);
            //更新当前提供者被调用的时间
            weightedRoundRobin.setLastUpdate(now);
            //每轮询一个提供者，这边都会做一次比较。所以如果服务提供者的历史总权重>前一个的提供者，
            // 则更新最新的被挑选的提供者者信息，修改当前提供者未目标提供者
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight;
        }
        //更新修改服务提供者列表
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<>(map);
                    newMap.entrySet().removeIf(item -> {
                        //如果当前时间-上次更新时间>60s，则直接移除
                        return now - item.getValue().getLastUpdate() > RECYCLE_PERIOD;
                    });
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        if (selectedInvoker != null) {
            //如果当前提供者被选中，则当前的累加权重-所有提供者的总权重
            selectedWRR.current.addAndGet(-1 * totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}

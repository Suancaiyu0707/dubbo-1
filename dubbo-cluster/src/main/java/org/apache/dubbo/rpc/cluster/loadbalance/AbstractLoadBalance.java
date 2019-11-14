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
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_WARMUP;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_WEIGHT;
import static org.apache.dubbo.rpc.cluster.Constants.WARMUP_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WEIGHT_KEY;

/**
 * AbstractLoadBalance
 */
public abstract class AbstractLoadBalance implements LoadBalance {
    /**
     * Calculate the weight according to the uptime proportion of warmup time
     * the new weight will be within 1(inclusive) to weight(inclusive)
     *
     * @param uptime the uptime in milliseconds
     * @param warmup the warmup time in milliseconds
     * @param weight the weight of an invoker
     * @return weight which takes warmup into account
     */
    static int calculateWarmupWeight(int uptime, int warmup, int weight) {
        int ww = (int) ( uptime / ((float) warmup / weight));
        return ww < 1 ? 1 : (Math.min(ww, weight));
    }

    /***
     * 根据url信息选择一个invoker实例进行调用
     * @param invokers   invokers.
     * @param url        refer url
     * @param invocation invocation.
     * @param <T>
     * @return
     */
    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        //如果服务提供者就一个的话，直接返回
        if (invokers.size() == 1) {
            return invokers.get(0);
        }
        //根据具体的实现类选择服务实例
        return doSelect(invokers, url, invocation);
    }

    protected abstract <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation);


    /**
     * Get the weight of the invoker's invocation which takes warmup time into account
     * if the uptime is within the warmup time, the weight will be reduce proportionally
     *
     * @param invoker    the invoker
     * @param invocation the invocation of this invoker
     * @return weight
     */
    /***
     * 获取提供者服务(服务里的某个方法)的权重配置
     *      1、获得服务提供者显式配置的权重，如果方法未配置权重，默认是100
     *      2、如果提供者权重大于0，则根据服务器启动的时间来重新换算权重
     *          2-1、获取服务器提供者接口的启动的时间点，默认是0
     *          2-2、计算服务器提供者已启动运行的时间uptime
     *          2-3、获取服务提供者的配置的预热时间warmup，如果未配置，默认是10分钟
     *          2-4、如果服务提供者的启动时间uptime<预热时间warmup，则表示接口还处于预热阶段，这个时候权重还要重新计算
     *
     * 从这里，我们可以看到，只要跟权重有关的负载均衡算法，都可能涉及到预热功能
     * @param invoker
     * @param invocation
     * @return
     */
    int getWeight(Invoker<?> invoker, Invocation invocation) {
        int weight = 0;
        //获得调用者的url
        URL url = invoker.getUrl();
        // Multiple registry scenario, load balance among multiple registries.
        //如果有多个服务提供者的情况下，在多个服务提供者之间负载均衡
        if (url.getServiceInterface().equals("org.apache.dubbo.registry.RegistryService")) {
            //url中解析registry.weight属性， 没设置的话，默认是100
            weight = url.getParameter(REGISTRY_KEY + "." + WEIGHT_KEY, DEFAULT_WEIGHT);
        } else {
            //获得调用方法的权重属性weight，没有的话，默认是100
            weight = url.getMethodParameter(invocation.getMethodName(), WEIGHT_KEY, DEFAULT_WEIGHT);
            if (weight > 0) {
                //获得启动时间
                long timestamp = invoker.getUrl().getParameter(TIMESTAMP_KEY, 0L);
                if (timestamp > 0L) {
                    //获得服务提供者已启动的时间
                    long uptime = System.currentTimeMillis() - timestamp;
                    if (uptime < 0) {//如果小于0，则权重为最小值1
                        return 1;
                    }
                    //判断预热时间，默认是10 * 60 * 1000 ，也就是10分钟
                    int warmup = invoker.getUrl().getParameter(WARMUP_KEY, DEFAULT_WARMUP);
                    //如果服务器到目前启动的时间小于预热时间，那按照已预热的时间占比，计算当前的权重
                    if (uptime > 0 && uptime < warmup) {
                        weight = calculateWarmupWeight((int)uptime, warmup, weight);
                    }
                }
            }
        }
        return Math.max(weight, 0);
    }
}

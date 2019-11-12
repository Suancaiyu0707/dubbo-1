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

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ConsistentHashLoadBalance
 *  默认是通过接口、版本号和方法来作为key，并映射到ConsistentHashSelector，跟ip地址无关
 *  ConsistentHashSelector包含所有节点的哈希情况
 *  每个服务接口方法的提供者列表共同映射到一个identityHashCode
 *  1、会为每个服务提供者创建160个虚拟的节点。
 *  2、通过treeMap维护一个包含所有虚拟节点的集合
 *  3、可以通过hash.arguments来指定参与哈希的参数索引
 *  4、定位还节点时，会把参数值也作为计算哈希值，从而找到最近的哈希节点（那如果一个方法没有参数呢？那每次计算出来的哈希值不都一样吗）
 *
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获取调用方法名
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        //生产提供者列表的哈希code
        int identityHashCode = System.identityHashCode(invokers);//计算哈希值identityHashCode，它只跟服务接口方法列表有关，和单个提供者无关
        // 以调用方法名为key,获取一致性hash选择器
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || selector.identityHashCode != identityHashCode) {
            // 创建ConsistentHashSelector时会生成所有虚拟结点
            selectors.put(key, new ConsistentHashSelector<T>(invokers, invocation.getMethodName(), identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        //
        return selector.select(invocation);
    }

    /***
     * 对于哈希一致性的负载均衡方法，主要是通过内存为每一个接口方法维护一个树结构的TreeMap，treeMap默认为每个提供者接口方法创建160个虚拟节点
     *     treeMap: <hashCode,Invoker>
     *     在采用哈希索引的时候，默认为每个invoker映射160个虚拟节点
     * 集群中每个接口方法都映射一个ConsistentHashSelector，
     *
     * @param <T>
     */
    private static final class ConsistentHashSelector<T> {
        // 虚拟结点
        private final TreeMap<Long, Invoker<T>> virtualInvokers;
        // 副本数,默认是160。可以在method里通过hash.nodes来配置
        private final int replicaNumber;
        //方法提供者列表的哈希code
        private final int identityHashCode;
        // 参数哈希的参数索引数组
        private final int[] argumentIndex;

        /***
         * 为每个提供者创建160个虚拟节点
         * @param invokers
         * @param methodName
         * @param identityHashCode
         */
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            // 创建TreeMap 来保存结点
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            // 生成调用结点HashCode
            this.identityHashCode = identityHashCode;//唯一的哈希值
            // 获取Url
            // dubbo://169.254.90.37:20880/service.DemoService?anyhost=true&application=srcAnalysisClient&check=false&dubbo=2.8.4
            // &generic=false&interface=service.DemoService&loadbalance=consistenthash
            // &methods=sayHello,retMap&pid=14648&sayHello.timeout=20000&side=consumer&timestamp=1493522325563
            URL url = invokers.get(0).getUrl();
            //获得配置的虚拟节点个数，可以通过hash.nodes来设置，默认是160个
            this.replicaNumber = url.getMethodParameter(methodName, "hash.nodes", 160);
            // 获取需要进行hash的参数数组索引，默认只对第一个参数进行hash
            String[] index = Constants.COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, "hash.arguments", "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 创建虚拟结点
            // 对每个invoker生成replicaNumber个虚拟结点，并存放于TreeMap中
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 根据md5算法为每4个结点生成一个消息摘要，摘要长为16字节128位。
                    byte[] digest = md5(address + i);
                    // 随后将128位分为4部分，0-31,32-63,64-95,95-128，并生成4个32位数，存于long中，long的高32位都为0
                    // 并作为虚拟结点的key。
                    for (int h = 0; h < 4; h++) {
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }
        // 选择结点
        public Invoker<T> select(Invocation invocation) {
            // 根据调用参数来生成Key
            //根据接口方法和参数值来获得哈希值，所以不同的参数值会哈希到不同的哈希节点
            String key = toKey(invocation.getArguments());
            // 根据这个参数生成消息摘要
            byte[] digest = md5(key);
            //调用hash(digest, 0)，将消息摘要转换为hashCode，这里仅取0-31位来生成HashCode
            //调用sekectForKey方法选择结点。
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            // 由于hash.arguments没有进行配置，因为只取方法的第1个参数作为key
            for (int i : argumentIndex) {

                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.tailMap(hash, true).firstEntry();
        	if (entry == null) {
        		entry = virtualInvokers.firstEntry();
        	}
        	return entry.getValue();
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes;
            try {
                bytes = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.update(bytes);
            return md5.digest();
        }

    }

}

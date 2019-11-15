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
import org.apache.dubbo.rpc.support.RpcUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;


/**
 * ConsistentHashLoadBalance
 *  默认是通过接口、版本号和方法来作为key，并映射到ConsistentHashSelector，跟ip地址无关
 *  ConsistentHashSelector包含所有节点的哈希情况
 *  每个服务接口方法的提供者列表共同映射到一个identityHashCode
 *  1、会为每个服务提供者创建160个虚拟的节点。
 *  2、通过treeMap维护一个包含所有虚拟节点的集合
 *  3、可以通过hash.arguments来指定参与哈希的参数索引
 *  4、定位节点时，会把参数值也作为计算哈希值，从而找到最近的哈希节点（那如果一个方法没有参数呢？那每次计算出来的哈希值不都一样吗）
 *
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    /**
     * Hash nodes name
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * Hash arguments name
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        //获得方法吗
        String methodName = RpcUtils.getMethodName(invocation);
        //默认是通过接口、版本号和方法来作为key，并映射到ConsistentHashSelector，跟ip地址无关
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        //计算哈希值
        int identityHashCode = System.identityHashCode(invokers);
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || //如果selectors集合中不存在对应的 接口的一致性哈希选择器
                selector.identityHashCode != identityHashCode) //或者哈希值变了，也就是这个提供者发生了改变，
        {   //新建并更新这个 ConsistentHashSelector
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        return selector.select(invocation);
    }

    private static final class ConsistentHashSelector<T> {

        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        private final int replicaNumber;

        private final int identityHashCode;

        private final int[] argumentIndex;
        //在客户端调用时，只会对请求参数做MD5,虽然此时得到的MD5的值不一定能对应到TreeMap中的一个key，因为每次的请求参数都不相同。
        //但是由于TreeMap是有序的树形结构，所以我们可以调用TreeMap的ceilingEntry方法，用于返回一个至少大于或等于当前给定key的entry。
        //从而达到顺时针往前找的效果，如果找不到则使用firstEntry返回的第一个节点
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            //创建一个维护虚拟节点的 treeMap
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            // 获取Url
            // dubbo://169.254.90.37:20880/service.DemoService?anyhost=true&application=srcAnalysisClient&check=false&dubbo=2.8.4
            // &generic=false&interface=service.DemoService&loadbalance=consistenthash
            // &methods=sayHello,retMap&pid=14648&sayHello.timeout=20000&side=consumer&timestamp=1493522325563
            URL url = invokers.get(0).getUrl();
            // 副本数,默认是160。可以在method里通过hash.nodes来配置
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);
            // 获取需要进行hash的参数数组索引，默认只对第一个参数进行hash。可用hash.arguments指定索引
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 创建虚拟结点
            // 为每个invoker生成replicaNumber个虚拟结点，并存放于TreeMap中
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                //针对每个invoker，将它的副本数，每四个分成一份，共享一个摘要的128位
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
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
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
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}

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

package com.alibaba.dubbo.registry;

import com.alibaba.dubbo.common.URL;

import java.util.List;
import java.util.stream.Collectors;

@Deprecated
public interface NotifyListener {
    /**
     * 当收到服务变更通知时触发。
     * <p>
     * 通知需处理契约：<br>
     * 1. 总是以服务接口和数据类型为维度全量通知，即不会通知一个服务的同类型的部分数据，用户不需要对比上一次通知结果。<br>
     * 2. 订阅时的第一次通知，必须是一个服务的所有类型数据的全量通知。<br>
     * 3. 中途变更时，允许不同类型的数据分开通知，比如：providers, consumers, routers, overrides，允许只通知其中一种类型，但该类型的数据必须是全量的，不是增量的。<br>
     * 4. 如果一种类型的数据为空，需通知一个empty协议并带category参数的标识性URL数据。<br>
     * 5. 通知者(即注册中心实现)需保证通知的顺序，比如：单线程推送，队列串行化，带版本对比。<br>
     *
     * @param urls 已注册信息列表，总不为空
     */
    void notify(List<URL> urls);

    class CompatibleNotifyListener implements NotifyListener {

        private org.apache.dubbo.registry.NotifyListener listener;

        public CompatibleNotifyListener(org.apache.dubbo.registry.NotifyListener listener) {
            this.listener = listener;
        }

        @Override
        public void notify(List<URL> urls) {
            if (listener != null) {
                listener.notify(urls.stream().map(url -> url.getOriginalURL()).collect(Collectors.toList()));
            }
        }
    }

    class ReverseCompatibleNotifyListener implements org.apache.dubbo.registry.NotifyListener {

        private NotifyListener listener;

        public ReverseCompatibleNotifyListener(NotifyListener listener) {
            this.listener = listener;
        }

        @Override
        public void notify(List<org.apache.dubbo.common.URL> urls) {
            if (listener != null) {
                listener.notify(urls.stream().map(url -> new URL(url)).collect(Collectors.toList()));
            }
        }
    }
}

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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;

/**
 * Configurator. (SPI, Prototype, ThreadSafe)
 *
 */
public interface Configurator extends Comparable<Configurator> {

    /**
     * Get the configurator url.
     *
     * @return configurator url.
     */
    URL getUrl();

    /**
     * Configure the provider url.
     *
     * @param url - old provider url.
     * @return new provider url.
     */
    URL configure(URL url);


    /**
     * Convert override urls to map for use when re-refer. Send all rules every time, the urls will be reassembled and
     * calculated
     *
     * URL contract:
     * <ol>
     * <li>override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules
     * (all of the providers take effect)</li>
     * <li>override://ip:port...?anyhost=false Special rules (only for a certain provider)</li>
     * <li>override:// rule is not supported... ,needs to be calculated by registry itself</li>
     * <li>override://0.0.0.0/ without parameters means clearing the override</li>
     * </ol>
     *
     * @param urls URL list to convert
     * @return converted configurator list
     */
    /***
     *
     * @param urls
     * @return
     *
     * 处理变化的 Configurator
     */
    static Optional<List<Configurator>> toConfigurators(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {//empty://192.168.44.56:20880/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=192.168.44.56&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=22219&release=&revision=1.0.0&side=provider&timestamp=1576065463228&version=1.0.0
            return Optional.empty();
        }

        ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .getAdaptiveExtension();

        List<Configurator> configurators = new ArrayList<>(urls.size());
        for (URL url : urls) {
            //只有有任何一个url的协议是empty，则直接清空configurators，且直接更新本地缓存configurators=empty
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            //将url中的key=value转换成map的元素
            Map<String, String> override = new HashMap<>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            override.remove(ANYHOST_KEY);
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }
            //如果当前 url不为空，则添加到 configurators
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        Collections.sort(configurators);
        return Optional.of(configurators);
    }

    /**
     * Sort by host, then by priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     */
    @Override
    default int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());
        // host is the same, sort by priority
        if (ipCompare == 0) {
            int i = getUrl().getParameter(PRIORITY_KEY, 0);
            int j = o.getUrl().getParameter(PRIORITY_KEY, 0);
            return Integer.compare(i, j);
        } else {
            return ipCompare;
        }
    }
}

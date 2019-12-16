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
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.OVERRIDE_PROVIDERS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    /***
     *
     * @param url - old provider url.
     * @return
     * 1、如果configuratorUrl的enabled=false或者configuratorUrl的host是空的，又或者url是空的，则跳过直接返回
     * 2、获得 configuratorUrl 对应的版本号 configVersion
     *      a、如果configVersion不为空。判断当前要被修改的url是服务提供者还是消费者
     *          检查要被修改的url和当前configurator作用的角色是否一致(consumer或provider)
     *      b、将configurators里的对应的url的配置参数合并到当前url里
     *
     */
    @Override
    public URL configure(URL url) {
        // If override url is not enabled or is invalid, just return.
        if (!configuratorUrl.getParameter(ENABLED_KEY, true) //如果enabled=false
                || configuratorUrl.getHost() == null //或者host==null
                || url == null //或者url=null
                || url.getHost() == null) {
            return url;
        }
        /**
         * This if branch is created since 2.7.0.
         */
        String apiVersion = configuratorUrl.getParameter(CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            String currentSide = url.getParameter(SIDE_KEY);
            String configuratorSide = configuratorUrl.getParameter(SIDE_KEY);
            if (currentSide.equals(configuratorSide) && CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (currentSide.equals(configuratorSide) && PROVIDER.equals(configuratorSide) && url.getPort() == configuratorUrl.getPort()) {
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /**
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        return url;
    }

    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {// override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0
            // 1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore;
            // 2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider
            if (url.getParameter(SIDE_KEY, PROVIDER).equals(CONSUMER)) {
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.
            } else if (url.getParameter(SIDE_KEY, CONSUMER).equals(PROVIDER)) {
                return configureIfMatch(ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    /***
     *
     * @param host
     * @param url 待处理的url
     * @return
     * 1、如果 configurator 作用的ip是所有的*，或者和目标url里的host一致，则表示当前configurator是为当前url配置的
     * 2、检查当前configurator与待处理的url的地址是否匹配
     *      从configurator中获取application名
     *      从url中获取application名
     *      如果configurator的application名为空，或者configurator中的application的名字是*，又或者configurator的application名和url中application名相同，则合并url和configurator的参数
     *      conditionKeys是用来指定在合并参数时要忽略的参数
     *
     */
    private URL configureIfMatch(String host, URL url) {
        if (ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            //获得 providerAddresses 属性值
            String providers = configuratorUrl.getParameter(OVERRIDE_PROVIDERS_KEY);//从configuratorUrl获取providerAddresses属性值
            if (StringUtils.isEmpty(providers) //providerAddresses为空
                    || providers.contains(url.getAddress()) //或者providerAddresses包含url的地址
                    || providers.contains(ANYHOST_VALUE)) {//或者providerAddresses是*
                String configApplication = configuratorUrl.getParameter(APPLICATION_KEY,
                        configuratorUrl.getUsername());
                String currentApplication = url.getParameter(APPLICATION_KEY, url.getUsername());
                if (configApplication == null || ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    Set<String> conditionKeys = new HashSet<String>();
                    conditionKeys.add(CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(DYNAMIC_KEY);
                    conditionKeys.add(ENABLED_KEY);
                    conditionKeys.add(GROUP_KEY);
                    conditionKeys.add(VERSION_KEY);
                    conditionKeys.add(APPLICATION_KEY);
                    conditionKeys.add(SIDE_KEY);
                    conditionKeys.add(CONFIG_VERSION_KEY);
                    conditionKeys.add(COMPATIBLE_CONFIG_KEY);
                    //遍历 configuratorUrl的Parameters
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        //
                        if (key.startsWith("~")//如果以～开头
                                || APPLICATION_KEY.equals(key)//如果是application
                                || SIDE_KEY.equals(key)//如果是side
                                ) {
                            conditionKeys.add(key);
                            if (value != null
                                    && !ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}

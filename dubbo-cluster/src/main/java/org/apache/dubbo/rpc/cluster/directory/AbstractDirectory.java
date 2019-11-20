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
package org.apache.dubbo.rpc.cluster.directory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Abstract implementation of Directory: Invoker list returned from this Directory's list method have been filtered by Routers
 *ZookeeperRegistry.doSubscribe->FailbackRegistry.notify->FailbackRegistry.doNotify->
 * AbstractRegistry.notify->RegistryDirectory.notify
 */
public abstract class AbstractDirectory<T> implements Directory<T> {

    // logger
    private static final Logger logger = LoggerFactory.getLogger(AbstractDirectory.class);

    private final URL url;
    //是否销毁的状态
    private volatile boolean destroyed = false;
    //消费者url
    private volatile URL consumerUrl;
    //路由信息
    protected RouterChain<T> routerChain;

    public AbstractDirectory(URL url) {
        this(url, null);
    }

    public AbstractDirectory(URL url, RouterChain<T> routerChain) {
        this(url, url, routerChain);
    }

    /***
     *
     * @param url：zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=springcloud-alibaba-dubbo-provider&dubbo=2.0.2&pid=29002&qos.enable=false&refer=application%3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26interface%3Dcom.springcloud.alibaba.service.HelloAnnotationProviderService%26lazy%3Dfalse%26methods%3DsayHi%26monitor%3Ddubbo%253A%252F%252F127.0.0.1%253A2181%252Forg.apache.dubbo.registry.RegistryService%253Fapplication%253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526pid%253D29002%2526protocol%253Dregistry%2526qos.enable%253Dfalse%2526refer%253Dapplication%25253Dspringcloud-alibaba-dubbo-provider%252526dubbo%25253D2.0.2%252526interface%25253Dorg.apache.dubbo.monitor.MonitorService%252526pid%25253D29002%252526qos.enable%25253Dfalse%252526register.ip%25253D220.250.64.225%252526release%25253D2.7.4.1%252526timestamp%25253D1574218168630%2526registry%253Dzookeeper%2526release%253D2.7.4.1%2526timestamp%253D1574218168628%26pid%3D29002%26qos.enable%3Dfalse%26register.ip%3D220.250.64.225%26release%3D2.7.4.1%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1574218168373&release=2.7.4.1&timestamp=1574218168628
     * @param consumerUrl：zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=springcloud-alibaba-dubbo-provider&dubbo=2.0.2&pid=29002&qos.enable=false&refer=application%3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26interface%3Dcom.springcloud.alibaba.service.HelloAnnotationProviderService%26lazy%3Dfalse%26methods%3DsayHi%26monitor%3Ddubbo%253A%252F%252F127.0.0.1%253A2181%252Forg.apache.dubbo.registry.RegistryService%253Fapplication%253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526pid%253D29002%2526protocol%253Dregistry%2526qos.enable%253Dfalse%2526refer%253Dapplication%25253Dspringcloud-alibaba-dubbo-provider%252526dubbo%25253D2.0.2%252526interface%25253Dorg.apache.dubbo.monitor.MonitorService%252526pid%25253D29002%252526qos.enable%25253Dfalse%252526register.ip%25253D220.250.64.225%252526release%25253D2.7.4.1%252526timestamp%25253D1574218168630%2526registry%253Dzookeeper%2526release%253D2.7.4.1%2526timestamp%253D1574218168628%26pid%3D29002%26qos.enable%3Dfalse%26register.ip%3D220.250.64.225%26release%3D2.7.4.1%26side%3Dconsumer%26sticky%3Dfalse%26timestamp%3D1574218168373&release=2.7.4.1&timestamp=1574218168628
     * @param routerChain
     */
    public AbstractDirectory(URL url, URL consumerUrl, RouterChain<T> routerChain) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        //判断是否是 registry 协议
        if (UrlUtils.isRegistry(url)) {
            //根据refer属性值转换成queryMap
            Map<String, String> queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
            this.url = url.addParameters(queryMap).removeParameter(MONITOR_KEY);
        } else {
            this.url = url;
        }

        this.consumerUrl = consumerUrl;
        setRouterChain(routerChain);
    }

    /***
     * 获得服务提供者列表
     * @param invocation 调用者信息
     *        RpcInvocation [methodName=sayHi, parameterTypes=[class java.lang.String], arguments=[null], attachments={}]
     * @return
     * @throws RpcException
     */
    @Override
    public List<Invoker<T>> list(Invocation invocation) throws RpcException {
        if (destroyed) {
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }
        //默认调用RegistryDirectory
        return doList(invocation);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    public RouterChain<T> getRouterChain() {
        return routerChain;
    }

    public void setRouterChain(RouterChain<T> routerChain) {
        this.routerChain = routerChain;
    }

    protected void addRouters(List<Router> routers) {
        routers = routers == null ? Collections.emptyList() : routers;
        routerChain.addRouters(routers);
    }

    /***
     * 消费者url
     * @return
     */
    public URL getConsumerUrl() {
        //consumer://192.168.0.103/com.springcloud.alibaba.service.HelloAnnotationProviderService
        // ?application=springcloud-alibaba-dubbo-provider&category=providers,configurators,routers
        // &dubbo=2.0.2&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService
        // &lazy=false&methods=sayHi&pid=15244&qos.enable=false&release=2.7.4.1
        // &side=consumer&sticky=false&timestamp=1574206816104
        return consumerUrl;
    }
    //将当前Directory绑定对应的接口服务
    public void setConsumerUrl(URL consumerUrl) {
        this.consumerUrl = consumerUrl;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    /***
     * 销毁所有的invoker
     */
    @Override
    public void destroy() {
        destroyed = true;
    }

    protected abstract List<Invoker<T>> doList(Invocation invocation) throws RpcException;

}

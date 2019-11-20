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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_LOADBALANCE;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_AVAILABLE_CHECK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_STICKY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_STICKY;

/**
 * AbstractClusterInvoker
 *
 * x
 */

/***
 * 采用了模版的设计模式，定义了一系列的模版功能：
 *      获取服务列表：
 *      负载均衡：
 *      调用服务提供者：
 *
 * @param <T>
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    protected Directory<T> directory;

    protected boolean availablecheck;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker() {
    }

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(CLUSTER_AVAILABLE_CHECK_KEY, DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return directory.getInterface();
    }

    /***
     * 根据目录获得url
     * @return
     */
    @Override
    public URL getUrl() {
        //zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?anyhost=true
        // &application=springcloud-alibaba-dubbo-provider&bean.name=ServiceBean:com.springcloud.alibaba.service.HelloAnnotationProviderService
        // &check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService
        // &lazy=false&methods=sayHi&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2Forg.apache.dubbo.registry.RegistryService%3Fapplication
        // %3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26pid%3D15244%26protocol%3Dregistry%26qos.enable%3Dfalse%26refer%3Dapplication
        // %253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526interface%253Dorg.apache.dubbo.monitor.MonitorService%2526pid
        // %253D15244%2526qos.enable%253Dfalse%2526register.ip%253D192.168.0.103%2526release%253D2.7.4.1%2526timestamp%253D1574206816273
        // %26registry%3Dzookeeper%26release%3D2.7.4.1%26timestamp%3D1574206816272&pid=15244&qos.enable=false&register.ip=192.168.0.103&release=2.7.4.1
        // &remote.application=springcloud-alibaba-dubbo-provider&side=consumer&sticky=false&timestamp=1574206816104
        return directory.getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    public Directory<T> getDirectory() {
        return directory;
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation  invocation
     * @param invokers    invoker candidates
     * @param selected    exclude selected invokers or not
     * @return the invoker which will final to do invoke.
     * @throws RpcException exception
     */
    /****
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected 已选中的
     * @return
     * @throws RpcException
     *  选择一个对应的服务提供者
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        //获得方法名
        String methodName = invocation == null ? StringUtils.EMPTY : invocation.getMethodName();
        //从方法method标签里获得sticky属性，默认是false
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, CLUSTER_STICKY_KEY, DEFAULT_CLUSTER_STICKY);

        //ignore overloaded method  默认sticky=false
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }
        //ignore concurrency problem
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }
        //这边调用 AbstractClusterInvoker.doSelect，从提供者列表中根据负载均衡算法选中一个提供者
        //invoker=interface com.springcloud.alibaba.service.HelloAnnotationProviderService -> dubbo://192.168.0.103:20880/com.springcloud.alibaba.service.HelloAnnotationProviderService?anyhost=true&application=springcloud-alibaba-dubbo-provider&bean.name=ServiceBean:com.springcloud.alibaba.service.HelloAnnotationProviderService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=com.springcloud.alibaba.service.HelloAnnotationProviderService&lazy=false&methods=sayHi&monitor=dubbo%3A%2F%2F127.0.0.1%3A2181%2Forg.apache.dubbo.registry.RegistryService%3Fapplication%3Dspringcloud-alibaba-dubbo-provider%26dubbo%3D2.0.2%26pid%3D15244%26protocol%3Dregistry%26qos.enable%3Dfalse%26refer%3Dapplication%253Dspringcloud-alibaba-dubbo-provider%2526dubbo%253D2.0.2%2526interface%253Dorg.apache.dubbo.monitor.MonitorService%2526pid%253D15244%2526qos.enable%253Dfalse%2526register.ip%253D192.168.0.103%2526release%253D2.7.4.1%2526timestamp%253D1574206816273%26registry%3Dzookeeper%26release%3D2.7.4.1%26timestamp%3D1574206816272&pid=15244&qos.enable=false&register.ip=192.168.0.103&release=2.7.4.1&remote.application=springcloud-alibaba-dubbo-provider&side=consumer&sticky=false&timestamp=1574206704527
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);
        //默认是false
        if (sticky) {
            stickyInvoker = invoker;
        }//返回选中的提供者
        return invoker;
    }

    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        //判断服务提供者是否为空
        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        if (invokers.size() == 1) {//如果只有一个提供者，则直接返回
            return invokers.get(0);
        }//根据负载均衡算法选中一个服务提供者
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //如果之前已选中过这个服务提供者，且这个提供者不可用，且做过可用性检查，则重新选
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rInvoker != null) {
                    invoker = rInvoker;
                } else {
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     *
     * @param loadbalance    load balance policy
     * @param invocation     invocation
     * @param invokers       invoker candidates
     * @param selected       exclude selected invokers or not
     * @param availablecheck check invoker available if true
     * @return the reselect result to do invoke
     * @throws RpcException exception
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // First, try picking a invoker not in `selected`.
        for (Invoker<T> invoker : invokers) {
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }

            if (selected == null || !selected.contains(invoker)) {
                reselectInvokers.add(invoker);
            }
        }

        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // Just pick an available invoker using loadbalance policy
        if (selected != null) {
            for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
        }
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        return null;
    }

    /***
     * 选中服务提供者后就开始调用远程服务
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        //检查节点是否被销毁了
        checkWhetherDestroyed();

        // binding attachments into invocation.
        //从上下文中获得绑定的  attachments，将这些 attachments 一起传给服务端
        Map<String, Object> contextAttachments = RpcContext.getContext().getAttachments();
        if (contextAttachments != null && contextAttachments.size() != 0) {
            ((RpcInvocation) invocation).addAttachments(contextAttachments);
        }
        //获得所有的 Invoker 列表(在这一步会根据router路由信息都RegistryDirectory中提供的服务列表进行过滤)
        List<Invoker<T>> invokers = list(invocation);
        //获得负载均衡的方式
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        //为当前的rpc请求创建一个id，用于标识当前的请求
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        //根据对应的实现类调用,比如：FailoverClusterInvoker
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    /****
     *  校验 传入的 invoker 的列表是否为空，基本每种 AbstractClusterInvoker 都要做这个校验，所以放到抽象类上
     * @param invokers
     * @param invocation
     */
    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    /***
     * 需要子类实现，不同的集群方式有自己不同的实现逻辑
     * @param invocation
     * @param invokers
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    /***
     * 根据路由过滤，从服务提供者列表里获取符合条件的服务提供者列表
     * @param invocation
     * @return
     * @throws RpcException
     */
    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        return directory.list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(//获得方法配置属性 loadbalance，没有配置负载均衡的方式的话，默认采用random
                            RpcUtils.getMethodName(invocation), //获得方法名
                            LOADBALANCE_KEY, DEFAULT_LOADBALANCE));
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(DEFAULT_LOADBALANCE);
        }
    }
}

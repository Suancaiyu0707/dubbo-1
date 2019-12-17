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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.retry.FailedNotifiedTask;
import org.apache.dubbo.registry.retry.FailedRegisteredTask;
import org.apache.dubbo.registry.retry.FailedSubscribedTask;
import org.apache.dubbo.registry.retry.FailedUnregisteredTask;
import org.apache.dubbo.registry.retry.FailedUnsubscribedTask;
import org.apache.dubbo.remoting.Constants;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RETRY_PERIOD;
import static org.apache.dubbo.registry.Constants.REGISTRY_RETRY_PERIOD_KEY;

/**
 * FailbackRegistry. (SPI, Prototype, ThreadSafe)
 * FailbackRegistry 在 AbstractRegistry 的基础上，实现了和注册中心实际的操作，并且支持失败重试的特性。
 */
public abstract class FailbackRegistry extends AbstractRegistry {

    /*  retry task map */
    //发起注册失败的url
    private final ConcurrentMap<URL, FailedRegisteredTask> failedRegistered = new ConcurrentHashMap<URL, FailedRegisteredTask>();
    //取消注册失败的url集合
    private final ConcurrentMap<URL, FailedUnregisteredTask> failedUnregistered = new ConcurrentHashMap<URL, FailedUnregisteredTask>();
    //发起订阅失败的监听器集合
    private final ConcurrentMap<Holder, FailedSubscribedTask> failedSubscribed = new ConcurrentHashMap<Holder, FailedSubscribedTask>();
    //取消订阅失败的监听器集合
    private final ConcurrentMap<Holder, FailedUnsubscribedTask> failedUnsubscribed = new ConcurrentHashMap<Holder, FailedUnsubscribedTask>();
    //通知失败的url集合
    private final ConcurrentMap<Holder, FailedNotifiedTask> failedNotified = new ConcurrentHashMap<Holder, FailedNotifiedTask>();

    /**
     * The time in milliseconds the retryExecutor will wait
     */
    private final int retryPeriod;

    // Timer for failure retry, regular check if there is a request for failure, and if there is, an unlimited retry
    private final HashedWheelTimer retryTimer;

    public FailbackRegistry(URL url) {
        super(url);
        this.retryPeriod = url.getParameter(REGISTRY_RETRY_PERIOD_KEY, DEFAULT_REGISTRY_RETRY_PERIOD);//5000


        retryTimer = new HashedWheelTimer(new NamedThreadFactory("DubboRegistryRetryTimer", true), retryPeriod, TimeUnit.MILLISECONDS, 128);
    }

    public void removeFailedRegisteredTask(URL url) {
        failedRegistered.remove(url);
    }

    public void removeFailedUnregisteredTask(URL url) {
        failedUnregistered.remove(url);
    }

    public void removeFailedSubscribedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedSubscribed.remove(h);
    }

    public void removeFailedUnsubscribedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedUnsubscribed.remove(h);
    }

    public void removeFailedNotifiedTask(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        failedNotified.remove(h);
    }
    /***
     * 添加一个注册重试的任务
     * @param url
     */
    private void addFailedRegistered(URL url) {
        FailedRegisteredTask oldOne = failedRegistered.get(url);
        if (oldOne != null) {
            return;
        }
        FailedRegisteredTask newTask = new FailedRegisteredTask(url, this);
        oldOne = failedRegistered.putIfAbsent(url, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedRegistered(URL url) {
        FailedRegisteredTask f = failedRegistered.remove(url);
        if (f != null) {
            f.cancel();
        }
    }

    /***
     * 添加一个取消注册重试的任务
     * @param url
     */
    private void addFailedUnregistered(URL url) {
        FailedUnregisteredTask oldOne = failedUnregistered.get(url);
        if (oldOne != null) {
            return;
        }
        FailedUnregisteredTask newTask = new FailedUnregisteredTask(url, this);
        oldOne = failedUnregistered.putIfAbsent(url, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedUnregistered(URL url) {
        FailedUnregisteredTask f = failedUnregistered.remove(url);
        if (f != null) {
            f.cancel();
        }
    }

    /***
     * 添加一个订阅失败重试的任务
     * @param url
     * @param listener
     */
    protected void addFailedSubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedSubscribedTask oldOne = failedSubscribed.get(h);
        if (oldOne != null) {
            return;
        }
        FailedSubscribedTask newTask = new FailedSubscribedTask(url, this, listener);
        oldOne = failedSubscribed.putIfAbsent(h, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 移除订阅失败的url
     * @param url 订阅的url
     * @param listener
     * 1、执行对应的订阅失败的任务FailedUnsubscribedTask，移除 url和listener对应 failedUnsubscribed 订阅任务
     * 2、执行对应的通知失败的任务FailedNotifiedTask
     */
    private void removeFailedSubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedSubscribedTask f = failedSubscribed.remove(h);
        if (f != null) {
            f.cancel();
        }
        removeFailedUnsubscribed(url, listener);
        removeFailedNotified(url, listener);
    }

    /***
     * 添加一个取消订阅失败重试的任务
     * @param url
     * @param listener
     */
    private void addFailedUnsubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedUnsubscribedTask oldOne = failedUnsubscribed.get(h);
        if (oldOne != null) {
            return;
        }
        FailedUnsubscribedTask newTask = new FailedUnsubscribedTask(url, this, listener);
        oldOne = failedUnsubscribed.putIfAbsent(h, newTask);
        if (oldOne == null) {
            // never has a retry task. then start a new task for retry.
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        }
    }

    private void removeFailedUnsubscribed(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedUnsubscribedTask f = failedUnsubscribed.remove(h);
        if (f != null) {
            f.cancel();
        }
    }
    /***
     * 添加一个通知监听节点变更失败重试的任务
     * @param url
     * @param listener
     */
    private void addFailedNotified(URL url, NotifyListener listener, List<URL> urls) {
        Holder h = new Holder(url, listener);
        FailedNotifiedTask newTask = new FailedNotifiedTask(url, listener);
        FailedNotifiedTask f = failedNotified.putIfAbsent(h, newTask);
        if (f == null) {
            // never has a retry task. then start a new task for retry.
            newTask.addUrlToRetry(urls);
            retryTimer.newTimeout(newTask, retryPeriod, TimeUnit.MILLISECONDS);
        } else {
            // just add urls which needs retry.
            newTask.addUrlToRetry(urls);
        }
    }

    private void removeFailedNotified(URL url, NotifyListener listener) {
        Holder h = new Holder(url, listener);
        FailedNotifiedTask f = failedNotified.remove(h);
        if (f != null) {
            f.cancel();
        }
    }

    ConcurrentMap<URL, FailedRegisteredTask> getFailedRegistered() {
        return failedRegistered;
    }

    ConcurrentMap<URL, FailedUnregisteredTask> getFailedUnregistered() {
        return failedUnregistered;
    }

    ConcurrentMap<Holder, FailedSubscribedTask> getFailedSubscribed() {
        return failedSubscribed;
    }

    ConcurrentMap<Holder, FailedUnsubscribedTask> getFailedUnsubscribed() {
        return failedUnsubscribed;
    }

    ConcurrentMap<Holder, FailedNotifiedTask> getFailedNotified() {
        return failedNotified;
    }

    /***
     *
     * @param url  Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     *      provider: dubbo://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=30672&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576489098974
     * 1、判断注册中心是否接受注册
     * 2、更新内存，缓存已注册的服务提供者地址
     * 3、移除注册失败的服务地址
     * 4、移除取消注册失败的任务
     * 5、根据url在zk上创建相应的路径
     */
    @Override
    public void register(URL url) {
        if (!acceptable(url)) {//判断注册中心是否接受注册
            logger.info("URL " + url + " will not be registered to Registry. Registry " + url + " does not accept service of this protocol type.");
            return;
        }
        super.register(url);//内存里缓存已注册的服务地址
        removeFailedRegistered(url);//执行注册失败的任务，移除注册失败的服务地址
        removeFailedUnregistered(url);//执行取消注册失败的任务
        try {
            // Sending a registration request to the server side
            doRegister(url);//根据url在zk上创建相应的路径
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to register " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to register " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            addFailedRegistered(url);
        }
    }

    @Override
    public void unregister(URL url) {
        super.unregister(url);
        removeFailedRegistered(url);
        removeFailedUnregistered(url);
        try {
            // Sending a cancellation request to the server side
            doUnregister(url);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true)
                    && !CONSUMER_PROTOCOL.equals(url.getProtocol());
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unregister " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unregister " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            addFailedUnregistered(url);
        }
    }
    // provider：provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=10604&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576476419518

    /***
     *
     * @param url   Subscription condition, not allowed to be empty, e.g. consumer://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     *
     * @param listener A listener of the change event, not allowed to be empty
     *      provider: provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880
     *                 &category=configurators
     *                 &check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=19528&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576482019237
     *      consumer:
     * 1、本地的订阅信息缓存记录订阅关系
     * 2、移除先前订阅失败的关系，包括 `failedSubscribed` `failedUnsubscribed` `failedNotified`
     * 3、向注册中心发起订阅请求
     */
    @Override
    public void subscribe(URL url, NotifyListener listener) {
        super.subscribe(url, listener);
        removeFailedSubscribed(url, listener);//先移除订阅失败的
        try {
            // 向注册中心发送订阅请求
            doSubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;
            // 如果有缓存的 URL 集合，进行通知。后续订阅成功后，会使用最新的 URL 集合，进行通知。
            List<URL> urls = getCacheUrls(url);
            if (CollectionUtils.isNotEmpty(urls)) {
                notify(url, listener, urls);
                logger.error("Failed to subscribe " + url + ", Using cached list: " + urls + " from cache file: " + getUrl().getParameter(FILE_KEY, System.getProperty("user.home") + "/dubbo-registry-" + url.getHost() + ".cache") + ", cause: " + t.getMessage(), t);
            } else {
                // If the startup detection is opened, the Exception is thrown directly.
                // 如果开启了启动时检测，则直接抛出异常
                boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                        && url.getParameter(Constants.CHECK_KEY, true);
                boolean skipFailback = t instanceof SkipFailbackWrapperException;
                if (check || skipFailback) {
                    if (skipFailback) {
                        t = t.getCause();
                    }
                    throw new IllegalStateException("Failed to subscribe " + url + ", cause: " + t.getMessage(), t);
                } else {
                    logger.error("Failed to subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
                }
            }

            // Record a failed registration request to a failed list, retry regularly
            // 将失败的订阅请求记录到 `failedSubscribed`，定时重试
            addFailedSubscribed(url, listener);
        }
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        super.unsubscribe(url, listener);
        removeFailedSubscribed(url, listener);
        try {
            // Sending a canceling subscription request to the server side
            doUnsubscribe(url, listener);
        } catch (Exception e) {
            Throwable t = e;

            // If the startup detection is opened, the Exception is thrown directly.
            boolean check = getUrl().getParameter(Constants.CHECK_KEY, true)
                    && url.getParameter(Constants.CHECK_KEY, true);
            boolean skipFailback = t instanceof SkipFailbackWrapperException;
            if (check || skipFailback) {
                if (skipFailback) {
                    t = t.getCause();
                }
                throw new IllegalStateException("Failed to unsubscribe " + url + " to registry " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
            } else {
                logger.error("Failed to unsubscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
            }

            // Record a failed registration request to a failed list, retry regularly
            addFailedUnsubscribed(url, listener);
        }
    }

    /**
     *   通知监听器
     * @param url 订阅者 URL
     *     provider: provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=10604&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576476419518
     *         会订阅configurators 目录
     *     consumer：
     *        会订阅configurators/providers/routers 目录
     * @param listener 监听子路径变化的监听器 listener
     * @param urls 通知的 URL 变化结果（全量数据）
     */
    @Override
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        try {
            doNotify(url, listener, urls);
        } catch (Exception t) {
            // Record a failed registration request to a failed list, retry regularly
            addFailedNotified(url, listener, urls);
            logger.error("Failed to notify for subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
        }
    }
    /**
     *
     * @param url 订阅者 URL
     *     provider: provider://220.250.64.225:20880/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&category=configurators&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=10604&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576476419518
     *         会订阅configurators 目录
     *     consumer：
     *        会订阅configurators/providers/routers 目录
     * @param listener 监听子路径变化的监听器 listener
     * @param urls 通知的 URL 变化结果（全量数据）
     */
    protected void doNotify(URL url, NotifyListener listener, List<URL> urls) {
        super.notify(url, listener, urls);
    }

    /***
     * 恢复重新订阅和注册，失败会重试
     * @throws Exception
     */
    @Override
    protected void recover() throws Exception {
        // register 恢复注册，添加到 `failedRegistered` ，定时重试
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                addFailedRegistered(url);
            }
        }
        // subscribe 恢复订阅，添加到 `failedSubscribed` ，定时重试
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        retryTimer.stop();
    }

    // ==== Template method  模版方法，具体的由子类来实现 ====

    public abstract void doRegister(URL url);

    public abstract void doUnregister(URL url);

    public abstract void doSubscribe(URL url, NotifyListener listener);

    public abstract void doUnsubscribe(URL url, NotifyListener listener);

    static class Holder {

        private final URL url;

        private final NotifyListener notifyListener;

        Holder(URL url, NotifyListener notifyListener) {
            if (url == null || notifyListener == null) {
                throw new IllegalArgumentException();
            }
            this.url = url;
            this.notifyListener = notifyListener;
        }

        @Override
        public int hashCode() {
            return url.hashCode() + notifyListener.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Holder) {
                Holder h = (Holder) obj;
                return this.url.equals(h.url) && this.notifyListener.equals(h.notifyListener);
            } else {
                return false;
            }
        }
    }
}

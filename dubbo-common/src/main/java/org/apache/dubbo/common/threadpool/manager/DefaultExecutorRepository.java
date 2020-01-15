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
package org.apache.dubbo.common.threadpool.manager;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.utils.NamedThreadFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.EXECUTOR_SERVICE_COMPONENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;

/**
 * Consider implementing {@code Licycle} to enable executors shutdown when the process stops.
 */
public class DefaultExecutorRepository implements ExecutorRepository {
    private static final Logger logger = LoggerFactory.getLogger(DefaultExecutorRepository.class);

    private int DEFAULT_SCHEDULER_SIZE = Runtime.getRuntime().availableProcessors();

    private final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(new NamedThreadFactory("DubboSharedHandler", true));

    private Ring<ScheduledExecutorService> scheduledExecutors = new Ring<>();

    private ScheduledExecutorService serviceExporterExecutor;

    private ScheduledExecutorService reconnectScheduledExecutor;

    private ConcurrentMap<String, ConcurrentMap<Integer, ExecutorService>> data = new ConcurrentHashMap<>();

    public DefaultExecutorRepository() {
//        for (int i = 0; i < DEFAULT_SCHEDULER_SIZE; i++) {
//            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-framework-scheduler"));
//            scheduledExecutors.addItem(scheduler);
//        }
//
//        reconnectScheduledExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Dubbo-reconnect-scheduler"));
        serviceExporterExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Dubbo-exporter-scheduler"));
    }

    /***
     * 初始化一个连接池对象
     * @param url
     * @return
     * 1、判断是CONSUMER端还是PROVIDER端
     * 2、判断这个CONSUMER/PROVIDER端是否有包含当前端口的线程池
     * 3、创建这个CONSUMER/PROVIDER端下对应端口的线程池
     */
    public synchronized ExecutorService createExecutorIfAbsent(URL url) {
        String componentKey = EXECUTOR_SERVICE_COMPONENT_KEY;
        //如果是消费端连接池，componentKey=consumer
        if (CONSUMER_SIDE.equalsIgnoreCase(url.getParameter(SIDE_KEY))) {
            componentKey = CONSUMER_SIDE;
        }
        //消费端和服务端各一个连接池
        Map<Integer, ExecutorService> executors = data.computeIfAbsent(componentKey, k -> new ConcurrentHashMap<>());
        Integer portKey = url.getPort();
        ExecutorService executor = executors.computeIfAbsent(portKey, k -> createExecutor(url));
        // If executor has been shut down, create a new one
        if (executor.isShutdown() || executor.isTerminated()) {
            executors.remove(portKey);
            executor = createExecutor(url);
            executors.put(portKey, executor);
        }
        return executor;
    }

    /***
     *
     * @param url
     * @return
     * ConcurrentMap<String, ConcurrentMap<Integer, ExecutorService>> data ：
     *      String：是一个代表客户端或者服务端的key
     *      Integer: 代表端口号
     */
    public ExecutorService getExecutor(URL url) {
        String componentKey = EXECUTOR_SERVICE_COMPONENT_KEY;
        //如果是一个消费端的话，则从
        if (CONSUMER_SIDE.equalsIgnoreCase(url.getParameter(SIDE_KEY))) {
            componentKey = CONSUMER_SIDE;
        }
        Map<Integer, ExecutorService> executors = data.get(componentKey);
        if (executors == null) {
            return null;
        }
        //获得端口号
        Integer portKey = url.getPort();
        ExecutorService executor = executors.get(portKey);
        if (executor != null) {
            if (executor.isShutdown() || executor.isTerminated()) {
                executors.remove(portKey);
                executor = createExecutor(url);
                executors.put(portKey, executor);
            }
        }
        return executor;
    }

    @Override
    public void updateThreadpool(URL url, ExecutorService executor) {
        try {
            if (url.hasParameter(THREADS_KEY)
                    && executor instanceof ThreadPoolExecutor && !executor.isShutdown()) {
                ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
                int threads = url.getParameter(THREADS_KEY, 0);
                int max = threadPoolExecutor.getMaximumPoolSize();
                int core = threadPoolExecutor.getCorePoolSize();
                if (threads > 0 && (threads != max || threads != core)) {
                    if (threads < core) {
                        threadPoolExecutor.setCorePoolSize(threads);
                        if (core == max) {
                            threadPoolExecutor.setMaximumPoolSize(threads);
                        }
                    } else {
                        threadPoolExecutor.setMaximumPoolSize(threads);
                        if (core == max) {
                            threadPoolExecutor.setCorePoolSize(threads);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    public ScheduledExecutorService nextScheduledExecutor() {
        return scheduledExecutors.pollItem();
    }

    @Override
    public ScheduledExecutorService getServiceExporterExecutor() {
        return serviceExporterExecutor;
    }

    @Override
    public ExecutorService getSharedExecutor() {
        return SHARED_EXECUTOR;
    }

    /***
     * 创建一个连接池，默认是fixed
     * @param url
     * @return
     * 1、创建一个线程池，会根据url中的threadpool配置来决定创建哪种线程池：
     *      fixed 固定大小线程池，启动时建立线程，不关闭，一直持有。(缺省)
     *      cached 缓存线程池，空闲一分钟自动删除，需要时重建。
     *      limited 可伸缩线程池，但池中的线程数只会增长不会收缩。只增长不收缩的目的是为了避免收缩时突然来了大流量引起的性能问题。
     *      eager 优先创建Worker线程池。在任务数量大于corePoolSize但是小于maximumPoolSize时，优先创建Worker来处理任务。
     *          当任务数量大于maximumPoolSize时，将任务放入阻塞队列中。阻塞队列充满时抛出RejectedExecutionException。
     *          (相比于cached:cached在任务数量超过maximumPoolSize时直接抛出异常而不是将任务放入阻塞队列)
     */
    private ExecutorService createExecutor(URL url) {
        return (ExecutorService) ExtensionLoader.getExtensionLoader(ThreadPool.class).getAdaptiveExtension().getExecutor(url);
    }

}

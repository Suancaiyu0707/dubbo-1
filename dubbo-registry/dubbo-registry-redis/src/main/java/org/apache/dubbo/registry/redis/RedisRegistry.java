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
package org.apache.dubbo.registry.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.rpc.RpcException;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTRY_RECONNECT_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.UNREGISTER;

/**
 * RedisRegistry：通过redis实现服务注册
 *  redis和基于 Zookeeper 实现的注册中心，也是分成 Root、Service、Type、URL 四层。
 *  使用 Redis Map 的数据结构，聚合相同服务和类型( Root + Service + Type )。
 *  不使用 Redis 的自动过期机制，而是通过监控中心，实现过期机制。因为，Redis Key 自动过期时，不存在相应的事件通知。
 *  服务提供者和消费者，定时延长其注册的 URL 地址的过期时间。
 *  普通消费者直接订阅指定服务提供者的 Key，只会收到指定服务的变更事件，而监控中心通过 psubscribe 功能订阅 /dubbo/*，会收到所有服务的所有变更事件
 *
 * Key:/dubbo/com.foo.BarService/providers
 * value:是一个map类型
 *      dubbo://127.0.0.1:1234/barService=121201...
 *      dubbo://127.0.0.2:1234/barService=121201...
 * Key:/dubbo/com.foo.BarService/consumers
 * value:是一个map类型
 *      subscribe://10.0.0.1:1234/barService=121201...
 *      subscribe://10.0.0.2:1234/barService=121201...
 *
 * 整个调用流程：
 * 【一】服务提供方
 *      1、服务提供方启动时，向 Key=/dubbo/com.foo.BarService/providers 下，添加当前提供者的地址。
 *          每个服务提供者对应一个map，其中map的key是服务名。map的field是具体的提供者的url，value是过期时间；
 *                   hash key：服务提供者服务名
 *                   filed：某个具体的服务提供者的url
 *                   value：服务提供者的过期时间
 *      2、并向 Channel=/dubbo/com.foo.BarService/providers 发送 register 事件
 * 【二】服务消费方
 *      1、服务消费方启动时，从 Channel=/dubbo/com.foo.BarService/providers 订阅 register 和 unregister 事件
 *      2、并向 Key=/dubbo/com.foo.BarService/providers 下，添加当前消费者的地址
 * 服务消费方收到 register 和 unregister 事件后，从 Key=/dubbo/com.foo.BarService/providers 下获取提供者地址列表
 * 【三】服务监控中心
 *      1、服务监控中心启动时，从 Channel=/dubbo/* 订阅 register 和 unregister，以及 subscribe 和 unsubsribe 事件
 *      2、服务监控中心收到 register 和 unregister 事件后，从 Key=/dubbo/com.foo.BarService/providers 下获取提供者地址列表
 *      3、服务监控中心收到 subscribe 和 unsubsribe 事件后，从 Key=/dubbo/com.foo.BarService/consumers 下获取消费者地址列表
 */
public class RedisRegistry extends FailbackRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisRegistry.class);
    /**
     * redis集群端口
     */
    private static final int DEFAULT_REDIS_PORT = 6379;
    /***
     * 默认的redis根节点
     */
    private final static String DEFAULT_ROOT = "dubbo";
    /***
     * 定时任务，用于执行redis过期key的执行器
     */
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    private final ScheduledFuture<?> expireFuture;
    /**
     * eg: /root/
     */
    private final String root;
    /***
     * JedisPool 集合
     * key：ip:port
     */
    private final Map<String, JedisPool> jedisPools = new ConcurrentHashMap<>();
    /***
     * 通知器集合
     *  key：Root + Service ，例如 `/dubbo/com.alibaba.dubbo.demo.DemoService`。
     *  用于 Redis Publish/Subscribe 机制中的订阅，实时监听数据的变化。
     */
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<>();
    /**
     * 重连事件间隔
     */
    private final int reconnectPeriod;
    //redis中的key的超时时间，默认是60s ，可通过session进行配置
    private final int expirePeriod;
    /***
     * 是否监控中心
     * 于判断脏数据，脏数据由监控中心通过clean方法删除
     */
    private volatile boolean admin = false;

    private boolean replicate;
    /***
     *
     * redis://127.0.0.1:6379/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2&interface=org.apache.dubbo.registry.RegistryService&pid=22219&qos.port=22222&timestamp=1576065462096
     * 创建一个redis注册中心，并初始化redis配置
     * @param url
     */
    public RedisRegistry(URL url) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        GenericObjectPoolConfig config = new GenericObjectPoolConfig();
        config.setTestOnBorrow(url.getParameter("test.on.borrow", true));
        config.setTestOnReturn(url.getParameter("test.on.return", false));
        config.setTestWhileIdle(url.getParameter("test.while.idle", false));
        if (url.getParameter("max.idle", 0) > 0) {
            config.setMaxIdle(url.getParameter("max.idle", 0));
        }
        if (url.getParameter("min.idle", 0) > 0) {
            config.setMinIdle(url.getParameter("min.idle", 0));
        }
        if (url.getParameter("max.active", 0) > 0) {
            config.setMaxTotal(url.getParameter("max.active", 0));
        }
        if (url.getParameter("max.total", 0) > 0) {
            config.setMaxTotal(url.getParameter("max.total", 0));
        }
        if (url.getParameter("max.wait", url.getParameter("timeout", 0)) > 0) {
            config.setMaxWaitMillis(url.getParameter("max.wait", url.getParameter("timeout", 0)));
        }
        if (url.getParameter("num.tests.per.eviction.run", 0) > 0) {
            config.setNumTestsPerEvictionRun(url.getParameter("num.tests.per.eviction.run", 0));
        }
        if (url.getParameter("time.between.eviction.runs.millis", 0) > 0) {
            config.setTimeBetweenEvictionRunsMillis(url.getParameter("time.between.eviction.runs.millis", 0));
        }
        if (url.getParameter("min.evictable.idle.time.millis", 0) > 0) {
            config.setMinEvictableIdleTimeMillis(url.getParameter("min.evictable.idle.time.millis", 0));
        }
        /***
         * 从注册中心上获得集群高可用策略
         *      failover: 只写入和读取任意一台，失败时重试另一台，需要服务器端自行配置数据同步。
         *      replicate: 在客户端同时写入所有服务器，只读取单台，服务器端不需要同步，注册中心集群增大，性能压力也会更大。
         */
        String cluster = url.getParameter("cluster", "failover");
        if (!"failover".equals(cluster) && !"replicate".equals(cluster)) {
            throw new IllegalArgumentException("Unsupported redis cluster: " + cluster + ". The redis cluster only supported failover or replicate.");
        }
        replicate = "replicate".equals(cluster);

        List<String> addresses = new ArrayList<>();
        addresses.add(url.getAddress());
        //当redis集群部署的时候，选第一个节点的URL，其它的作为backup
        String[] backups = url.getParameter(RemotingConstants.BACKUP_KEY, new String[0]);
        if (ArrayUtils.isNotEmpty(backups)) {
            addresses.addAll(Arrays.asList(backups));
        }
        //遍历redis集群地址列表，本地内存维护redis连接池
        for (String address : addresses) {
            int i = address.indexOf(':');
            String host;
            int port;
            if (i > 0) {
                host = address.substring(0, i);
                port = Integer.parseInt(address.substring(i + 1));
            } else {
                host = address;
                port = DEFAULT_REDIS_PORT;
            }
            this.jedisPools.put(address, new JedisPool(config, host, port,
                    url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), StringUtils.isEmpty(url.getPassword()) ? null : url.getPassword(),
                    url.getParameter("db.index", 0)));
        }
        //重连的时间间隔
        this.reconnectPeriod = url.getParameter(REGISTRY_RECONNECT_PERIOD_KEY, DEFAULT_REGISTRY_RECONNECT_PERIOD);
        //判断注册中心是否配置了group属性，没有配置的话，默认是 dubbo
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        //如果group不是以'/'开头，则 拼接一个'/' eg： /dubbo
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        //如果如果group不是以'/'结尾，则拼接一个'/' eg： /dubbo/
        if (!group.endsWith(PATH_SEPARATOR)) {
            group = group + PATH_SEPARATOR;
        }
        //root ='/root/'
        this.root = group;
        //获得超时时间，默认是60s ，可通过session进行配置
        this.expirePeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(() -> {
            try {
                deferExpired(); // Extend the expiration time
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    /***
     * 遍历所有的redis连接池，更新key的失效时间
     */
    private void deferExpired() {
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //循环已经注册的url
                    for (URL url : new HashSet<>(getRegistered())) {
                        //获得动态更新的属性，默认是dynamic
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            //获得注册的url
                            String key = toCategoryPath(url);
                            //更新注册的服务的失效时间，并重新发布注册事件，通知订阅者
                            if (jedis.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                                jedis.publish(key, REGISTER);
                            }
                        }
                    }
                    //如果是监控中心的话，删除过期脏数据
                    if (admin) {
                        clean(jedis);
                    }
                    if (!replicate) {
                        break;//  If the server side has synchronized data, just write a single machine
                    }
                }
            } catch (Throwable t) {
                logger.warn("Failed to write provider heartbeat to redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
    }

    // 监控中心会清除过期的键

    /***
     *
     * @param jedis
     * 1、遍历所有的服务
     * 2、遍历服务下的filed对应的值，也就是失效时间
     * 3、比较失效时间跟当前时间，过期了则删除
     * 4、如果filed被删除了，则发送取消注册的通知事件
     */
    private void clean(Jedis jedis) {
        //获取所有的服务
        Set<String> keys = jedis.keys(root + ANY_VALUE);
        if (CollectionUtils.isNotEmpty(keys)) {
            for (String key : keys) {
                Map<String, String> values = jedis.hgetAll(key);
                if (CollectionUtils.isNotEmptyMap(values)) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    //遍历服务下的filed对应的值，也就是失效时间
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            //获得key的值(也就是失效时间)
                            long expire = Long.parseLong(entry.getValue());
                            //比较失效时间跟当前时间，过期了则删除
                            if (expire < now) {
                                jedis.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    //如果filed有被删除了，则发送取消注册的通知事件
                    if (delete) {
                        jedis.publish(key, UNREGISTER);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        for (JedisPool jedisPool : jedisPools.values()) {
            try (Jedis jedis = jedisPool.getResource()) {
                if (jedis.isConnected()) {
                    return true; // At least one single machine is available.
                }
            } catch (Throwable t) {
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                jedisPool.destroy();
            } catch (Throwable t) {
                logger.warn("Failed to destroy the redis registry client. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    /***
     * 将服务提供者注册到redis里（在这里我们可以发现，每次注册一个新的服务的时候，都会向同名channel发送一个注册的事件）
     *      1、服务提供方启动时，向 Key=/dubbo/com.foo.BarService/providers 下，添加当前提供者的地址
     *      2、并向 Channel=/dubbo/com.foo.BarService/providers 下发送 register 事件
     * @param url ：dubbo://127.0.0.1:1234/barService=121201...
     */
    @Override
    public void doRegister(URL url) {
        //创建redis的key
        // eg：key：/dubbo/com.foo.BarService/providers
        String key = toCategoryPath(url);
        //创建redis的filed
        // eg：filed：dubbo://127.0.0.1:1234/barService=121201...
        String filed = url.toFullString();
        //设置redis key的超时时间，默认是60s ，可通过session进行配置
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        boolean success = false;
        RpcException exception = null;
        //遍历redis连接池
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //set key value 到redis连接池，注意，这里是把过期时间作为map里的值
                    // hash key：服务提供者服务名
                    // filed：某个具体的服务提供者的url
                    // value：服务提供者的过期时间
                    jedis.hset(key, filed, expire);
                    //向名称与key同名的channel发送注册的通知信息，通知订阅者，自己已经注册上去了(这样订阅者可以订阅这个channel获得通知从而进行下一步操作)
                    //通过向channel管道发送注册事件，这样订阅者（消费者，普通消费者直接订阅指定服务提供者的 Key，只会收到指定服务的变更事件）可以实时获得服务端注册通知
                    jedis.publish(key, REGISTER);
                    success = true;
                    //如果非 replicate ，意味着 Redis 服务器端自己已同步数据，只需写入单台机器。因此，结束循环。否则，满足 replicate ，向所有 Redis 写入。
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to register service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    /***
     * 将某个服务提供者从redis里取消注册
     * @param url ：dubbo://127.0.0.1:1234/barService=121201...
     * 1、创建redis的key和filed:
     *      eg：
     *          key：/dubbo/com.foo.BarService/providers
     *          filed：dubbo://127.0.0.1:1234/barService=121201...
     * 2、遍历redis连接池
     *      a、向redis连接池执行del key filed
     *      b、向名称与key同名的channel发送取消通知信息，通知订阅者，自己已经取消注册了(这样订阅者可以订阅这个channel获得通知从而进行下一步操作)
     *      c、如果是failover可用的话，则退出，因为表示Redis服务器端自己会帮忙完成删除数据的同步
     */
    @Override
    public void doUnregister(URL url) {
        String key = toCategoryPath(url);
        String filed = url.toFullString();
        RpcException exception = null;
        boolean success = false;
        //遍历redis连接池
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    //向redis连接池执行del key value。删除名称为key的hash的filed是value的
                    jedis.hdel(key, filed);
                    //向名称与key同名的channel发送取消通知信息，通知订阅者，自己已经取消注册了(这样订阅者可以订阅这个channel获得通知从而进行下一步操作)
                    jedis.publish(key, UNREGISTER);
                    success = true;
                    //如果是failover可用的话，则退出，不继续遍历剩下的连接池(因为肯定是只有一个)，不然就在每个连接池里都取消注册这个服务信息
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to unregister service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    /***
     *
     * @param url 消费者订阅的服务url
     * @param listener 监听器 订阅监听对应的服务提供者
     * 1、服务消费方启动时，从 Channel=/dubbo/com.foo.BarService/providers 订阅 register 和 unregister 事件
     * 2、并向 Key=/dubbo/com.foo.BarService/providers 下，添加当前消费者的地址
     * 1、根据请求的url希望订阅的service地址
     * 2、为订阅的service地址绑定一个通知器Notifier
     * 3、遍历所有的jedis连接池的实例(第一次订阅时，要触发订阅服务的所有信息)
     * 4、订阅监听对应的服务提供者，检查service服务：
     *      如果是以/dubbo/*，则表示监控中心，会订阅并监听/dubbo/下的所有的服务
     *      如果是/dubbo/com.foo.BarService，会订阅并监听/dubbo/com.foo.BarService/下所有的路径(包括providers、configurators、routers)
     *
     */
    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        //获取服务的路径
        // eg：/dubbo/com.foo.BarService
        String service = toServicePath(url);
        //检查并初始化对该服务路径的监听Notifier
        Notifier notifier = notifiers.get(service);
        if (notifier == null) {
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            if (notifier == newNotifier) {
                notifier.start();
            }
        }
        boolean success = false;
        RpcException exception = null;
        /***
         * 遍历jedis池
         */
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                try (Jedis jedis = jedisPool.getResource()) {
                    if (service.endsWith(ANY_VALUE)) {//如果service=/dubbo/*，表示监控中心订阅所有的服务
                        admin = true;//表示是监空中心调用
                        //获取 jedis下所有的键（这个要注意，keys很耗性能的）
                        Set<String> keys = jedis.keys(service);
                        if (CollectionUtils.isNotEmpty(keys)) {
                            Map<String, Set<String>> serviceKeys = new HashMap<>();
                            for (String key : keys) {
                                String serviceKey = toServicePath(key);
                                Set<String> sk = serviceKeys.computeIfAbsent(serviceKey, k -> new HashSet<>());
                                sk.add(key);
                            }
                            for (Set<String> sk : serviceKeys.values()) {
                                doNotify(jedis, sk, url, Collections.singletonList(listener));
                            }
                        }
                    } else {//如果是消费者订阅服务
                        doNotify(jedis,
                                jedis.keys(service + PATH_SEPARATOR + ANY_VALUE), //可能返回 providers/ 、configurators/、routers/
                                url,
                                Collections.singletonList(listener));
                    }
                    success = true;
                    break; // Just read one server's data
                }
            } catch (Throwable t) { // Try the next server
                exception = new RpcException("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    /**
     *
     * @param jedis
     * @param key 分类数组，
     *            eg：/dubbo/com.alibaba.dubbo.demo.DemoService/providers
     * 1、获得所有的监听器
     */
    private void doNotify(Jedis jedis, String key) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(getSubscribed()).entrySet()) {
            doNotify(jedis, Collections.singletonList(key), entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    /***
     * 通知监听器，获得所有的订阅信息
     * @param jedis 注册中心
     * @param keys  某个服务的分类数组，
     *              eg：`/dubbo/com.alibaba.dubbo.demo.DemoService/providers`、
     *                  `/dubbo/com.alibaba.dubbo.demo.DemoService/routers`
     * @param url 服务的url
     * @param listeners 监听器列表
     */
    private void doNotify(Jedis jedis, Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<>();
        //获得消费端配置的category值
        List<String> categories = Arrays.asList(url.getParameter(CATEGORY_KEY, new String[0]));
        //获得当前消费者需要消费的服务名称
        String consumerService = url.getServiceInterface();
        //循环分类数组(providers、configurators、routers)
        for (String key : keys) {
            if (!ANY_VALUE.equals(consumerService)) {//表示不是监控中心
                //获得接口服务名称
                String providerService = toServiceName(key);
                //如果服务分类不是当前消费的服务，则跳过
                if (!providerService.equals(consumerService)) {
                    continue;
                }
            }
            //获得分类category值
            // eg：providers、configurators、routers
            String category = toCategoryName(key);
            //若订阅的不包含该分类，则跳过
            if (!categories.contains(ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<>();
            Map<String, String> values = jedis.hgetAll(key);
            //获得分类下的所有 URL 数组
            if (CollectionUtils.isNotEmptyMap(values)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    if (!u.getParameter(DYNAMIC_KEY, true)
                            || Long.parseLong(entry.getValue()) >= now) {
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            // 若服务该分类下不存在匹配的订阅的url，则创建 `empty://` 的 URL返回，用于清空该服务的该分类。
            if (urls.isEmpty()) {
                urls.add(URLBuilder.from(url)
                        .setProtocol(EMPTY_PROTOCOL)
                        .setAddress(ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(CATEGORY_KEY, category)
                        .build());
            }
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        // 全量数据获取完成时，调用 `super#notify(...)` 方法，回调 NotifyListener
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }
    //获得
    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    /***
     * 返回 root+服务名称
     *      eg：/dubbo/com.foo.BarService
     * @param url
     * @return
     */
    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    /***
     * 返回 root+服务名称 +'/'+category
     *      eg: /dubbo/com.foo.BarService/providers
     * @param url
     * @return
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private class NotifySub extends JedisPubSub {

        private final JedisPool jedisPool;

        public NotifySub(JedisPool jedisPool) {
            this.jedisPool = jedisPool;
        }

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            if (msg.equals(REGISTER)
                    || msg.equals(UNREGISTER)) {
                try {
                    Jedis jedis = jedisPool.getResource();
                    try {
                        doNotify(jedis, key);
                    } finally {
                        jedis.close();
                    }
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    private class Notifier extends Thread {

        private final String service;
        private final AtomicInteger connectSkip = new AtomicInteger();
        private final AtomicInteger connectSkipped = new AtomicInteger();
        private volatile Jedis jedis;
        private volatile boolean first = true;
        private volatile boolean running = true;
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        private void resetSkip() {
            connectSkip.set(0);
            connectSkipped.set(0);
            connectRandom = 0;
        }

        private boolean isSkip() {
            int skip = connectSkip.get(); // Growth of skipping times
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = ThreadLocalRandom.current().nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            if (connectSkipped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            connectSkip.incrementAndGet();
            connectSkipped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    if (!isSkip()) {
                        try {
                            for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
                                JedisPool jedisPool = entry.getValue();
                                try {
                                    jedis = jedisPool.getResource();
                                    try {
                                        if (service.endsWith(ANY_VALUE)) {
                                            if (first) {
                                                first = false;
                                                Set<String> keys = jedis.keys(service);
                                                if (CollectionUtils.isNotEmpty(keys)) {
                                                    for (String s : keys) {
                                                        doNotify(jedis, s);
                                                    }
                                                }
                                                resetSkip();
                                            }
                                            jedis.psubscribe(new NotifySub(jedisPool), service); // blocking
                                        } else {
                                            if (first) {
                                                first = false;
                                                doNotify(jedis, service);
                                                resetSkip();
                                            }
                                            jedis.psubscribe(new NotifySub(jedisPool), service + PATH_SEPARATOR + ANY_VALUE); // blocking
                                        }
                                        break;
                                    } finally {
                                        jedis.close();
                                    }
                                } catch (Throwable t) { // Retry another server
                                    logger.warn("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
                                    // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                    sleep(reconnectPeriod);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void shutdown() {
            try {
                running = false;
                jedis.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}

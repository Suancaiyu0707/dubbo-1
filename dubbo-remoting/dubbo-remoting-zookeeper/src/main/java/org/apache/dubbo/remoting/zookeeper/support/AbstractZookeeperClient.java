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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/***
 * 实现 ZookeeperClient 接口，Zookeeper 客户端抽象类，实现通用的逻辑。
 * @param <TargetDataListener>
 * @param <TargetChildListener>
 */
public abstract class AbstractZookeeperClient<TargetDataListener, TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    protected int DEFAULT_CONNECTION_TIMEOUT_MS = 5 * 1000;
    protected int DEFAULT_SESSION_TIMEOUT_MS = 60 * 1000;
    /***
     * 注册中心的地址
     */
    private final URL url;

    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();
    /**
     * ChildListener 集合
     *
     * key1：节点路径
     * key2：ChildListener 对象
     * value ：监听器具体对象。不同 Zookeeper 客户端，实现会不同。
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();
    /**
     * listeners 集合
     *
     * key1：节点路径
     * key2：DataListener 对象
     * value ：监听器具体对象。不同 Zookeeper 客户端，实现会不同。
     */
    private final ConcurrentMap<String, ConcurrentMap<DataListener, TargetDataListener>> listeners = new ConcurrentHashMap<String, ConcurrentMap<DataListener, TargetDataListener>>();
    /**
     * zk 客户端是否关闭
     */
    private volatile boolean closed = false;
    /***
     * 持久化的节点路径的目录
     */
    private final Set<String>  persistentExistNodePath = new ConcurrentHashSet<>();

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /***
     * 删除一个节点
     * @param path 节点路径
     * 1、直接移除持久化节点的集合 persistentExistNodePath  中的元素
     * 2、通过zk客户端具体实现删除指定的节点，具体的删除逻辑由子类实现
     */
    @Override
    public void delete(String path){
        //never mind if ephemeral
        persistentExistNodePath.remove(path);
        deletePath(path);
    }


    /***
     *
     * @param path 节点路径  path：provider: /dubbo/org.apache.dubbo.demo.EventNotifyService/providers/dubbo%3A%2F%2F192.168.0.105%3A20880%2Forg.apache.dubbo.demo.EventNotifyService%3Fanyhost%3Dtrue%26bean.name%3Dorg.apache.dubbo.demo.EventNotifyService%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Dcn%26interface%3Dorg.apache.dubbo.demo.EventNotifyService%26methods%3Dget%26pid%3D79756%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1576456269728%26version%3D1.0.0
     * @param ephemeral 是否是临时节点
     * 1、如果不是临时节点，则在持久化节点集合persistentExistNodePath里缓存这个节点
     * 2、截取zk客户端上待创建的节点路径
     * 3、如果是一个持久化节点的话，检查zk服务器上是否已经存在该节点，具体检查交给具体的实现来做
     * 4、调用具体的客户端实现向zk服务器发起创建节点的请求
     */
    @Override
    public void create(String path, boolean ephemeral) {
        if (!ephemeral) {
            if(persistentExistNodePath.contains(path)){
                return;
            }
            if (checkExists(path)) {
                persistentExistNodePath.add(path);
                return;
            }
        }
        //截取zk客户端上待创建的节点路径，比如 provider: /dubbo/org.apache.dubbo.demo.StubService/providers
        int i = path.lastIndexOf('/');
        if (i > 0) {//
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path);
        } else {
            createPersistent(path);
            persistentExistNodePath.add(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    /***
     * 向指定的监听路径添加一个监听器，用于监听该节点下的子节点的变化
     * @param path 监听路径
     * @param listener 监听器
     * @return
     * 1、检查是否已经对该路径添加了监听器，如果有的话，则直接添加到这个路径对应的集合里
     * 2、检查该待加的监听器是否已经存在。如果不存在的话，则维护一个具体的客户端实现的ChildListener，用于监听path
     * 注意:
     *      这里listener是我们自定义的一个接口，会被映射到具体的客户端的具体实现
     */
    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        // 获得路径下的监听器集合
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        // 获得是否已经有该抽象监听器对应的具体监听器
        TargetChildListener targetListener = listeners.get(listener);
        // 监听器不存在，进行创建
        if (targetListener == null) {
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        // 向 Zookeeper ，真正发起监听
        //创建真正的 ChildListener 对象。因为，每个 Zookeeper 的库，实现不同。
        return addTargetChildListener(path, targetListener);
    }

    /**
     * 向path节点下添加一个节点节点数据变化的监听器 listener
     * @param path:    节点路径，路径下所有的子节点都会被监听
     * @param listener 监听节点数据比那话的监听器
     */
    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener, null);
    }

    /***
     * 向path节点下添加一个节点节点数据变化的监听器 listener
     * @param path:     节点路径，路径下所有的子节点都会被监听
     * @param listener 监听器
     * @param executor another thread
     * 1、这里listener是我们自定义的一个接口，会被映射到具体的客户端的具体实现
     */
    @Override
    public void addDataListener(String path, DataListener listener, Executor executor) {
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap == null) {
            listeners.putIfAbsent(path, new ConcurrentHashMap<DataListener, TargetDataListener>());
            dataListenerMap = listeners.get(path);
        }
        TargetDataListener targetListener = dataListenerMap.get(listener);
        if (targetListener == null) {
            dataListenerMap.putIfAbsent(listener, createTargetDataListener(path, listener));
            targetListener = dataListenerMap.get(listener);
        }
        addTargetDataListener(path, targetListener, executor);
    }

    /***
     * 移除某个节点路径下的对应的数据节点监听器
     * @param path 节点路径
     * @param listener 监听器
     */
    @Override
    public void removeDataListener(String path, DataListener listener ){
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetListener = dataListenerMap.remove(listener);
            if(targetListener != null){
                removeTargetDataListener(path, targetListener);
            }
        }
    }

    /**
     * 移除某个节点路径下的对应的子节点监听器
     * @param path 节点路径
     * @param listener 监听器
     */
    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    /***
     * 节点状态变化回调
     * @param state
     */
    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * 创建一个节点path，并初始节点的内容信息
     * @param path 节点路径
     * @param content 节点内容
     * @param ephemeral 是否是临时节点
     */
    @Override
    public void create(String path, String content, boolean ephemeral) {
        if (checkExists(path)) {
            delete(path);
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path, content);
        } else {
            createPersistent(path, content);
        }
    }

    @Override
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    protected abstract boolean checkExists(String path);

    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

    protected abstract String doGetContent(String path);

    /**
     * we invoke the zookeeper client to delete the node
     * @param path the node path
     */
    protected abstract void deletePath(String path);

}

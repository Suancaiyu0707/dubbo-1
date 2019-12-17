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
package org.apache.dubbo.remoting.zookeeper;

import org.apache.dubbo.common.URL;

import java.util.List;
import java.util.concurrent.Executor;

/***
 * 用于连接zk的客户端
 */
public interface ZookeeperClient {
    /***
     * 创建一个节点path
     * @param path 节点路径
     * @param ephemeral 是否是临时节点
     */
    void create(String path, boolean ephemeral);

    /***
     * 删除一个节点
     * @param path 节点路径
     */
    void delete(String path);

    /***
     * 获得指定路径的子节点列表
     * @param path 查找节点
     * @return 子节点列表
     */
    List<String> getChildren(String path);

    /***
     * 向指定节点下添加监听子节点的监听器
     * @param path 监听路径
     * @param listener 监听器
     * @return 子节点列表
     */
    List<String> addChildListener(String path, ChildListener listener);

    /**
     * 向节点添加监听节点信息变化的监听器
     * @param path:    节点路径，路径下所有的子节点都会被监听
     * @param listener 监听节点数据比那话的监听器
     */
    void addDataListener(String path, DataListener listener);

    /**
     * 向节点添加监听节点信息变化的监听器
     * @param path:     节点路径，路径下所有的子节点都会被监听
     * @param listener 监听器
     * @param executor another thread
     */
    void addDataListener(String path, DataListener listener, Executor executor);

    /***
     * 移除节点对应的监听器  listener
     * @param path 节点路径
     * @param listener 监听器
     */
    void removeDataListener(String path, DataListener listener);

    /***
     * 移除指定节点监听子节点的监听器
     * @param path 节点路径
     * @param listener 监听器
     */
    void removeChildListener(String path, ChildListener listener);

    /***
     * 添加一个监听状态的监听器
     * @param listener 监听器
     */
    void addStateListener(StateListener listener);

    void removeStateListener(StateListener listener);

    /***
     * 是否已连接
     * @return
     */
    boolean isConnected();

    void close();

    URL getUrl();

    void create(String path, String content, boolean ephemeral);

    String getContent(String path);

}

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
package org.apache.dubbo.common.extension.factory;

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

/**
 * SpiExtensionFactory，是ExtensionFactory的一种实现之一
 * 由AdaptiveExtensionFactory遍历统一按顺序调用：SpiExtensionFactory -> SpringExtensionFactory
 *
 */
public class SpiExtensionFactory implements ExtensionFactory {
     /***
     *
     * @param type 拓展类型
      *        eg: org.apache.dubbo.rpc.Protocol
     * @param name 拓展实现类的名称
      *        eg: protocol
     * @param <T>
     * @return
      * 1、判断拓展类型是否添加了SPI注解
      * 2、获得拓展类型绑定的ExtensionLoader
      * 3、返回拓展类型的cachedAdaptiveInstance属性值，cachedAdaptiveInstance什么时候不为空呢？
      *     拓展类型type下存在添加了@Adaptive注解的拓展实现类，则不为空，返回的就是这个默认的自适应实现类
      *
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {//判断type是否持有SPI注解
            ExtensionLoader<T> loader = ExtensionLoader.getExtensionLoader(type);
            //如果缓存的拓展节点不为空，表示已加载该拓展点
            if (!loader.getSupportedExtensions().isEmpty()) {
                //返回 自适应拓展实现类实例，可能为空
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}

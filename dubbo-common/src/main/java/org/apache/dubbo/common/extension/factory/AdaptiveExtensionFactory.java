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

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 * 负责拓展 Adaptive 实现类，会添加到 ExtensionLoader.cachedAdaptiveClass 属性中。
 */
@Adaptive // 用 Adaptive 表示这个一个默认的自适应实现
public class AdaptiveExtensionFactory implements ExtensionFactory {
    /**
     * 存放ExtensionFactory拓展类型的所有实现类，不包含AdaptiveExtensionFactory
     * 0 = {SpiExtensionFactory@2521}
     * 1 = {SpringExtensionFactory@2540}
     */
    private final List<ExtensionFactory> factories;

    /**
     * 1、AdaptiveExtensionFactory是ExtensionFactory默认的自适应类
     * 2、加载遍历所有的ExtensionFactory的ExtensionLoader
     * 3、遍历完所有的ExtensionLoader后会进行排序：
     *      这个排序很重要，它会把SpiExtensionFactory的排到最前面。这样调用getExtension会优先使用getExtension去查找默认的带有Adaptive注解的拓展实现
     */
    public AdaptiveExtensionFactory() {
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);//获得ExtensionFactory绑定的ExtensionLoader实例
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        for (String name : loader.getSupportedExtensions()) {//从ExtensionLoader获得这个拓展类的所有实现类并进行遍历
            list.add(loader.getExtension(name));//遍历每个实现类，并获得对应的实例，没有则创建
        }
        factories = Collections.unmodifiableList(list);
    }

    /***
     *
     * @param type 拓展类型
     * @param name 拓展实现类的名称
     * @param <T>
     * @return
     * 由于在AdaptiveExtensionFactory构造函数里进行排序了，所以就变成按顺序调用：SpiExtensionFactory -> SpringExtensionFactory
     * 1、先通过SpiExtensionFactory.getExtension 根据type和名称查找是否存在对应的拓展类型实现类
     * 2、如果通过SpiExtensionFactory找不到，则通过 SpringExtensionFactory 在容器里根据type和名称查找是否存在对应的拓展类型实现类
     *
     * 从这边，我们可以发现SpiExtensionFactory的优先级比SpringExtensionFactory高
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}

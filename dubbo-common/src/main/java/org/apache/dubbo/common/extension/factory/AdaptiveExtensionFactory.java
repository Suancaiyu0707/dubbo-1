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
@Adaptive
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
     * @param type object type.
     * @param name object name.
     * @param <T>
     * @return
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

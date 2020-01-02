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
package org.apache.dubbo.common.compiler.support;

import org.apache.dubbo.common.compiler.Compiler;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;

/**
 * AdaptiveCompiler. (SPI, Singleton, ThreadSafe)
 * 实现编译接口Compiler的自适应 Compiler 实现类
 */
@Adaptive //表示是一个自适应的实现类
public class AdaptiveCompiler implements Compiler {

    private static volatile String DEFAULT_COMPILER;

    /**
     * 该方法被 ApplicationConfig#setCompiler(compiler) 方法调用
     * 在 <dubbo:application compiler="" /> 配置下，可触发该方法。
     * @param compiler
     */
    public static void setDefaultCompiler(String compiler) {
        DEFAULT_COMPILER = compiler;
    }

    /***
     *
     * @param code 待编译的java代码
     * @param classLoader 编译完成后，加载类的类加载器
     * @return
     * 1、获得 Compiler 的 ExtensionLoader 对象。
     * 2、检查是否设置了默认的拓展名：
     *  设置了拓展名，则使用设置的拓展名，获得 Compiler 拓展对象
     *      可通过<dubbo:application compiler="" /> 配置
     *  没有设置拓展名，则获得默认的 Compiler 拓展对象（JavassistCompiler）
     * 3、调用真正的 Compiler 对象，动态编译代码。
     */
    @Override
    public Class<?> compile(String code, ClassLoader classLoader) {
        Compiler compiler;
        // 获得 Compiler 的 ExtensionLoader 对象。
        ExtensionLoader<Compiler> loader = ExtensionLoader.getExtensionLoader(Compiler.class);
        String name = DEFAULT_COMPILER; // copy reference
        if (name != null && name.length() > 0) {// 根据拓展名获得 Compiler 拓展对象
            compiler = loader.getExtension(name);
        } else {//如果没有设置拓展名，采用默认的 Compiler 拓展对象 ：JavassistCompiler
            compiler = loader.getDefaultExtension();
        }
        //调用真正的 Compiler 对象，动态编译代码。
        return compiler.compile(code, classLoader);
    }

}

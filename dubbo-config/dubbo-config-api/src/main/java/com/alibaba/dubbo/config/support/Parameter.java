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
package com.alibaba.dubbo.config.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Parameter
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Parameter {
    /***
     * 键名，默认是空，未指定的话取config的属性名称
     * @return
     */
    String key() default "";

    /***
     * 是否必输
     * @return
     */
    boolean required() default false;

    /***
     * 是否不包含，为true时会被忽略不拼接到URL上
     * @return
     */
    boolean excluded() default false;

    /***
     * 是否需要编码转义
     * @return
     */
    boolean escaped() default false;

    /***
     * 配置true的话会加到attributes里，一般在MethodConfig会配置，用于事件通知
     * @return
     */
    boolean attribute() default false;

    /***
     * 遇到同一属性的多次配置，是否需要拼接
     * @return
     */
    boolean append() default false;

}
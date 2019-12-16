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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * InjvmExporter
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    private final String key;

    private final Map<String, Exporter<?>> exporterMap;
    //jvm内部暴露只是暴露到本地内存里
    //invoker:interface org.apache.dubbo.demo.EventNotifyService -> injvm://127.0.0.1/org.apache.dubbo.demo.EventNotifyService?anyhost=true&bean.name=org.apache.dubbo.demo.EventNotifyService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=cn&interface=org.apache.dubbo.demo.EventNotifyService&methods=get&pid=34339&release=&revision=1.0.0&side=provider&timestamp=1575881105857&version=1.0.0
    //key:cn/org.apache.dubbo.demo.EventNotifyService:1.0.0
    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        exporterMap.put(key, this);
    }

    @Override
    public void unexport() {
        super.unexport();
        exporterMap.remove(key);
    }

}

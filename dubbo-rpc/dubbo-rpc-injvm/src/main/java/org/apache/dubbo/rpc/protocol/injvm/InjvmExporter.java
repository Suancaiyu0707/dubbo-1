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
    // 服务的全路径。eg：org.apache.dubbo.demo.StubService
    private final String key;
    /***
     * key：org.apache.dubbo.demo.StubService
     * value：InjvmExporter对象，对象属性
     *      key = "org.apache.dubbo.demo.StubService"
     *      exporterMap = {ConcurrentHashMap@4543}  size = 1
     *      logger = {FailsafeLogger@4562}
     *      invoker = {ProtocolFilterWrapper$1@4542} "interface org.apache.dubbo.demo.StubService -> injvm://127.0.0.1/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&bind.ip=220.250.64.225&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.StubService&methods=sayHello&pid=686&release=&side=provider&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1576737965762"
     *      unexported = false
     */
    private final Map<String, Exporter<?>> exporterMap;

    /**
     *  jvm内部暴露只是暴露到本地内存里，并没有通过NettyServer对外提供服务
     * @param invoker
     * @param key 本地注册的服务的全路径，eg：org.apache.dubbo.demo.StubService
     * @param exporterMap
     * @return
     */
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

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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;

import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;

/**
 * InjvmInvoker
 */
class InjvmInvoker<T> extends AbstractInvoker<T> {
    /**
     * 所引用的服务
     *  eg:org.apache.dubbo.demo.StubService
     */
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

    /***
     *
     * @param type 接口类型 eg：org.apache.dubbo.demo.StubService
     * @param url 引用的服务提供者的url
     * @param key  被引用的服务键，eg：org.apache.dubbo.demo.StubService
     * @param exporterMap 本地暴露的服务
     */
    InjvmInvoker(Class<T> type, URL url, String key, Map<String, Exporter<?>> exporterMap) {
        super(type, url);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    /***
     * 判断是否有 Exporter 对象
     * @return
     */
    @Override
    public boolean isAvailable() {
        //如果
        InjvmExporter<?> exporter = (InjvmExporter<?>) exporterMap.get(key);
        if (exporter == null) {
            return false;
        } else {
            return super.isAvailable();
        }
    }

    /***
     *
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    public Result doInvoke(Invocation invocation) throws Throwable {
        //检查
        Exporter<?> exporter = InjvmProtocol.getExporter(exporterMap, getUrl());
        if (exporter == null) {
            throw new RpcException("Service [" + key + "] not found.");
        }
        RpcContext.getContext().setRemoteAddress(LOCALHOST_VALUE, 0);
        return exporter.getInvoker().invoke(invocation);
    }
}

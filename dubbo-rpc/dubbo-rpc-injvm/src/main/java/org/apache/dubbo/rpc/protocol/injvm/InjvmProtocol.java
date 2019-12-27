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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;

/**
 * InjvmProtocol：这个协议比较特殊，不做端口打开操作，仅仅是把服务保存到内存而已。
 * 我们可以发现，服务本地注册的话，其实就是在本地维护一个hashMap内存：
 *      key：org.apache.dubbo.demo.StubService
 *      value：InjvmExporter对象，对象属性
 * 并没有向远程注册那样，需要注册到注册中心，并通过NettyServer将服务暴露出去
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    public static final String NAME = LOCAL_PROTOCOL;

    public static final int DEFAULT_PORT = 0;
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    /***
     * 检查本地是否有该exporter
     * @param map
     * @param key  服务暴露的url地址
     *      eg：injvm://127.0.0.1/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=81987&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1577351435363
     * @return
     */
    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        if (!key.getServiceKey().contains("*")) {
            result = map.get(key.getServiceKey());
        } else {
            if (CollectionUtils.isNotEmptyMap(map)) {
                for (Exporter<?> exporter : map.values()) {
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        result = exporter;
                        break;
                    }
                }
            }
        }

        if (result == null) {
            return null;
        } else if (ProtocolUtils.isGeneric(
                result.getInvoker().getUrl().getParameter(GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }
    //我们可以发现
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    /***
     * 将暴露的协议和引用绑定
     * @param serviceType 接口类型
     *                     eg：org.apache.dubbo.demo.StubService
     * @param url   被引用的服务的地址
     *                  eg：injvm://127.0.0.1/org.apache.dubbo.demo.StubService?anyhost=true&bean.name=org.apache.dubbo.demo.StubService&check=false&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&init=false&interface=org.apache.dubbo.demo.StubService&lazy=false&methods=sayHello&pid=81987&register.ip=220.250.64.225&release=&remote.application=&side=consumer&sticky=false&stub=org.apache.dubbo.demo.StubServiceStub&timestamp=1577351435363
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    /***
     * 根据消费端的服务引用配置，判断是否是本地引用
     *      url：
     * @param url
     * @return
     * 1、如果服务消费端配置了scope=local或者injvm=true,则返回true
     * 2、如果服务消费端配置了scope=remote，则肯定是远程调用，返回false
     * 3、如果服务消费端配置了generic=true，则是泛化调用，则必定是远程引用，所以返回false
     * 4、当本地已经有该 url对应的InjvmExporter 时，本地引用
     * 5、其它的情况返回false
     */
    public boolean isInjvmRefer(URL url) {
        String scope = url.getParameter(SCOPE_KEY);
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        // 如果服务消费端配置了scope=local或者injvm=true,则返回true
        if (SCOPE_LOCAL.equals(scope) || (url.getParameter(LOCAL_PROTOCOL, false))) {
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            return true;
        } else if (SCOPE_REMOTE.equals(scope)) {//如果服务消费端配置了scope=remote，则肯定是远程调用，返回false
            // it's declared as remote reference
            return false;
        } else if (url.getParameter(GENERIC_KEY, false)) {//如果服务消费端配置了generic=true，则是泛化调用，则必定是远程引用，所以返回false
            // generic invocation is not local reference
            return false;
        } else if (getExporter(exporterMap, url) != null) { // 当本地已经有该 Exporter 时，本地引用
            return true;
        } else {
            return false;
        }
    }
}

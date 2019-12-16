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
package org.apache.dubbo.rpc.model;

import org.apache.dubbo.common.context.FrameworkExt;
import org.apache.dubbo.common.context.LifecycleAdapter;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.config.service.ReferenceConfigBase;
import org.apache.dubbo.config.service.ServiceConfigBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ServiceRepository extends LifecycleAdapter implements FrameworkExt {

    public static final String NAME = "repository";

    // services
    private ConcurrentMap<String, ServiceDescriptor> services = new ConcurrentHashMap<>();

    // consumers
    private ConcurrentMap<String, ConsumerModel> consumers = new ConcurrentHashMap<>();

    // providers
    private ConcurrentMap<String, ProviderModel> providers = new ConcurrentHashMap<>();

    public ServiceRepository() {
        Set<BuiltinServiceDetector> builtinServices
                = ExtensionLoader.getExtensionLoader(BuiltinServiceDetector.class).getSupportedExtensionInstances();
        if (CollectionUtils.isNotEmpty(builtinServices)) {
            for (BuiltinServiceDetector service : builtinServices) {
                registerService(service.getService());
            }
        }
    }

    public ServiceDescriptor registerService(Class<?> interfaceClazz) {
        return services.computeIfAbsent(interfaceClazz.getName(),
                _k -> new ServiceDescriptor(interfaceClazz));
    }

    /**
     * See {@link #registerService(Class)}
     * <p>
     * we assume:
     * 1. services with different interfaces are not allowed to have the same path.
     * 2. services share the same interface but has different group/version can share the same path.
     * 3. path's default value is the name of the interface.
     *
     * @param path
     * @param interfaceClass
     * @return
     * 将服务的描述信息保存到内存里
     */
    public ServiceDescriptor registerService(String path, Class<?> interfaceClass) {
        ServiceDescriptor serviceDescriptor = registerService(interfaceClass);
        // if path is different with interface name, add extra path mapping
        if (!interfaceClass.getName().equals(path)) {//如果path不是服务名，则添加额外的映射关系
            services.putIfAbsent(path, serviceDescriptor);
        }
        return serviceDescriptor;
    }

    /***
     * 更新本地缓存consumers
     * @param serviceKey
     * @param attributes
     * @param serviceModel
     * @param rc
     * @param proxy
     * @param serviceMetadata
     */
    public void registerConsumer(String serviceKey,
                                 Map<String, Object> attributes,
                                 ServiceDescriptor serviceModel,
                                 ReferenceConfigBase<?> rc,
                                 Object proxy,
                                 ServiceMetadata serviceMetadata) {
        consumers.computeIfAbsent(
                serviceKey,
                _k -> new ConsumerModel(
                        serviceMetadata.getServiceKey(),//org.apache.dubbo.demo.DemoService
                        proxy,
                        serviceModel,
                        rc,
                        attributes,
                        serviceMetadata
                )
        );
    }

    /***
     * 维护全局变量providers
     * @param serviceKey 服务的key interfaceName+group+version
     * @param serviceInstance
     * @param serviceModel
     * @param serviceConfig
     * @param serviceMetadata
     */
    public void registerProvider(String serviceKey,
                                 Object serviceInstance,
                                 ServiceDescriptor serviceModel,
                                 ServiceConfigBase<?> serviceConfig,
                                 ServiceMetadata serviceMetadata) {
        /***
         * key = "cn/org.apache.dubbo.demo.EventNotifyService:1.0.0"
         * value = {ProviderModel@3643}
         *  serviceKey = "cn/org.apache.dubbo.demo.EventNotifyService:1.0.0"
         *  serviceInstance = {EventNotifyServiceImpl@3220}
         *  serviceModel = {ServiceDescriptor@3570}
         *  serviceConfig = {ServiceBean@3207} "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="true" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@1b822fcc" generic="false" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" group="cn" dynamic="true" deprecated="false" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
         *  urls = {ArrayList@3644}  size = 0
         *  serviceMetadata = {ServiceMetadata@3221}
         *  methods = {HashMap@3645}  size = 1
         */
        providers.computeIfAbsent(
                serviceKey,
                _k -> new ProviderModel(
                        serviceKey,
                        serviceInstance,
                        serviceModel,
                        serviceConfig,
                        serviceMetadata
                )
        );
    }

    public List<ServiceDescriptor> getAllServices() {
        return Collections.unmodifiableList(new ArrayList<>(services.values()));
    }

    public ServiceDescriptor lookupService(String interfaceName) {
        return services.get(interfaceName);
    }

    public MethodDescriptor lookupMethod(String interfaceName, String methodName) {
        ServiceDescriptor serviceDescriptor = lookupService(interfaceName);
        if (serviceDescriptor == null) {
            return null;
        }
        Set<MethodDescriptor> methods = serviceDescriptor.getMethods(methodName);
        if (CollectionUtils.isEmpty(methods)) {
            return null;
        }
        return methods.iterator().next();
    }

    public List<ProviderModel> getExportedServices() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    public ProviderModel lookupExportedService(String serviceKey) {
        return providers.get(serviceKey);
    }

    public List<ConsumerModel> getReferredServices() {
        return Collections.unmodifiableList(new ArrayList<>(consumers.values()));
    }

    public ConsumerModel lookupReferredService(String serviceKey) {
        return consumers.get(serviceKey);
    }

    @Override
    public void destroy() throws IllegalStateException {
        // currently works for unit test
        services.clear();
        consumers.clear();
        providers.clear();
    }
}

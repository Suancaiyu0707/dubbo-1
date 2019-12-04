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
package org.apache.dubbo.config.bootstrap;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.config.configcenter.wrapper.CompositeDynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.ShutdownHookCallback;
import org.apache.dubbo.common.lang.ShutdownHookCallbacks;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.concurrent.ScheduledCompletableFuture;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.config.bootstrap.builders.ApplicationBuilder;
import org.apache.dubbo.config.bootstrap.builders.ConsumerBuilder;
import org.apache.dubbo.config.bootstrap.builders.ProtocolBuilder;
import org.apache.dubbo.config.bootstrap.builders.ProviderBuilder;
import org.apache.dubbo.config.bootstrap.builders.ReferenceBuilder;
import org.apache.dubbo.config.bootstrap.builders.RegistryBuilder;
import org.apache.dubbo.config.bootstrap.builders.ServiceBuilder;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.metadata.ConfigurableMetadataServiceExporter;
import org.apache.dubbo.config.service.ServiceConfigBase;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.event.EventListener;
import org.apache.dubbo.event.GenericEventListener;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.MetadataServiceExporter;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.registry.client.DefaultServiceInstance;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceDiscoveryRegistry;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.config.configcenter.DynamicConfiguration.getDynamicConfiguration;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.StringUtils.isNotEmpty;
import static org.apache.dubbo.metadata.WritableMetadataService.getExtension;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.setMetadataStorageType;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;

/**
 * See {@link ApplicationModel} and {@link ExtensionLoader} for why this class is designed to be singleton.
 *
 * The bootstrap class of Dubbo
 *
 * Get singleton instance by calling static method {@link #getInstance()}.
 * Designed as singleton because some classes inside Dubbo, such as ExtensionLoader, are designed only for one instance per process.
 *
 * @since 2.7.4
 */
public class DubboBootstrap extends GenericEventListener {

    public static final String DEFAULT_REGISTRY_ID = "REGISTRY#DEFAULT";

    public static final String DEFAULT_PROTOCOL_ID = "PROTOCOL#DEFAULT";

    public static final String DEFAULT_SERVICE_ID = "SERVICE#DEFAULT";

    public static final String DEFAULT_REFERENCE_ID = "REFERENCE#DEFAULT";

    public static final String DEFAULT_PROVIDER_ID = "PROVIDER#DEFAULT";

    public static final String DEFAULT_CONSUMER_ID = "CONSUMER#DEFAULT";

    private static final String NAME = DubboBootstrap.class.getSimpleName();

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static DubboBootstrap instance;

    private final AtomicBoolean awaited = new AtomicBoolean(false);

    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();

    private final ExecutorService executorService = newSingleThreadExecutor();

    private final EventDispatcher eventDispatcher = EventDispatcher.getDefaultExtension();

    private final ExecutorRepository executorRepository = ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
    /***
     * 0 = {HashMap$Node@2567} "registry" -> " size = 1"
     *  key = "registry"
     *  value = {HashMap@2573}  size = 1
     *   0 = {HashMap$Node@2586} "org.apache.dubbo.config.RegistryConfig" -> "<dubbo:registry address="zookeeper://127.0.0.1:2181" protocol="zookeeper" port="2181" valid="true" id="org.apache.dubbo.config.RegistryConfig" prefix="dubbo.registry" />"
     * 1 = {HashMap$Node@2568} "protocol" -> " size = 1"
     *  key = "protocol"
     *  value = {HashMap@2575}  size = 1
     *   0 = {HashMap$Node@2594} "dubbo" -> "<dubbo:protocol name="dubbo" valid="true" id="dubbo" prefix="dubbo.protocol" />"
     * 2 = {HashMap$Node@2569} "method" -> " size = 1"
     *  key = "method"
     *  value = {HashMap@2577}  size = 1
     *   0 = {HashMap$Node@2601} "addListener" -> "<dubbo:method name="addListener" prefix="dubbo.null.addListener" id="addListener" valid="true" />"
     * 3 = {HashMap$Node@2570} "application" -> " size = 1"
     *  key = "application"
     *  value = {HashMap@2579}  size = 1
     * 4 = {HashMap$Node@2571} "service" -> " size = 8"
     *  key = "service"
     *  value = {HashMap@2581}  size = 8
     *   0 = {HashMap$Node@2607} "org.apache.dubbo.demo.EventNotifyService" -> "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="false" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@363042d7" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" dynamic="true" deprecated="false" group="cn" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
     *   1 = {HashMap$Node@2608} "org.apache.dubbo.demo.AsyncService2" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService2" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService2" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl2@366ac49b" interface="org.apache.dubbo.demo.AsyncService2" uniqueServiceName="org.apache.dubbo.demo.AsyncService2" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService2" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService2" valid="true" />"
     *   2 = {HashMap$Node@2609} "org.apache.dubbo.demo.CallbackService" -> "<dubbo:service beanName="org.apache.dubbo.demo.CallbackService" exported="false" unexported="false" path="org.apache.dubbo.demo.CallbackService" ref="org.apache.dubbo.demo.provider.CallbackServiceImpl@6ad59d92" interface="org.apache.dubbo.demo.CallbackService" uniqueServiceName="org.apache.dubbo.demo.CallbackService" prefix="dubbo.service.org.apache.dubbo.demo.CallbackService" dynamic="true" deprecated="false" connections="1" callbacks="1000" id="org.apache.dubbo.demo.CallbackService" valid="true" />"
     *   3 = {HashMap$Node@2610} "org.apache.dubbo.demo.AsyncService" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl@56f0cc85" interface="org.apache.dubbo.demo.AsyncService" uniqueServiceName="org.apache.dubbo.demo.AsyncService" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService" valid="true" />"
     *   4 = {HashMap$Node@2611} "org.apache.dubbo.demo.StubService" -> "<dubbo:service beanName="org.apache.dubbo.demo.StubService" exported="false" unexported="false" path="org.apache.dubbo.demo.StubService" ref="org.apache.dubbo.demo.provider.StubServiceImpl@62e20a76" interface="org.apache.dubbo.demo.StubService" uniqueServiceName="org.apache.dubbo.demo.StubService" prefix="dubbo.service.org.apache.dubbo.demo.StubService" dynamic="true" deprecated="false" stub="org.apache.dubbo.demo.StubServiceStub" id="org.apache.dubbo.demo.StubService" valid="true" />"
     *   5 = {HashMap$Node@2612} "org.apache.dubbo.demo.MockService" -> "<dubbo:service beanName="org.apache.dubbo.demo.MockService" exported="false" unexported="false" path="org.apache.dubbo.demo.MockService" ref="org.apache.dubbo.demo.provider.MockServiceImpl@2cc44ad" interface="org.apache.dubbo.demo.MockService" uniqueServiceName="org.apache.dubbo.demo.MockService" prefix="dubbo.service.org.apache.dubbo.demo.MockService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.MockService" valid="true" />"
     *   6 = {HashMap$Node@2613} "org.apache.dubbo.demo.DemoService" -> "<dubbo:service beanName="org.apache.dubbo.demo.DemoService" exported="false" unexported="false" path="org.apache.dubbo.demo.DemoService" ref="org.apache.dubbo.demo.provider.DemoServiceImpl@44b3606b" interface="org.apache.dubbo.demo.DemoService" uniqueServiceName="org.apache.dubbo.demo.DemoService" prefix="dubbo.service.org.apache.dubbo.demo.DemoService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.DemoService" valid="true" />"
     *   7 = {HashMap$Node@2614} "org.apache.dubbo.demo.InjvmService" -> "<dubbo:service beanName="org.apache.dubbo.demo.InjvmService" exported="false" unexported="false" path="org.apache.dubbo.demo.InjvmService" ref="org.apache.dubbo.demo.provider.InjvmServiceImpl@1477089c" interface="org.apache.dubbo.demo.InjvmService" uniqueServiceName="org.apache.dubbo.demo.InjvmService" prefix="dubbo.service.org.apache.dubbo.demo.InjvmService" dynamic="true" deprecated="false" protocolIds="injvm" id="org.apache.dubbo.demo.InjvmService" valid="true" />"
     */
    private final ConfigManager configManager;

    private final Environment environment;

    private ReferenceConfigCache cache;

    private volatile boolean exportAsync;

    private volatile boolean referAsync;

    private AtomicBoolean initialized = new AtomicBoolean(false);

    private AtomicBoolean started = new AtomicBoolean(false);

    private volatile ServiceInstance serviceInstance;

    private volatile MetadataService metadataService;

    private volatile MetadataServiceExporter metadataServiceExporter;

    private List<ServiceConfigBase<?>> exportedServices = new ArrayList<>();

    private List<Future<?>> asyncExportingFutures = new ArrayList<>();

    private List<CompletableFuture<Object>> asyncReferringFutures = new ArrayList<>();

    /**
     * See {@link ApplicationModel} and {@link ExtensionLoader} for why DubboBootstrap is designed to be singleton.
     */
    public static synchronized DubboBootstrap getInstance() {
        if (instance == null) {
            instance = new DubboBootstrap();
        }
        return instance;
    }

    private DubboBootstrap() {
        /***
         * 0 = {HashMap$Node@2567} "registry" -> " size = 1"
         *  key = "registry"
         *  value = {HashMap@2573}  size = 1
         *   0 = {HashMap$Node@2586} "org.apache.dubbo.config.RegistryConfig" -> "<dubbo:registry address="zookeeper://127.0.0.1:2181" protocol="zookeeper" port="2181" valid="true" id="org.apache.dubbo.config.RegistryConfig" prefix="dubbo.registry" />"
         * 1 = {HashMap$Node@2568} "protocol" -> " size = 1"
         *  key = "protocol"
         *  value = {HashMap@2575}  size = 1
         *   0 = {HashMap$Node@2594} "dubbo" -> "<dubbo:protocol name="dubbo" valid="true" id="dubbo" prefix="dubbo.protocol" />"
         * 2 = {HashMap$Node@2569} "method" -> " size = 1"
         *  key = "method"
         *  value = {HashMap@2577}  size = 1
         *   0 = {HashMap$Node@2601} "addListener" -> "<dubbo:method name="addListener" prefix="dubbo.null.addListener" id="addListener" valid="true" />"
         * 3 = {HashMap$Node@2570} "application" -> " size = 1"
         *  key = "application"
         *  value = {HashMap@2579}  size = 1
         * 4 = {HashMap$Node@2571} "service" -> " size = 8"
         *  key = "service"
         *  value = {HashMap@2581}  size = 8
         *   0 = {HashMap$Node@2607} "org.apache.dubbo.demo.EventNotifyService" -> "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="false" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@363042d7" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" dynamic="true" deprecated="false" group="cn" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
         *   1 = {HashMap$Node@2608} "org.apache.dubbo.demo.AsyncService2" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService2" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService2" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl2@366ac49b" interface="org.apache.dubbo.demo.AsyncService2" uniqueServiceName="org.apache.dubbo.demo.AsyncService2" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService2" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService2" valid="true" />"
         *   2 = {HashMap$Node@2609} "org.apache.dubbo.demo.CallbackService" -> "<dubbo:service beanName="org.apache.dubbo.demo.CallbackService" exported="false" unexported="false" path="org.apache.dubbo.demo.CallbackService" ref="org.apache.dubbo.demo.provider.CallbackServiceImpl@6ad59d92" interface="org.apache.dubbo.demo.CallbackService" uniqueServiceName="org.apache.dubbo.demo.CallbackService" prefix="dubbo.service.org.apache.dubbo.demo.CallbackService" dynamic="true" deprecated="false" connections="1" callbacks="1000" id="org.apache.dubbo.demo.CallbackService" valid="true" />"
         *   3 = {HashMap$Node@2610} "org.apache.dubbo.demo.AsyncService" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl@56f0cc85" interface="org.apache.dubbo.demo.AsyncService" uniqueServiceName="org.apache.dubbo.demo.AsyncService" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService" valid="true" />"
         *   4 = {HashMap$Node@2611} "org.apache.dubbo.demo.StubService" -> "<dubbo:service beanName="org.apache.dubbo.demo.StubService" exported="false" unexported="false" path="org.apache.dubbo.demo.StubService" ref="org.apache.dubbo.demo.provider.StubServiceImpl@62e20a76" interface="org.apache.dubbo.demo.StubService" uniqueServiceName="org.apache.dubbo.demo.StubService" prefix="dubbo.service.org.apache.dubbo.demo.StubService" dynamic="true" deprecated="false" stub="org.apache.dubbo.demo.StubServiceStub" id="org.apache.dubbo.demo.StubService" valid="true" />"
         *   5 = {HashMap$Node@2612} "org.apache.dubbo.demo.MockService" -> "<dubbo:service beanName="org.apache.dubbo.demo.MockService" exported="false" unexported="false" path="org.apache.dubbo.demo.MockService" ref="org.apache.dubbo.demo.provider.MockServiceImpl@2cc44ad" interface="org.apache.dubbo.demo.MockService" uniqueServiceName="org.apache.dubbo.demo.MockService" prefix="dubbo.service.org.apache.dubbo.demo.MockService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.MockService" valid="true" />"
         *   6 = {HashMap$Node@2613} "org.apache.dubbo.demo.DemoService" -> "<dubbo:service beanName="org.apache.dubbo.demo.DemoService" exported="false" unexported="false" path="org.apache.dubbo.demo.DemoService" ref="org.apache.dubbo.demo.provider.DemoServiceImpl@44b3606b" interface="org.apache.dubbo.demo.DemoService" uniqueServiceName="org.apache.dubbo.demo.DemoService" prefix="dubbo.service.org.apache.dubbo.demo.DemoService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.DemoService" valid="true" />"
         *   7 = {HashMap$Node@2614} "org.apache.dubbo.demo.InjvmService" -> "<dubbo:service beanName="org.apache.dubbo.demo.InjvmService" exported="false" unexported="false" path="org.apache.dubbo.demo.InjvmService" ref="org.apache.dubbo.demo.provider.InjvmServiceImpl@1477089c" interface="org.apache.dubbo.demo.InjvmService" uniqueServiceName="org.apache.dubbo.demo.InjvmService" prefix="dubbo.service.org.apache.dubbo.demo.InjvmService" dynamic="true" deprecated="false" protocolIds="injvm" id="org.apache.dubbo.demo.InjvmService" valid="true" />"
         */
        configManager = ApplicationModel.getConfigManager();//创建一个配置管理器ConfigManager
        environment = ApplicationModel.getEnvironment();//初始化创建一个Environment实例
        //添加一个钩子函数，当jvm退出时回调刚方法进行销毁
        ShutdownHookCallbacks.INSTANCE.addCallback(new ShutdownHookCallback() {
            @Override
            public void callback() throws Throwable {
                DubboBootstrap.this.destroy();
            }
        });
    }

    public void registerShutdownHook() {
        DubboShutdownHook.getDubboShutdownHook().register();
    }

    private boolean isOnlyRegisterProvider() {
        Boolean registerConsumer = getApplication().getRegisterConsumer();
        return registerConsumer == null || !registerConsumer;
    }

    private String getMetadataType() {
        String type = getApplication().getMetadataType();//从Application中获取 MetaType
        if (StringUtils.isEmpty(type)) {
            type = DEFAULT_METADATA_STORAGE_TYPE;//default
        }
        return type;
    }

    public DubboBootstrap metadataReport(MetadataReportConfig metadataReportConfig) {
        configManager.addMetadataReport(metadataReportConfig);
        return this;
    }

    public DubboBootstrap metadataReports(List<MetadataReportConfig> metadataReportConfigs) {
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            return this;
        }

        configManager.addMetadataReports(metadataReportConfigs);
        return this;
    }

    // {@link ApplicationConfig} correlative methods

    /**
     * Set the name of application
     *
     * @param name the name of application
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(String name) {
        return application(name, builder -> {
            // DO NOTHING
        });
    }

    /**
     * Set the name of application and it's future build
     *
     * @param name            the name of application
     * @param consumerBuilder {@link ApplicationBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(String name, Consumer<ApplicationBuilder> consumerBuilder) {
        ApplicationBuilder builder = createApplicationBuilder(name);
        consumerBuilder.accept(builder);
        return application(builder.build());
    }

    /**
     * Set the {@link ApplicationConfig}
     *
     * @param applicationConfig the {@link ApplicationConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap application(ApplicationConfig applicationConfig) {
        configManager.setApplication(applicationConfig);
        return this;
    }


    // {@link RegistryConfig} correlative methods

    /**
     * Add an instance of {@link RegistryConfig} with {@link #DEFAULT_REGISTRY_ID default ID}
     *
     * @param consumerBuilder the {@link Consumer} of {@link RegistryBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(Consumer<RegistryBuilder> consumerBuilder) {
        return registry(DEFAULT_REGISTRY_ID, consumerBuilder);
    }

    /**
     * Add an instance of {@link RegistryConfig} with the specified ID
     *
     * @param id              the {@link RegistryConfig#getId() id}  of {@link RegistryConfig}
     * @param consumerBuilder the {@link Consumer} of {@link RegistryBuilder}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(String id, Consumer<RegistryBuilder> consumerBuilder) {
        RegistryBuilder builder = createRegistryBuilder(id);
        consumerBuilder.accept(builder);
        return registry(builder.build());
    }

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param registryConfig an instance of {@link RegistryConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registry(RegistryConfig registryConfig) {
        configManager.addRegistry(registryConfig);
        return this;
    }

    /**
     * Add an instance of {@link RegistryConfig}
     *
     * @param registryConfigs the multiple instances of {@link RegistryConfig}
     * @return current {@link DubboBootstrap} instance
     */
    public DubboBootstrap registries(List<RegistryConfig> registryConfigs) {
        if (CollectionUtils.isEmpty(registryConfigs)) {
            return this;
        }
        registryConfigs.forEach(this::registry);
        return this;
    }


    // {@link ProtocolConfig} correlative methods
    public DubboBootstrap protocol(Consumer<ProtocolBuilder> consumerBuilder) {
        return protocol(DEFAULT_PROTOCOL_ID, consumerBuilder);
    }

    public DubboBootstrap protocol(String id, Consumer<ProtocolBuilder> consumerBuilder) {
        ProtocolBuilder builder = createProtocolBuilder(id);
        consumerBuilder.accept(builder);
        return protocol(builder.build());
    }

    public DubboBootstrap protocol(ProtocolConfig protocolConfig) {
        return protocols(asList(protocolConfig));
    }

    public DubboBootstrap protocols(List<ProtocolConfig> protocolConfigs) {
        if (CollectionUtils.isEmpty(protocolConfigs)) {
            return this;
        }
        configManager.addProtocols(protocolConfigs);
        return this;
    }

    // {@link ServiceConfig} correlative methods
    public <S> DubboBootstrap service(Consumer<ServiceBuilder<S>> consumerBuilder) {
        return service(DEFAULT_SERVICE_ID, consumerBuilder);
    }

    public <S> DubboBootstrap service(String id, Consumer<ServiceBuilder<S>> consumerBuilder) {
        ServiceBuilder builder = createServiceBuilder(id);
        consumerBuilder.accept(builder);
        return service(builder.build());
    }

    public DubboBootstrap service(ServiceConfig<?> serviceConfig) {
        configManager.addService(serviceConfig);
        return this;
    }

    public DubboBootstrap services(List<ServiceConfig> serviceConfigs) {
        if (CollectionUtils.isEmpty(serviceConfigs)) {
            return this;
        }
        serviceConfigs.forEach(configManager::addService);
        return this;
    }

    // {@link Reference} correlative methods
    public <S> DubboBootstrap reference(Consumer<ReferenceBuilder<S>> consumerBuilder) {
        return reference(DEFAULT_REFERENCE_ID, consumerBuilder);
    }

    public <S> DubboBootstrap reference(String id, Consumer<ReferenceBuilder<S>> consumerBuilder) {
        ReferenceBuilder builder = createReferenceBuilder(id);
        consumerBuilder.accept(builder);
        return reference(builder.build());
    }

    public DubboBootstrap reference(ReferenceConfig<?> referenceConfig) {
        configManager.addReference(referenceConfig);
        return this;
    }

    public DubboBootstrap references(List<ReferenceConfig> referenceConfigs) {
        if (CollectionUtils.isEmpty(referenceConfigs)) {
            return this;
        }

        referenceConfigs.forEach(configManager::addReference);
        return this;
    }

    // {@link ProviderConfig} correlative methods
    public DubboBootstrap provider(Consumer<ProviderBuilder> builderConsumer) {
        return provider(DEFAULT_PROVIDER_ID, builderConsumer);
    }

    public DubboBootstrap provider(String id, Consumer<ProviderBuilder> builderConsumer) {
        ProviderBuilder builder = createProviderBuilder(id);
        builderConsumer.accept(builder);
        return provider(builder.build());
    }

    public DubboBootstrap provider(ProviderConfig providerConfig) {
        return providers(asList(providerConfig));
    }

    public DubboBootstrap providers(List<ProviderConfig> providerConfigs) {
        if (CollectionUtils.isEmpty(providerConfigs)) {
            return this;
        }

        providerConfigs.forEach(configManager::addProvider);
        return this;
    }

    // {@link ConsumerConfig} correlative methods
    public DubboBootstrap consumer(Consumer<ConsumerBuilder> builderConsumer) {
        return consumer(DEFAULT_CONSUMER_ID, builderConsumer);
    }

    public DubboBootstrap consumer(String id, Consumer<ConsumerBuilder> builderConsumer) {
        ConsumerBuilder builder = createConsumerBuilder(id);
        builderConsumer.accept(builder);
        return consumer(builder.build());
    }

    public DubboBootstrap consumer(ConsumerConfig consumerConfig) {
        return consumers(asList(consumerConfig));
    }

    public DubboBootstrap consumers(List<ConsumerConfig> consumerConfigs) {
        if (CollectionUtils.isEmpty(consumerConfigs)) {
            return this;
        }

        consumerConfigs.forEach(configManager::addConsumer);
        return this;
    }

    // {@link ConfigCenterConfig} correlative methods
    public DubboBootstrap configCenter(ConfigCenterConfig configCenterConfig) {
        return configCenters(asList(configCenterConfig));
    }

    public DubboBootstrap configCenters(List<ConfigCenterConfig> configCenterConfigs) {
        if (CollectionUtils.isEmpty(configCenterConfigs)) {
            return this;
        }
        configManager.addConfigCenters(configCenterConfigs);
        return this;
    }

    public DubboBootstrap monitor(MonitorConfig monitor) {
        configManager.setMonitor(monitor);
        return this;
    }

    public DubboBootstrap metrics(MetricsConfig metrics) {
        configManager.setMetrics(metrics);
        return this;
    }

    public DubboBootstrap module(ModuleConfig module) {
        configManager.setModule(module);
        return this;
    }

    public DubboBootstrap ssl(SslConfig sslConfig) {
        configManager.setSsl(sslConfig);
        return this;
    }

    public DubboBootstrap cache(ReferenceConfigCache cache) {
        this.cache = cache;
        return this;
    }

    public ReferenceConfigCache getCache() {
        if (cache == null) {
            cache = ReferenceConfigCache.getCache();
        }
        return cache;
    }

    public DubboBootstrap exportAsync() {
        this.exportAsync = true;
        return this;
    }

    public DubboBootstrap referAsync() {
        this.referAsync = true;
        return this;
    }

    @Deprecated
    public void init() {
        initialize();
    }

    /**
     * Initialize
     */
    private void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        ApplicationModel.iniFrameworkExts();
        //检查配置中心(如有有的话)
        startConfigCenter();
        //如果没有配置config-center的话，则用registry作为配置中心
        useRegistryAsConfigCenterIfNecessary();

        startMetadataReport();
        //如果配置了远程的配置中心，检查远程配置中心的RegistryIds和ProtocolIds
        loadRemoteConfigs();
        //检查各个配置标签的命名规范和长度
        checkGlobalConfigs();
        //默认的 metadataService=InMemoryWritableMetadataService
        initMetadataService();
        //this.metadataServiceExporter = new ConfigurableMetadataServiceExporter(InMemoryWritableMetadataService);
        initMetadataServiceExporter();
        //把自身作为一个监听器添加到 eventDispatcher
        initEventListener();

        if (logger.isInfoEnabled()) {
            logger.info(NAME + " has been initialized!");
        }
    }

    private void checkGlobalConfigs() {
        // 检查</appliation>，没有则初始化一个
        ConfigValidationUtils.validateApplicationConfig(getApplication());
        // check Config Center
        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();
        if (CollectionUtils.isNotEmpty(configCenters)) {
            for (ConfigCenterConfig configCenterConfig : configCenters) {
                ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            }
        }
        // 检查 <meta-config>标签
        Collection<MetadataReportConfig> metadatas = configManager.getMetadataConfigs();
        for (MetadataReportConfig metadataReportConfig : metadatas) {
            ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
        }
        // 检查 <Monitor>标签，没有就初始化一个
        ConfigValidationUtils.validateMonitorConfig(getMonitor());
        // 检查 MetricsConfig标签，没有就初始化一个
        ConfigValidationUtils.validateMetricsConfig(getMetrics());
        // 检查 ModuleConfig标签，没有就初始化一个
        ConfigValidationUtils.validateModuleConfig(getModule());
        // 检查 SslConfig标签，没有就初始化一个
        ConfigValidationUtils.validateSslConfig(getSsl());
    }
    //启动配置中心
    private void startConfigCenter() {
        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();//获得配置中心
        //如果配置中心不为空（默认没有配置中心的话，则使用注册中心作为配置中心）
        if (CollectionUtils.isNotEmpty(configCenters)) {
            CompositeDynamicConfiguration compositeDynamicConfiguration = new CompositeDynamicConfiguration();
            for (ConfigCenterConfig configCenter : configCenters) {
                configCenter.refresh();
                ConfigValidationUtils.validateConfigCenterConfig(configCenter);
                compositeDynamicConfiguration.addConfiguration(prepareEnvironment(configCenter));
            }
            environment.setDynamicConfiguration(compositeDynamicConfiguration);
        }
        configManager.refreshAll();
    }

    private void startMetadataReport() {
        ApplicationConfig applicationConfig = getApplication();

        String metadataType = applicationConfig.getMetadataType();
        // FIXME, multiple metadata config support.
        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                throw new IllegalStateException("No MetadataConfig found, you must specify the remote Metadata Center address when 'metadata=remote' is enabled.");
            }
            return;
        }
        MetadataReportConfig metadataReportConfig = metadataReportConfigs.iterator().next();
        ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
        if (!metadataReportConfig.isValid()) {
            return;
        }

        MetadataReportInstance.init(metadataReportConfig.toUrl());
    }

    /**
     * For compatibility purpose, use registry as the default config center when the registry protocol is zookeeper and
     * there's no config center specified explicitly.
     */
    private void useRegistryAsConfigCenterIfNecessary() {
        // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
        if (environment.getDynamicConfiguration().isPresent()) {
            return;
        }
        //检查时否使用配置中心
        if (CollectionUtils.isNotEmpty(configManager.getConfigCenters())) {
            return;
        }
        //使用注册中心初始化一个配置中心
        configManager.getDefaultRegistries().stream()
                .filter(registryConfig -> registryConfig.getUseAsConfigCenter() == null || registryConfig.getUseAsConfigCenter())
                .forEach(registryConfig -> {
                    String protocol = registryConfig.getProtocol();
                    String id = "config-center-" + protocol + "-" + registryConfig.getPort();
                    ConfigCenterConfig cc = new ConfigCenterConfig();
                    cc.setId(id);
                    if (cc.getParameters() == null) {
                        cc.setParameters(new HashMap<>());
                    }
                    if (registryConfig.getParameters() != null) {
                        cc.getParameters().putAll(registryConfig.getParameters());
                    }
                    cc.getParameters().put(CLIENT_KEY, registryConfig.getClient());
                    cc.setProtocol(registryConfig.getProtocol());
                    cc.setAddress(registryConfig.getAddress());
                    cc.setNamespace(registryConfig.getGroup());
                    cc.setUsername(registryConfig.getUsername());
                    cc.setPassword(registryConfig.getPassword());
                    cc.setHighestPriority(false);//<dubbo:config-center address="zookeeper://127.0.0.1:2181" protocol="zookeeper" check="true" timeout="3000" group="dubbo" configFile="dubbo.properties" highestPriority="false" valid="true" id="config-center-zookeeper-2181" prefix="dubbo.config-center" />
                    configManager.addConfigCenter(cc);
                });
        startConfigCenter();
    }
    //检查远程配置中心的RegistryIds和ProtocolIds
    private void loadRemoteConfigs() {
        // registry ids to registry configs
        List<RegistryConfig> tmpRegistries = new ArrayList<>();
        Set<String> registryIds = configManager.getRegistryIds();
        registryIds.forEach(id -> {
            if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                tmpRegistries.add(configManager.getRegistry(id).orElseGet(() -> {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setId(id);
                    registryConfig.refresh();
                    return registryConfig;
                }));
            }
        });

        configManager.addRegistries(tmpRegistries);

        // protocol ids to protocol configs
        List<ProtocolConfig> tmpProtocols = new ArrayList<>();
        Set<String> protocolIds = configManager.getProtocolIds();
        protocolIds.forEach(id -> {
            if (tmpProtocols.stream().noneMatch(prot -> prot.getId().equals(id))) {
                tmpProtocols.add(configManager.getProtocol(id).orElseGet(() -> {
                    ProtocolConfig protocolConfig = new ProtocolConfig();
                    protocolConfig.setId(id);
                    protocolConfig.refresh();
                    return protocolConfig;
                }));
            }
        });

        configManager.addProtocols(tmpProtocols);
    }


    /**
     * Initialize {@link MetadataService} from {@link WritableMetadataService}'s extension
     */
    private void initMetadataService() {
        this.metadataService = getExtension(getMetadataType());//默认是InMemoryWritableMetadataService
    }

    /**
     * Initialize {@link MetadataServiceExporter}
     */
    private void initMetadataServiceExporter() {//metadataService =InMemoryWritableMetadataService
        this.metadataServiceExporter = new ConfigurableMetadataServiceExporter(metadataService);
    }

    /**
     * Initialize {@link EventListener}
     */
    private void initEventListener() {
        // Add current instance into listeners
        addEventListener(this);
    }

    private List<ServiceDiscovery> getServiceDiscoveries() {
        return AbstractRegistryFactory.getRegistries()
                .stream()
                .filter(registry -> registry instanceof ServiceDiscoveryRegistry)
                .map(registry -> (ServiceDiscoveryRegistry) registry)
                .map(ServiceDiscoveryRegistry::getServiceDiscovery)
                .collect(Collectors.toList());
    }

    /**
     * Start the bootstrap
     * 1、设置启动参数为true（避免并发）
     * 2、初始化各种基本配置：config-center\application\monitor\module\metaService等
     * 3、开始暴露服务
     */
    public DubboBootstrap start() {
        if (started.compareAndSet(false, true)) {
            //初始化基本配置
            initialize();
            if (logger.isInfoEnabled()) {
                logger.info(NAME + " is starting...");
            }
            // 开始暴露服务
            exportServices();

            // Not only provider register
            if (!isOnlyRegisterProvider() || hasExportedServices()) {
                // 2. export MetadataService
                exportMetadataService();
                //3. Register the local ServiceInstance if required
                registerServiceInstance();
            }

            referServices();

            if (logger.isInfoEnabled()) {
                logger.info(NAME + " has started.");
            }
        }
        return this;
    }

    private boolean hasExportedServices() {
        return !metadataService.getExportedURLs().isEmpty();
    }

    /**
     * Block current thread to be await.
     *
     * @return {@link DubboBootstrap}
     */
    public DubboBootstrap await() {
        // if has been waited, no need to wait again, return immediately
        if (!awaited.get()) {
            if (!executorService.isShutdown()) {
                executeMutually(() -> {
                    while (!awaited.get()) {
                        if (logger.isInfoEnabled()) {
                            logger.info(NAME + " awaiting ...");
                        }
                        try {
                            condition.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        }
        return this;
    }

    public DubboBootstrap awaitFinish() throws Exception {
        logger.info(NAME + " waiting services exporting / referring ...");
        if (exportAsync && asyncExportingFutures.size() > 0) {
            CompletableFuture future = CompletableFuture.allOf(asyncExportingFutures.toArray(new CompletableFuture[0]));
            future.get();
        }
        if (referAsync && asyncReferringFutures.size() > 0) {
            CompletableFuture future = CompletableFuture.allOf(asyncReferringFutures.toArray(new CompletableFuture[0]));
            future.get();
        }

        logger.info("Service export / refer finished.");
        return this;
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public boolean isStarted() {
        return started.get();
    }

    public DubboBootstrap stop() throws IllegalStateException {
        destroy();
        return this;
    }
    /* serve for builder apis, begin */

    private ApplicationBuilder createApplicationBuilder(String name) {
        return new ApplicationBuilder().name(name);
    }

    private RegistryBuilder createRegistryBuilder(String id) {
        return new RegistryBuilder().id(id);
    }

    private ProtocolBuilder createProtocolBuilder(String id) {
        return new ProtocolBuilder().id(id);
    }

    private ServiceBuilder createServiceBuilder(String id) {
        return new ServiceBuilder().id(id);
    }

    private ReferenceBuilder createReferenceBuilder(String id) {
        return new ReferenceBuilder().id(id);
    }

    private ProviderBuilder createProviderBuilder(String id) {
        return new ProviderBuilder().id(id);
    }

    private ConsumerBuilder createConsumerBuilder(String id) {
        return new ConsumerBuilder().id(id);
    }
    /* serve for builder apis, end */

    private DynamicConfiguration prepareEnvironment(ConfigCenterConfig configCenter) {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInited()) {
                return null;
            }
            DynamicConfiguration dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            String configContent = dynamicConfiguration.getProperties(configCenter.getConfigFile(), configCenter.getGroup());

            String appGroup = getApplication().getName();
            String appConfigContent = null;
            if (isNotEmpty(appGroup)) {
                appConfigContent = dynamicConfiguration.getProperties
                        (isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                                appGroup
                        );
            }
            try {
                environment.setConfigCenterFirst(configCenter.isHighestPriority());
                environment.updateExternalConfigurationMap(parseProperties(configContent));
                environment.updateAppExternalConfigurationMap(parseProperties(appConfigContent));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
            return dynamicConfiguration;
        }
        return null;
    }

    /**
     * Add an instance of {@link EventListener}
     *
     * @param listener {@link EventListener}
     * @return {@link DubboBootstrap}
     */
    public DubboBootstrap addEventListener(EventListener<?> listener) {
        eventDispatcher.addEventListener(listener);
        return this;
    }

    /**
     * export {@link MetadataService}
     */
    private void exportMetadataService() {
        metadataServiceExporter.export();
    }

    private void unexportMetadataService() {
        metadataServiceExporter.unexport();
    }

    /***
     * 遍历所有的服务进行暴露
     * 4 = {HashMap$Node@2571} "service" -> " size = 8"
     *  key = "service"
     *  value = {HashMap@2581}  size = 8
     *   0 = {HashMap$Node@2607} "org.apache.dubbo.demo.EventNotifyService" -> "<dubbo:service beanName="org.apache.dubbo.demo.EventNotifyService" exported="false" unexported="false" path="org.apache.dubbo.demo.EventNotifyService" ref="org.apache.dubbo.demo.provider.EventNotifyServiceImpl@363042d7" interface="org.apache.dubbo.demo.EventNotifyService" uniqueServiceName="cn/org.apache.dubbo.demo.EventNotifyService:1.0.0" prefix="dubbo.service.org.apache.dubbo.demo.EventNotifyService" dynamic="true" deprecated="false" group="cn" version="1.0.0" id="org.apache.dubbo.demo.EventNotifyService" valid="true" />"
     *   1 = {HashMap$Node@2608} "org.apache.dubbo.demo.AsyncService2" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService2" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService2" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl2@366ac49b" interface="org.apache.dubbo.demo.AsyncService2" uniqueServiceName="org.apache.dubbo.demo.AsyncService2" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService2" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService2" valid="true" />"
     *   2 = {HashMap$Node@2609} "org.apache.dubbo.demo.CallbackService" -> "<dubbo:service beanName="org.apache.dubbo.demo.CallbackService" exported="false" unexported="false" path="org.apache.dubbo.demo.CallbackService" ref="org.apache.dubbo.demo.provider.CallbackServiceImpl@6ad59d92" interface="org.apache.dubbo.demo.CallbackService" uniqueServiceName="org.apache.dubbo.demo.CallbackService" prefix="dubbo.service.org.apache.dubbo.demo.CallbackService" dynamic="true" deprecated="false" connections="1" callbacks="1000" id="org.apache.dubbo.demo.CallbackService" valid="true" />"
     *   3 = {HashMap$Node@2610} "org.apache.dubbo.demo.AsyncService" -> "<dubbo:service beanName="org.apache.dubbo.demo.AsyncService" exported="false" unexported="false" path="org.apache.dubbo.demo.AsyncService" ref="org.apache.dubbo.demo.provider.AsyncServiceImpl@56f0cc85" interface="org.apache.dubbo.demo.AsyncService" uniqueServiceName="org.apache.dubbo.demo.AsyncService" prefix="dubbo.service.org.apache.dubbo.demo.AsyncService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.AsyncService" valid="true" />"
     *   4 = {HashMap$Node@2611} "org.apache.dubbo.demo.StubService" -> "<dubbo:service beanName="org.apache.dubbo.demo.StubService" exported="false" unexported="false" path="org.apache.dubbo.demo.StubService" ref="org.apache.dubbo.demo.provider.StubServiceImpl@62e20a76" interface="org.apache.dubbo.demo.StubService" uniqueServiceName="org.apache.dubbo.demo.StubService" prefix="dubbo.service.org.apache.dubbo.demo.StubService" dynamic="true" deprecated="false" stub="org.apache.dubbo.demo.StubServiceStub" id="org.apache.dubbo.demo.StubService" valid="true" />"
     *   5 = {HashMap$Node@2612} "org.apache.dubbo.demo.MockService" -> "<dubbo:service beanName="org.apache.dubbo.demo.MockService" exported="false" unexported="false" path="org.apache.dubbo.demo.MockService" ref="org.apache.dubbo.demo.provider.MockServiceImpl@2cc44ad" interface="org.apache.dubbo.demo.MockService" uniqueServiceName="org.apache.dubbo.demo.MockService" prefix="dubbo.service.org.apache.dubbo.demo.MockService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.MockService" valid="true" />"
     *   6 = {HashMap$Node@2613} "org.apache.dubbo.demo.DemoService" -> "<dubbo:service beanName="org.apache.dubbo.demo.DemoService" exported="false" unexported="false" path="org.apache.dubbo.demo.DemoService" ref="org.apache.dubbo.demo.provider.DemoServiceImpl@44b3606b" interface="org.apache.dubbo.demo.DemoService" uniqueServiceName="org.apache.dubbo.demo.DemoService" prefix="dubbo.service.org.apache.dubbo.demo.DemoService" dynamic="true" deprecated="false" id="org.apache.dubbo.demo.DemoService" valid="true" />"
     *   7 = {HashMap$Node@2614} "org.apache.dubbo.demo.InjvmService" -> "<dubbo:service beanName="org.apache.dubbo.demo.InjvmService" exported="false" unexported="false" path="org.apache.dubbo.demo.InjvmService" ref="org.apache.dubbo.demo.provider.InjvmServiceImpl@1477089c" interface="org.apache.dubbo.demo.InjvmService" uniqueServiceName="org.apache.dubbo.demo.InjvmService" prefix="dubbo.service.org.apache.dubbo.demo.InjvmService" dynamic="true" deprecated="false" protocolIds="injvm" id="org.apache.dubbo.demo.InjvmService" valid="true" />"
     */
    private void exportServices() {
        configManager.getServices().forEach(sc -> {
            // TODO, compatible with ServiceConfig.export()
            ServiceConfig serviceConfig = (ServiceConfig) sc;
            serviceConfig.setBootstrap(this);

            if (exportAsync) {
                ExecutorService executor = executorRepository.getServiceExporterExecutor();
                Future<?> future = executor.submit(() -> {
                    sc.export();
                });
                asyncExportingFutures.add(future);
            } else {
                sc.export();
                exportedServices.add(sc);
            }
        });
    }

    private void unexportServices() {
        exportedServices.forEach(sc -> {
            configManager.removeConfig(sc);
            sc.unexport();
        });

        asyncExportingFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        asyncExportingFutures.clear();
        exportedServices.clear();
    }

    private void referServices() {
        if (cache == null) {
            cache = ReferenceConfigCache.getCache();
        }

        configManager.getReferences().forEach(rc -> {
            // TODO, compatible with  ReferenceConfig.refer()
            ReferenceConfig referenceConfig = (ReferenceConfig) rc;
            referenceConfig.setBootstrap(this);

            if (rc.shouldInit()) {
                if (referAsync) {
                    CompletableFuture<Object> future = ScheduledCompletableFuture.submit(
                            executorRepository.getServiceExporterExecutor(),
                            () -> cache.get(rc)
                    );
                    asyncReferringFutures.add(future);
                } else {
                    cache.get(rc);
                }
            }
        });
    }

    private void unreferServices() {
        if (cache == null) {
            cache = ReferenceConfigCache.getCache();
        }

        asyncReferringFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        asyncReferringFutures.clear();
        cache.destroyAll();
    }

    private void registerServiceInstance() {
        if (CollectionUtils.isEmpty(getServiceDiscoveries())) {
            return;
        }

        ApplicationConfig application = getApplication();

        String serviceName = application.getName();

        URL exportedURL = selectMetadataServiceExportedURL();

        String host = exportedURL.getHost();

        int port = exportedURL.getPort();

        ServiceInstance serviceInstance = createServiceInstance(serviceName, host, port);

        getServiceDiscoveries().forEach(serviceDiscovery -> serviceDiscovery.register(serviceInstance));
    }

    private URL selectMetadataServiceExportedURL() {

        URL selectedURL = null;

        SortedSet<String> urlValues = metadataService.getExportedURLs();

        for (String urlValue : urlValues) {
            URL url = URL.valueOf(urlValue);
            if (MetadataService.class.getName().equals(url.getServiceInterface())) {
                continue;
            }
            if ("rest".equals(url.getProtocol())) { // REST first
                selectedURL = url;
                break;
            } else {
                selectedURL = url; // If not found, take any one
            }
        }

        if (selectedURL == null && CollectionUtils.isNotEmpty(urlValues)) {
            selectedURL = URL.valueOf(urlValues.iterator().next());
        }

        return selectedURL;
    }

    private void unregisterServiceInstance() {
        if (serviceInstance != null) {
            getServiceDiscoveries().forEach(serviceDiscovery -> {
                serviceDiscovery.unregister(serviceInstance);
            });
        }
    }

    private ServiceInstance createServiceInstance(String serviceName, String host, int port) {
        this.serviceInstance = new DefaultServiceInstance(serviceName, host, port);
        setMetadataStorageType(serviceInstance, getMetadataType());
        return this.serviceInstance;
    }

    public void destroy() {
        if (started.compareAndSet(true, false)) {
            unregisterServiceInstance();
            unexportMetadataService();
            unexportServices();
            unreferServices();

            destroyRegistries();
            destroyProtocols();
            destroyServiceDiscoveries();

            clear();
            shutdown();
            release();
        }
    }

    private void destroyProtocols() {
        configManager.getProtocols().forEach(protocolConfig -> {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(protocolConfig.getName()).destroy();
        });
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s all ProtocolConfigs have been destroyed.");
        }
    }

    private void destroyRegistries() {
        AbstractRegistryFactory.destroyAll();
    }

    private void destroyServiceDiscoveries() {
        getServiceDiscoveries().forEach(serviceDiscovery -> {
            execute(() -> {
                serviceDiscovery.destroy();
            });
        });
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s all ServiceDiscoveries have been destroyed.");
        }
    }

    private void clear() {
        clearConfigs();
        clearApplicationModel();
    }

    private void clearApplicationModel() {

    }

    private void clearConfigs() {
        configManager.clear();
        if (logger.isDebugEnabled()) {
            logger.debug(NAME + "'s configs have been clear.");
        }
    }

    private void release() {
        executeMutually(() -> {
            while (awaited.compareAndSet(false, true)) {
                if (logger.isInfoEnabled()) {
                    logger.info(NAME + " is about to shutdown...");
                }
                condition.signalAll();
            }
        });
    }

    private void shutdown() {
        if (!executorService.isShutdown()) {
            // Shutdown executorService
            executorService.shutdown();
        }
    }

    private void executeMutually(Runnable runnable) {
        try {
            lock.lock();
            runnable.run();
        } finally {
            lock.unlock();
        }
    }
    //检查 配置文件里是否配置了Application,如果没有的话，初始化一个
    private ApplicationConfig getApplication() {
        ApplicationConfig application = configManager
                .getApplication()
                .orElseGet(() -> {
                    ApplicationConfig applicationConfig = new ApplicationConfig();
                    applicationConfig.refresh();
                    return applicationConfig;
                });
        configManager.setApplication(application);
        return application;
    }

    private MonitorConfig getMonitor() {
        MonitorConfig monitor = configManager
                .getMonitor()
                .orElseGet(() -> {
                    MonitorConfig monitorConfig = new MonitorConfig();
                    monitorConfig.refresh();
                    return monitorConfig;
                });
        configManager.setMonitor(monitor);
        return monitor;
    }

    private MetricsConfig getMetrics() {
        MetricsConfig metrics = configManager
                .getMetrics()
                .orElseGet(() -> {
                    MetricsConfig metricsConfig = new MetricsConfig();
                    metricsConfig.refresh();
                    return metricsConfig;
                });
        configManager.setMetrics(metrics);
        return metrics;
    }

    private ModuleConfig getModule() {
        ModuleConfig module = configManager
                .getModule()
                .orElseGet(() -> {
                    ModuleConfig moduleConfig = new ModuleConfig();
                    moduleConfig.refresh();
                    return moduleConfig;
                });
        configManager.setModule(module);
        return module;
    }

    private SslConfig getSsl() {
        SslConfig ssl = configManager
                .getSsl()
                .orElseGet(() -> {
                    SslConfig sslConfig = new SslConfig();
                    sslConfig.refresh();
                    return sslConfig;
                });
        configManager.setSsl(ssl);
        return ssl;
    }
}

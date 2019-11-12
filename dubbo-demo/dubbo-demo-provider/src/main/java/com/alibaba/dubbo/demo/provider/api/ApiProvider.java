package com.alibaba.dubbo.demo.provider.api;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.demo.DemoService;
import com.alibaba.dubbo.demo.provider.DemoServiceImpl;
import com.alibaba.dubbo.demo.provider.Provider;

public class ApiProvider {
    public static void  main(String args[]){
        DemoService service = new DemoServiceImpl();
        ApplicationConfig application = new ApplicationConfig();
        application.setName("xuzf_appliction_api");

        RegistryConfig registry = new RegistryConfig();
        registry.setAddress("127.0.0.1:2081");
        //registry.setProtocol("dubbo");
        registry.setDefault(true);

        ProtocolConfig protocol = new ProtocolConfig("dubbo");
        protocol.setPort(12345);
        protocol.setThreads(200);



        ServiceConfig<DemoService> serviceConfig =  new ServiceConfig<DemoService>();

        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setRef(service);
        serviceConfig.setVersion("1.0.0");
        serviceConfig.setApplication(application);
        serviceConfig.setProtocol(protocol);
        serviceConfig.setRegistry(registry);
        serviceConfig.export();
//

    }

}


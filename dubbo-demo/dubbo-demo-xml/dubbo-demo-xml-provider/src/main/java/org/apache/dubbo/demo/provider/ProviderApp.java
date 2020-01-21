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
package org.apache.dubbo.demo.provider;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.demo.asyn.GreetingService;
import org.apache.dubbo.demo.provider.api.asyn.GreetingServiceImpl;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;

public class ProviderApp {
    public static void main(String[] args) throws Exception {
        testApi();
    }

    public static void testXml() throws IOException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-provider.xml");
        context.start();
        System.in.read();

    }
    public static void testApi() throws IOException {
        ServiceConfig<GreetingService> serviceConfig = new ServiceConfig <>();

        serviceConfig.setApplication(new ApplicationConfig("api-provider"));

        RegistryConfig registry = new RegistryConfig("zookeeper://127.0.0.1:2181");
        serviceConfig.setRegistry(registry);

        serviceConfig.setInterface(GreetingService.class);
        serviceConfig.setRef(new GreetingServiceImpl());

        serviceConfig.setVersion("1.0.0");
        serviceConfig.setGroup("dubbo");

        serviceConfig.export();

        System.out.println("server is started");

        System.in.read();

    }
}

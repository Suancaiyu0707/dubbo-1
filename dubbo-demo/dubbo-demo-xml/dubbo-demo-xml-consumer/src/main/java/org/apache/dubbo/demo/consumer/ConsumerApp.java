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
package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.demo.*;

import org.apache.dubbo.demo.asyn.GreetingService;
import org.apache.dubbo.demo.asyn.GreetingServiceAsyn;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ConsumerApp {
    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     */
    public static void main(String[] args) throws Exception {
        testApiAsynForCompleteFuture2();
    }
    public static void testXml() throws IOException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
//        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-demo-injvm.xml");
        context.start();


//        MockService mockService = context.getBean( MockService.class);
//        mockService.sayHello("xuzf");
//        DemoService demoService = context.getBean("demoService", DemoService.class);
//        CompletableFuture<String> hello = demoService.sayHelloAsync("world");
//        System.out.println("result: " + hello.get());
//        InjvmService injvmService = context.getBean("injvmService", InjvmService.class);
//        System.out.println(injvmService.sayHello_Injvm());
//        //参数回调
//        CallbackService callbackService = (CallbackService) context.getBean("callbackService");
//
//        callbackService.addListener("xuzf.callbcak", new CallbackListener(){
//            public void changed(String msg) {
//                System.out.println("xuzf.callbcak:" + msg);
//            }
//        });
//
//        //事件通知
//        EventNotifyService eventNotifyService = (EventNotifyService) context.getBean("eventNotifyService");
//        NotifyImpl notify = (NotifyImpl) context.getBean("eventNotifyCallback");
//        int requestId = 2;
//        String ret = eventNotifyService.get(requestId);
//
//        //for Test：只是用来说明callback正常被调用，业务具体实现自行决定.
//        for (int i = 0; i < 10; i++) {
//            if (!notify.ret.containsKey(requestId)) {
//                Thread.sleep(200);
//            } else {
//                break;
//            }
//        }
        //本地存根

//        StubService stubService = (StubService) context.getBean("stubService");
//
//        String result = stubService.sayHello("xuzf");

//        DemoService demoService = (DemoService) context.getBean("demoService");
//
//
//        try {
//            System.out.println(demoService.sayHello("xuzf"));
//        } catch (Throwable throwable) {
//                throwable.printStackTrace();
//        }

        StubService stubService = (StubService) context.getBean("stubService");
        try {
            stubService.sayHello("zjn");
        } catch (Throwable e) {
            e.printStackTrace();
        }



        EventNotifyService notifyService = (EventNotifyService) context.getBean("eventNotifyService");
        notifyService.get(10);
    }
    public static void testApi() throws IOException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig <>();

        referenceConfig.setApplication(new ApplicationConfig("api-consumer"));

        RegistryConfig registry = new RegistryConfig("zookeeper://127.0.0.1:2181");
        referenceConfig.setRegistry(registry);

        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(5000);


        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");

        GreetingService greet = referenceConfig.get();

        RpcContext.getContext().setAttachment("company","zhangmen");

        System.out.println(greet.sayHello("zjn"));

    }


    public static void testApiAsyn() throws IOException, ExecutionException, InterruptedException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig <>();

        referenceConfig.setApplication(new ApplicationConfig("api-consumer"));

        RegistryConfig registry = new RegistryConfig("zookeeper://127.0.0.1:2181");
        referenceConfig.setRegistry(registry);

        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(5000);


        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        //设置为异步
        referenceConfig.setAsync(true);

        GreetingService greet = referenceConfig.get();

        RpcContext.getContext().setAttachment("company","zhangmen");
        //因为一开始是异步调用，所以返回null
        System.out.println(greet.sayHello("zjn"));
        //调用future.get，这样会有阻塞的风险
        Future<String> future =RpcContext.getContext().getFuture();
        System.out.println(future.get());

    }

    public static void testApiAsynForCompleteFuture() throws IOException, ExecutionException, InterruptedException {
        ReferenceConfig<GreetingService> referenceConfig = new ReferenceConfig <>();

        referenceConfig.setApplication(new ApplicationConfig("api-consumer"));

        RegistryConfig registry = new RegistryConfig("zookeeper://127.0.0.1:2181");
        referenceConfig.setRegistry(registry);

        referenceConfig.setInterface(GreetingService.class);
        referenceConfig.setTimeout(5000);


        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        //设置为异步
        referenceConfig.setAsync(true);

        GreetingService greet = referenceConfig.get();

        RpcContext.getContext().setAttachment("company","zhangmen");
        //因为一开始是异步调用，所以返回null
        System.out.println(greet.sayHello("zjn"));
        //调用future.get，这样会有阻塞的风险
        CompletableFuture<String> future =RpcContext.getContext().getCompletableFuture();
        //通过回调函数来响应执行完成
        future.whenComplete((v,t) -> {
           if(t!=null){
               t.printStackTrace();
           } else{
               System.out.println(v);
           }
        });
        System.out.println("over");

        Thread.currentThread().join();

    }

    public static void testApiAsynForCompleteFuture2() throws IOException, ExecutionException, InterruptedException {
        ReferenceConfig<GreetingServiceAsyn> referenceConfig = new ReferenceConfig <>();

        referenceConfig.setApplication(new ApplicationConfig("api-consumer"));

        RegistryConfig registry = new RegistryConfig("zookeeper://127.0.0.1:2181");
        referenceConfig.setRegistry(registry);

        referenceConfig.setInterface(GreetingServiceAsyn.class);
        referenceConfig.setTimeout(5000);


        referenceConfig.setVersion("1.0.0");
        referenceConfig.setGroup("dubbo");
        //设置为异步
        referenceConfig.setAsync(true);

        GreetingServiceAsyn greet = referenceConfig.get();

        RpcContext.getContext().setAttachment("company","zhangmen");
        //因为一开始是异步调用，所以返回null
        System.out.println(greet.sayHello("zjn"));
        //调用future.get，这样会有阻塞的风险
        CompletableFuture<String> future =RpcContext.getContext().getCompletableFuture();
        //通过回调函数来响应执行完成
        future.whenComplete((v,t) -> {
            if(t!=null){
                t.printStackTrace();
            } else{
                System.out.println(v);
            }
        });
        System.out.println("over");

        Thread.currentThread().join();

    }
}

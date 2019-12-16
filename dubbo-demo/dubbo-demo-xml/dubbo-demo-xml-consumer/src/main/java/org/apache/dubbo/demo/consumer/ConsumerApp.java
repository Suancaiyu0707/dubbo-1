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

import org.apache.dubbo.demo.*;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.concurrent.CompletableFuture;

public class ConsumerApp {
    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     */
    public static void main(String[] args) throws Exception {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
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

        StubService stubService = (StubService) context.getBean("stubService");

        stubService.sayHello("xuzf");

    }
}

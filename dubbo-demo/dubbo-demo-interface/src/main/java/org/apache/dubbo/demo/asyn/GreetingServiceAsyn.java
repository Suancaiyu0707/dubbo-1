package org.apache.dubbo.demo.asyn;

import java.util.concurrent.CompletableFuture;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2020/1/21
 * Time: 8:30 AM
 * Description: No Description
 */
public interface GreetingServiceAsyn {
    CompletableFuture<String> sayHello(String name);
}

package org.apache.dubbo.demo.asyn;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2020/1/21
 * Time: 8:27 AM
 * Description: No Description
 */
public interface GreetingService {
    String sayHello(String name);

    Result <String> testGeneric( User user);
}
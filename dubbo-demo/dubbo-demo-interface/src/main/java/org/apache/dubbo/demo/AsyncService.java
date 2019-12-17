package org.apache.dubbo.demo;

import java.util.concurrent.CompletableFuture;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 8:46 AM
 * Description: No Description
 * 异步调用
 */
public interface AsyncService {
    CompletableFuture<String> sayHello( String name);
}

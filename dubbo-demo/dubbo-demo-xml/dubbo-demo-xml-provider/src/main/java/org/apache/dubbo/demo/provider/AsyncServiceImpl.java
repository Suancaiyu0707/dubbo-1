package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.AsyncService;
import org.apache.dubbo.rpc.RpcContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 8:47 AM
 * Description: No Description
 */
public class AsyncServiceImpl implements AsyncService {

    private static ExecutorService executorService = Executors.newFixedThreadPool(10);
    @Override
    public CompletableFuture<String> sayHello( String name ) {
        RpcContext rpcContext = RpcContext.getContext();
        return CompletableFuture.supplyAsync(new Supplier <String>() {
            @Override
            public String get() {
                System.out.println(rpcContext.getAttachment("consumer-key1"));
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return "async response from provider.";
            }
        },executorService);
    }
}

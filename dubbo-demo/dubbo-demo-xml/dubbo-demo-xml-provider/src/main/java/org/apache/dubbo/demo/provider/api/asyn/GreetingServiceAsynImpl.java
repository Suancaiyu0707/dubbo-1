package org.apache.dubbo.demo.provider.api.asyn;

import com.alibaba.fastjson.JSON;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.demo.asyn.GreetingService;
import org.apache.dubbo.demo.asyn.GreetingServiceAsyn;
import org.apache.dubbo.demo.asyn.Result;
import org.apache.dubbo.demo.asyn.User;
import org.apache.dubbo.rpc.RpcContext;

import java.util.concurrent.*;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2020/1/21
 * Time: 8:32 AM
 * Description: No Description
 */
public class GreetingServiceAsynImpl implements GreetingServiceAsyn {

    private ThreadPoolExecutor bizThreadPool = new ThreadPoolExecutor(0,16,1,TimeUnit.MINUTES,
            new SynchronousQueue <>(),
            new NamedThreadFactory("biz-thread-pool"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    /***
     * 调用sayHello方法的是Dubbo 线程池
     * 处理业务逻辑的是业务线程池
     * @param name
     * @return
     */
    @Override
    public CompletableFuture<String> sayHello( String name ) {
        RpcContext rpcContext = RpcContext.getContext();
        //强制supplyAsync使用自定义的业务线程池，避免使用JDK公用的线程池
        ForkJoinPool.commonPool();
        return CompletableFuture.supplyAsync(()->{
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "Hello "+name +" "+rpcContext.getAttachment("company");

        },bizThreadPool);
    }
}

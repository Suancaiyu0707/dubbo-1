package org.apache.dubbo.demo.provider.api.asyn;

import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.demo.asyn.GreetingServiceAsyn;
import org.apache.dubbo.demo.asyn.GreetingServiceRpcContext;
import org.apache.dubbo.rpc.AsyncContext;
import org.apache.dubbo.rpc.RpcContext;

import java.util.concurrent.*;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2020/1/21
 * Time: 8:32 AM
 * Description: No Description
 */
public class GreetingServiceAsynContextImpl implements GreetingServiceRpcContext {

    private ThreadPoolExecutor bizThreadPool = new ThreadPoolExecutor(0,16,1,TimeUnit.MINUTES,
            new SynchronousQueue <>(),
            new NamedThreadFactory("biz-thread-pool"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    /***
     * @param name
     * @return
     */
    @Override
    public String sayHello( String name ) {
        //调用startAsync开启服务异步执行,然后把服务处理任务提交给线程池后就直接返回null
        final AsyncContext asyncContext =RpcContext.startAsync();
        //异步任务首先执行signalContextSwitch切换任务的上下文，然后执行任务
        bizThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                //切换任务的上下文
                asyncContext.signalContextSwitch();

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                asyncContext.write("Hello "+name +" "+RpcContext.getContext().getAttachment("company"));
            }
        });
        return null;
    }
}

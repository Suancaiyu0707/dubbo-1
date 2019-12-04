package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.AsyncService2;
import org.apache.dubbo.rpc.AsyncContext;
import org.apache.dubbo.rpc.RpcContext;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 8:52 AM
 * Description: No Description
 */
public class AsyncServiceImpl2 implements AsyncService2 {

    public String sayHello(String name) {
        final AsyncContext asyncContext = RpcContext.startAsync();
        new Thread(() -> {
            // 如果要使用上下文，则必须要放在第一句执行
            asyncContext.signalContextSwitch();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // 写回响应
            asyncContext.write("Hello " + name + ", response from provider.");
        }).start();
        return null;
    }
}
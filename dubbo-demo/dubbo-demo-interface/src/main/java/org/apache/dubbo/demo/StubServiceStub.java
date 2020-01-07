package org.apache.dubbo.demo;

import org.apache.dubbo.rpc.RpcContext;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 9:21 AM
 * Description: No Description
 */
public class StubServiceStub implements StubService{
    private final StubService stubService;

    // 构造函数传入真正的远程代理对象
    public StubServiceStub(StubService stubService){
        this.stubService = stubService;
    }

    @Override
    public String sayHello( String name) {
        RpcContext context = RpcContext.getContext();
//        System.out.println(context.getAttachment("side"));
//        System.out.println("context="+context);
        // 此代码在客户端执行, 你可以在客户端做ThreadLocal本地缓存，或预先验证参数是否合法，等等
        try {
            if("xuzf".equals(name)){
                throw new RuntimeException();
            }
            System.out.println("开始调用真正类型");
            return stubService.sayHello(name);

        } catch (Exception e) {
            // 你可以容错，可以做任何AOP拦截事项
            System.out.println("容错数据");
           throw e;
        }
    }
}

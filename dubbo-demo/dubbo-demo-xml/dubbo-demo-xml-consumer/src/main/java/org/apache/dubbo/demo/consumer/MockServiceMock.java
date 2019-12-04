package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.demo.MockService;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 9:27 AM
 * Description: No Description
 * 本地伪装通常用于服务降级，比如某验权服务，当服务提供方全部挂掉后，客户端不抛出异常，而是通过 Mock 数据返回授权失败。
 */
public class MockServiceMock implements MockService {
    public String sayHello(String name) {
        // 你可以伪造容错数据，此方法只在出现RpcException时被执行
        return "i am MockServiceMock from consumer3";
    }
}
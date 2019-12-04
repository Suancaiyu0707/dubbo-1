package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.StubService;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 9:24 AM
 * Description: No Description
 */
public class StubServiceImpl implements StubService {
    @Override
    public String sayHello( String name ) {
        return "i am StubService";
    }
}

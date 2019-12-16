package org.apache.dubbo.demo.provider;

import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.demo.MockService;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 9:28 AM
 * Description: No Description
 */
public class MockServiceImpl implements MockService {
    @Override
    public String sayHello( String name ) {
        return "i am MockService";
    }
}

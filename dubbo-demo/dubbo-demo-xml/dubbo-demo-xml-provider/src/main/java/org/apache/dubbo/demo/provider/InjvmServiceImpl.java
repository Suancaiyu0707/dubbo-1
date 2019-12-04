package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.InjvmService;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 8:59 AM
 * Description: No Description
 */
public class InjvmServiceImpl implements InjvmService {
    @Override
    public String sayHello_Injvm() {
        return "i am InjvmServiceImpl";
    }
}

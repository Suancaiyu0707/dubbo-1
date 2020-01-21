package org.apache.dubbo.demo.provider.api.asyn;

import com.alibaba.fastjson.JSON;
import org.apache.dubbo.demo.asyn.GreetingService;
import org.apache.dubbo.demo.asyn.Result;
import org.apache.dubbo.demo.asyn.User;
import org.apache.dubbo.rpc.RpcContext;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2020/1/21
 * Time: 8:32 AM
 * Description: No Description
 */
public class GreetingServiceImpl implements GreetingService {
    @Override
    public String sayHello( String name ) {

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return "Hello "+name +" "+RpcContext.getContext().getAttachment("company");
    }

    @Override
    public Result <String> testGeneric( User user ) {
        Result<String> result = new Result <>();

        result.setCode(1);
        result.setData(JSON.toJSONString(user));
        return result;
    }
}

//该类通过 dubbo-common 模块的 bytecode 模块的 Proxy 类，自动生成，使用 Javassist 技术。
//生成的 proxy 类会实现我们定义的 Service 接口( 例如，此处是 DemoService )。
//#bye(Object) 和 #sayHello(Object) 方法，是我们定义在 DemoService 的接口方法，在生成的 proxy 类中，实现这些定义在接口中的方法，收拢统一调用
//java.lang.reflect.InvocationHandler#invoke(proxy, method, args) 方法。通过这样的方式，可以调用到最终的 Invoker#invoke(Invocation) 方法，实现 RPC 调用
package org.apache.dubbo.demo.consumer;

import com.alibaba.dubbo.rpc.service.EchoService;

import org.apache.dubbo.common.bytecode.ClassGenerator;
import org.apache.dubbo.demo.DemoService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;


public class DemoServiceProxy0 implements ClassGenerator.DC, EchoService, DemoService {
    public static Method[] methods;
    private InvocationHandler handler;

    public DemoServiceProxy0() {
    }

    public DemoServiceProxy0(InvocationHandler paramInvocationHandler) {
        this.handler = paramInvocationHandler;
    }

    public String sayHello(String paramString) throws Throwable {
        Object[] arrayOfObject = new Object[1];
        arrayOfObject[0] = paramString;

        Object localObject = this.handler.invoke(this, methods[1], arrayOfObject);

        return (String) localObject;
    }

    public Object $echo(Object paramObject){
        Object[] arrayOfObject = new Object[1];
        arrayOfObject[0] = paramObject;

        Object localObject = null;
        try {
            localObject = this.handler.invoke(this, methods[2], arrayOfObject);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return (Object) localObject;
    }
}

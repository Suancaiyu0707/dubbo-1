//package org.apache.dubbo.demo.consumer; //package org.apache.dubbo.common.bytecode;
//
//
///**
// * Created with IntelliJ IDEA.
// * User: zhifang.xu
// * Date: 2019/12/31
// * Time: 7:40 AM
// * Description: No Description
// */
//import org.apache.dubbo.common.bytecode.ClassGenerator;
//import org.apache.dubbo.common.bytecode.NoSuchPropertyException;
//import org.apache.dubbo.common.bytecode.Wrapper;
//
//import java.lang.reflect.InvocationTargetException;
//
//import java.util.Map;
//
//
//public class Wrapper1 extends Wrapper implements ClassGenerator.DC {
//    public static String[] pns;
//    public static Map pts;
//    public static String[] mns;
//    public static String[] dmns;
//    public static Class[] mts0;
//    public static Class[] mts1;
//    public static Class[] mts2;
//
//    public String[] getPropertyNames() {
//        return pns;
//    }
//
//    public boolean hasProperty(String paramString) {
//        return pts.containsKey(paramString);
//    }
//
//    public Class getPropertyType(String paramString) {
//        return (Class) pts.get(paramString);
//    }
//
//    public String[] getMethodNames() {
//        return mns;
//    }
//
//    public String[] getDeclaredMethodNames() {
//        return dmns;
//    }
//
//    public void setPropertyValue(Object paramObject1, String paramString,
//                                 Object paramObject2) {
//        DemoServiceImpl w;
//
//        try {
//            w = (DemoServiceImpl) paramObject1;
//        } catch (Throwable localThrowable) {
//            throw new IllegalArgumentException(localThrowable);
//        }
//
//
//        throw new NoSuchPropertyException("Not found property \"" +
//                paramString +
//                "\" filed or setter method in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.");
//    }
//
//    public Object getPropertyValue(Object paramObject, String paramString) {
//        DemoServiceImpl w;
//
//        try {
//            w = (DemoServiceImpl) paramObject;
//        } catch (Throwable localThrowable) {
//            throw new IllegalArgumentException(localThrowable);
//        }
//
//        throw new NoSuchPropertyException("Not found property \"" +
//                paramString +
//                "\" filed or setter method in class com.alibaba.dubbo.demo.provider.DemoServiceImpl.");
//    }
//
//    public Object invokeMethod(Object paramObject, String paramString,
//                               Class[] paramArrayOfClass, Object[] paramArrayOfObject)
//            throws InvocationTargetException {
//        DemoServiceImpl w;
//
//        try {
//            w = (DemoServiceImpl) paramObject;
//        } catch (Throwable localThrowable1) {
//            throw new IllegalArgumentException(localThrowable1);
//        }
//
//        try {
//            if ("sayHello".equals(paramString) &&
//                    (paramArrayOfClass.length == 1)) {
//                return w.sayHello((String) paramArrayOfObject[0]);
//            }
//
//        } catch (Throwable localThrowable2) {
//            throw new InvocationTargetException(localThrowable2);
//        }
//        return null;
//    }
//}

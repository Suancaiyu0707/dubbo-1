package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.demo.Notify;

import java.util.HashMap;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 9:15 AM
 * Description: No Description
 */
public class NotifyImpl  implements Notify {
    public Map<Integer, String>    ret    = new HashMap<Integer, String>();
    public Map<Integer, Throwable> errors = new HashMap<Integer, Throwable>();

    public void onreturn(String msg, Integer id) {
        System.out.println("onreturn:" + msg);
        ret.put(id, msg);
    }

    public void onthrow(Throwable ex, Integer id) {
        errors.put(id, ex);
    }
}
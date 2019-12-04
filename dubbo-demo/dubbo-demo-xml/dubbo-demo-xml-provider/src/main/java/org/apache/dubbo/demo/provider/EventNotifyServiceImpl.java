package org.apache.dubbo.demo.provider;

import org.apache.dubbo.demo.EventNotifyService;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2019/12/3
 * Time: 9:12 AM
 * Description: No Description
 */
public class EventNotifyServiceImpl implements EventNotifyService {
    @Override
    public String get( int id ) {
        return "success";
    }
}

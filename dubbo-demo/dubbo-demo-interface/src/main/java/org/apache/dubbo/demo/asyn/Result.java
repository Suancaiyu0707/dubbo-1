package org.apache.dubbo.demo.asyn;

/**
 * Created with IntelliJ IDEA.
 * User: zhifang.xu
 * Date: 2020/1/21
 * Time: 8:29 AM
 * Description: No Description
 */
public class Result<T> {
    public T data;

    private String msg;

    private int code;

    public T getData() {
        return data;
    }

    public void setData( T data ) {
        this.data = data;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg( String msg ) {
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public void setCode( int code ) {
        this.code = code;
    }
}

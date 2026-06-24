package com.aifanyi.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回体。code=0 表示成功，非 0 表示业务/系统错误。
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String msg;
    private T data;

    public R() {
    }

    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static <T> R<T> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(500, msg, null);
    }
}

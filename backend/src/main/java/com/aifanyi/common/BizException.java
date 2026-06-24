package com.aifanyi.common;

import lombok.Getter;

/**
 * 业务异常，携带错误码。由 GlobalExceptionHandler 统一捕获。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 500;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}

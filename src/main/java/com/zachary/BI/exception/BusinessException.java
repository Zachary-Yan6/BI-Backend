package com.zachary.BI.exception;

import com.zachary.BI.common.ErrorCode;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
public class BusinessException extends RuntimeException {

    /**
     * Documentation.
     */
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}

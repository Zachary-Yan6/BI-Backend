package com.zachary.BI.common;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "Invalid request parameters"),
    NOT_LOGIN_ERROR(40100, "Not signed in"),
    NO_AUTH_ERROR(40101, "Permission denied"),
    NOT_FOUND_ERROR(40400, "Requested data was not found"),
    FORBIDDEN_ERROR(40300, "Access forbidden"),
    SYSTEM_ERROR(50000, "Internal system error"),
    OPERATION_ERROR(50001, "Operation failed");

    /**
     * Documentation.
     */
    private final int code;

    /**
     * Documentation.
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

}

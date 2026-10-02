package com.zachary.BI.exception;

import com.zachary.BI.common.BaseResponse;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Turns every exception into the same {@link BaseResponse} shape.
 * <ul>
 *     <li>Errors raised by our own code keep the project convention: HTTP 200 with an error code.</li>
 *     <li>Errors Spring raises before our code runs (bad JSON, missing parameter, wrong method, unknown path)
 *     keep their real HTTP status, so proxies and monitoring still see 400/404/405.</li>
 *     <li>Anything else is logged in full but answered with a generic message, never internal details.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        // Expected outcomes such as "not found" or "permission denied": no stack trace needed.
        log.warn("Business error {}: {}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<?>> unreadableBodyHandler(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, ErrorCode.PARAMS_ERROR, "The request body is missing or is not valid JSON.");
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<BaseResponse<?>> typeMismatchHandler(TypeMismatchException e) {
        log.warn("Request parameter type mismatch: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, ErrorCode.PARAMS_ERROR, "A request parameter has an invalid value.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BaseResponse<?>> uploadTooLargeHandler(MaxUploadSizeExceededException e) {
        log.warn("Upload rejected: {}", e.getMessage());
        return error(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PARAMS_ERROR, "The uploaded file is too large.");
    }

    /**
     * Catches checked exceptions too (IOException from file handling, ServletException from Spring MVC),
     * which the previous RuntimeException-only handler let through to Spring's default error page.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<?>> exceptionHandler(Exception e) {
        // Spring MVC's own request errors describe their HTTP status, e.g. missing parameter or unknown path.
        if (e instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = errorResponse.getStatusCode();
            ErrorCode errorCode = toErrorCode(status);
            log.warn("Request rejected with {}: {}", status.value(), e.getMessage());
            String detail = StringUtils.defaultIfBlank(errorResponse.getBody().getDetail(), errorCode.getMessage());
            return error(status, errorCode, detail);
        }
        log.error("Unexpected exception", e);
        return error(HttpStatus.OK, ErrorCode.SYSTEM_ERROR, "System error");
    }

    private static ErrorCode toErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.NOT_LOGIN_ERROR;
            case 403 -> ErrorCode.FORBIDDEN_ERROR;
            case 404 -> ErrorCode.NOT_FOUND_ERROR;
            case 429 -> ErrorCode.TOO_MANY_REQUESTS;
            default -> ErrorCode.PARAMS_ERROR;
        };
    }

    private static ResponseEntity<BaseResponse<?>> error(HttpStatusCode status, ErrorCode errorCode, String message) {
        BaseResponse<?> body = ResultUtils.error(errorCode, message);
        return ResponseEntity.status(status).body(body);
    }
}

package com.zachary.BI.exception;

/**
 * An AI call failure that retrying cannot fix, such as missing configuration. The analysis job fails immediately.
 */
public class NonRetryableAiException extends RuntimeException {

    public NonRetryableAiException(String message) {
        super(message);
    }
}

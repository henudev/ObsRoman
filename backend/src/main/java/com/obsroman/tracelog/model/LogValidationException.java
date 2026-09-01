package com.obsroman.tracelog.model;

import com.obsroman.tracelog.common.ErrorCode;

/**
 * 单条日志校验失败。与 {@link ApiException} 同源，批量场景由 IngestionService 捕获并降级为 entry error。
 */
public class LogValidationException extends RuntimeException {

    private final ErrorCode errorCode;

    public LogValidationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

package com.obsroman.tracelog.storage;

import com.obsroman.tracelog.common.ErrorCode;

/**
 * 存储层异常。区分可重试（网络/5xx）与不可重试（4xx），由写入端 Retry 策略判断。
 */
public class StorageException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retryable;

    public StorageException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, errorCode != ErrorCode.STORAGE_ERROR);
    }

    public StorageException(ErrorCode errorCode, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** true = 网络/超时/429/5xx，写入路径允许重试 */
    public boolean isRetryable() {
        return retryable;
    }
}

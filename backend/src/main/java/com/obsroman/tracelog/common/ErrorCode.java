package com.obsroman.tracelog.common;

/**
 * 全局错误码。code=0 表示成功；非 0 为业务/系统错误，并与 HTTP 状态码对应。
 */
public enum ErrorCode {
    OK(0, "ok", 200),

    INVALID_REQUEST(1001, "invalid request", 400),
    INVALID_LOG(1002, "invalid log record", 400),
    LOG_TOO_LARGE(1003, "log record exceeds size limit", 400),
    BATCH_TOO_LARGE(1004, "batch exceeds records limit", 400),
    PAYLOAD_TOO_LARGE(1005, "request body exceeds size limit", 413),
    QUEUE_FULL(1006, "ingest queue full, log dropped", 202),

    UNAUTHORIZED(1101, "missing or invalid api key", 401),
    FORBIDDEN(1102, "insufficient permission", 403),

    NOT_FOUND(1201, "resource not found", 404),

    SEARCH_RANGE_EXCEEDED(1301, "search time range exceeds limit", 400),
    DASHBOARD_RANGE_EXCEEDED(1302, "dashboard time range exceeds limit", 400),
    EXPORT_LIMIT_EXCEEDED(1303, "export result set exceeds limit, narrow the query", 400),
    EXPORT_FORMAT_UNSUPPORTED(1304, "export format not supported", 400),

    STORAGE_ERROR(1401, "log storage error", 502),
    STORAGE_TIMEOUT(1402, "log storage query timeout", 504),

    INTERNAL_ERROR(1500, "internal server error", 500);

    private final int code;
    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }
}

package com.obsroman.tracelog.sdk;

/**
 * SDK 异常：仅在同步接口（sendSync / sendBatchSync）重试耗尽或参数非法时抛出；
 * 异步接口（send）遵循"日志故障不影响业务"原则，失败静默丢弃并计数。
 */
public class TraceLogException extends RuntimeException {

    public TraceLogException(String message) {
        super(message);
    }

    public TraceLogException(String message, Throwable cause) {
        super(message, cause);
    }
}

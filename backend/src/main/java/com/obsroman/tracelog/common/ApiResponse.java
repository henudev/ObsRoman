package com.obsroman.tracelog.common;

/**
 * 统一响应包装：{"code":0,"message":"ok","data":{...}}。
 * code=0 表示成功，非 0 见 {@link ErrorCode}。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.OK.code(), "ok", null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), "ok", data);
    }

    public static ApiResponse<Void> accepted() {
        return new ApiResponse<>(ErrorCode.OK.code(), "accepted", null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }
}

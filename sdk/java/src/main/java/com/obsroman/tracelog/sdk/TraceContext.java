package com.obsroman.tracelog.sdk;

import java.security.SecureRandom;

/**
 * W3C Trace Context（traceparent: 00-{trace_id}-{span_id}-{flags}）工具。
 * 请求入口存在 traceparent 时必须沿用原 trace_id；不存在时本地生成。
 */
public final class TraceContext {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.util.regex.Pattern PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final String traceId;
    private final String spanId;

    private TraceContext(String traceId, String spanId) {
        this.traceId = traceId;
        this.spanId = spanId;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    /**
     * 解析请求中的 traceparent header（如 00-4bf9...-00f0...-01）。
     * 非法/缺失返回 null，调用方应回退到 {@link #generate()}。
     */
    public static TraceContext parse(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String value = traceparent.trim().toLowerCase();
        if (!PATTERN.matcher(value).matches()) {
            return null;
        }
        String[] parts = value.split("-");
        return new TraceContext(parts[1], parts[2]);
    }

    /** 本地生成全新链路（无上游 traceparent 时使用） */
    public static TraceContext generate() {
        return new TraceContext(randomHex(16), randomHex(8));
    }

    /** 沿用上游 trace_id，生成新的 span_id（调用下游前用于传播） */
    public static TraceContext child(TraceContext parent) {
        return new TraceContext(parent.traceId, randomHex(8));
    }

    /** 组装 traceparent header 值（flags 固定 01） */
    public static String format(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /** 当前上下文的 traceparent header 值 */
    public String traceparent() {
        return format(traceId, spanId);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

package com.obsroman.tracelog.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一日志模型（与服务端 /api/v1/logs 契约一致）。
 * 一级字段白名单之外的数据必须放入 attributes。
 */
public final class LogRecord {

    private String timestamp;        // ISO-8601，缺省由服务端补接收时间
    private String traceId;          // 必填，32 位十六进制
    private String spanId;           // 可选，16 位十六进制
    private String parentSpanId;
    private String requestId;
    private String service;          // 必填，小写中划线
    private String serviceVersion;
    private String environment;      // local/dev/test/staging/prod，缺省 local
    private String level;            // 必填 TRACE/DEBUG/INFO/WARN/ERROR/FATAL
    private String type;             // 缺省 application
    private String event;
    private String message;          // 必填，非空
    private String userId;
    private String method;
    private String path;
    private Long statusCode;
    private Long durationMs;
    private String host;
    private String instance;
    private Map<String, Object> attributes;

    private LogRecord() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final LogRecord record = new LogRecord();

        public Builder timestamp(String iso8601) {
            record.timestamp = iso8601;
            return this;
        }

        public Builder timestampNow() {
            record.timestamp = java.time.OffsetDateTime.now().format(
                    java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return this;
        }

        public Builder traceId(String traceId) {
            record.traceId = traceId;
            return this;
        }

        public Builder spanId(String spanId) {
            record.spanId = spanId;
            return this;
        }

        public Builder parentSpanId(String parentSpanId) {
            record.parentSpanId = parentSpanId;
            return this;
        }

        public Builder requestId(String requestId) {
            record.requestId = requestId;
            return this;
        }

        public Builder service(String service) {
            record.service = service;
            return this;
        }

        public Builder serviceVersion(String serviceVersion) {
            record.serviceVersion = serviceVersion;
            return this;
        }

        public Builder environment(String environment) {
            record.environment = environment;
            return this;
        }

        public Builder level(String level) {
            record.level = level;
            return this;
        }

        public Builder type(String type) {
            record.type = type;
            return this;
        }

        public Builder event(String event) {
            record.event = event;
            return this;
        }

        public Builder message(String message) {
            record.message = message;
            return this;
        }

        public Builder userId(String userId) {
            record.userId = userId;
            return this;
        }

        public Builder method(String method) {
            record.method = method;
            return this;
        }

        public Builder path(String path) {
            record.path = path;
            return this;
        }

        public Builder statusCode(long statusCode) {
            record.statusCode = statusCode;
            return this;
        }

        public Builder durationMs(long durationMs) {
            record.durationMs = durationMs;
            return this;
        }

        public Builder host(String host) {
            record.host = host;
            return this;
        }

        public Builder instance(String instance) {
            record.instance = instance;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            record.attributes = attributes == null ? null : new LinkedHashMap<>(attributes);
            return this;
        }

        public Builder attribute(String key, Object value) {
            if (record.attributes == null) {
                record.attributes = new LinkedHashMap<>();
            }
            record.attributes.put(key, value);
            return this;
        }

        /** 从 W3C Trace Context 填充 trace_id / span_id */
        public Builder traceContext(TraceContext context) {
            record.traceId = context.traceId();
            record.spanId = context.spanId();
            return this;
        }

        public LogRecord build() {
            // 未传 trace_id 时本地自动生成（与服务端"无上游链路则本地生成"的规范一致）
            if (record.traceId == null || record.traceId.isBlank()) {
                record.traceId = TraceContext.generate().traceId();
            }
            if (!record.traceId.matches("^[0-9a-f]{32}$")) {
                throw new TraceLogException("trace_id must be a 32-char hex string");
            }
            if (record.service == null || !record.service.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
                throw new TraceLogException("service must be lowercase words joined by '-' (e.g. order-service)");
            }
            if (record.level == null) {
                throw new TraceLogException("level is required");
            }
            if (record.message == null || record.message.isBlank()) {
                throw new TraceLogException("message is required and must not be blank");
            }
            return record;
        }
    }

    Map<String, Object> attributes() {
        return attributes;
    }

    Map<String, Object> toMap() {
        return recordMap();
    }

    String toJson() {
        return Json.write(recordMap());
    }

    private Map<String, Object> recordMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "timestamp", timestamp);
        put(map, "trace_id", traceId);
        put(map, "span_id", spanId);
        put(map, "parent_span_id", parentSpanId);
        put(map, "request_id", requestId);
        put(map, "service", service);
        put(map, "service_version", serviceVersion);
        put(map, "environment", environment);
        put(map, "level", level);
        put(map, "type", type);
        put(map, "event", event);
        put(map, "message", message);
        put(map, "user_id", userId);
        put(map, "method", method);
        put(map, "path", path);
        put(map, "status_code", statusCode);
        put(map, "duration_ms", durationMs);
        put(map, "host", host);
        put(map, "instance", instance);
        put(map, "attributes", attributes);
        return map;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}

package com.obsroman.tracelog.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.common.ErrorCode;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一日志模型的解析与校验。所有规则集中在此处：
 * - 一级字段白名单（禁止业务自行增加一级字段，扩展数据进 attributes）
 * - trace_id 32 位十六进制；service 小写中划线；level/environment 受控枚举
 * - 单条日志字节上限
 */
public final class LogRecordParser {

    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "timestamp", "trace_id", "span_id", "parent_span_id", "request_id",
            "service", "service_version", "environment", "level", "type", "event",
            "message", "user_id", "method", "path", "status_code", "duration_ms",
            "host", "instance", "attributes");

    public static final Set<String> LEVELS =
            Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

    public static final Set<String> ENVIRONMENTS =
            Set.of("local", "dev", "test", "staging", "prod");

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern SERVICE_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final int MAX_SERVICE_VERSION = 64;
    private static final int MAX_TYPE = 64;
    private static final int MAX_EVENT = 256;
    private static final int MAX_MESSAGE = 32768;
    private static final int MAX_ID_FIELD = 128;
    private static final int MAX_METHOD = 16;
    private static final int MAX_PATH = 2048;
    private static final int MAX_HOST = 255;
    private static final int MAX_INSTANCE = 255;
    private static final int MAX_ATTRIBUTE_KEY = 128;
    private static final int MAX_ATTRIBUTE_COUNT = 50;

    private LogRecordParser() {
    }

    /**
     * @param receiveTime 日志缺少 timestamp 时使用的接收时间
     */
    public static LogRecord parse(JsonNode node,
                                  OffsetDateTime receiveTime,
                                  ObjectMapper mapper,
                                  long maxLogBytes) {
        if (node == null || !node.isObject()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, "log entry must be a JSON object");
        }

        LogRecord record = new LogRecord();

        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!ALLOWED_FIELDS.contains(field.getKey())) {
                throw new LogValidationException(ErrorCode.INVALID_LOG,
                        "unknown top-level field '" + field.getKey() + "', put extension data in 'attributes'");
            }
        }

        record.setTraceId(requireHex(node, "trace_id", TRACE_ID_PATTERN, "trace_id must be a 32-char hex string"));
        if (node.hasNonNull("span_id")) {
            record.setSpanId(requireHex(node, "span_id", SPAN_ID_PATTERN, "span_id must be a 16-char hex string"));
        }
        if (node.hasNonNull("parent_span_id")) {
            record.setParentSpanId(requireHex(node, "parent_span_id", SPAN_ID_PATTERN,
                    "parent_span_id must be a 16-char hex string"));
        }

        record.setService(requireMatching(node, "service", SERVICE_PATTERN,
                "service must be lowercase words joined by '-' (e.g. order-service)"));

        record.setLevel(requireEnum(node, "level", LEVELS, "level must be one of " + LEVELS));

        record.setEnvironment(parseEnvironment(node));

        record.setType(parseOptionalString(node, "type", MAX_TYPE));
        if (record.getType() == null) {
            record.setType("application");
        }

        record.setEvent(parseOptionalString(node, "event", MAX_EVENT));
        record.setRequestId(parseOptionalString(node, "request_id", MAX_ID_FIELD));
        record.setUserId(parseOptionalString(node, "user_id", MAX_ID_FIELD));
        record.setMethod(parseOptionalString(node, "method", MAX_METHOD));
        record.setPath(parseOptionalString(node, "path", MAX_PATH));
        record.setHost(parseOptionalString(node, "host", MAX_HOST));
        record.setInstance(parseOptionalString(node, "instance", MAX_INSTANCE));
        record.setServiceVersion(parseOptionalString(node, "service_version", MAX_SERVICE_VERSION));

        String message = parseOptionalString(node, "message", MAX_MESSAGE);
        if (message == null || message.isBlank()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, "message is required and must not be blank");
        }
        record.setMessage(message);

        if (node.hasNonNull("status_code")) {
            JsonNode sc = node.get("status_code");
            if (!sc.canConvertToLong() || sc.asLong() < 0 || sc.asLong() > 999) {
                throw new LogValidationException(ErrorCode.INVALID_LOG, "status_code must be an integer in [0, 999]");
            }
            record.setStatusCode(sc.asLong());
        }

        if (node.hasNonNull("duration_ms")) {
            JsonNode d = node.get("duration_ms");
            if (!d.canConvertToLong() || d.asLong() < 0) {
                throw new LogValidationException(ErrorCode.INVALID_LOG, "duration_ms must be a non-negative integer");
            }
            record.setDurationMs(d.asLong());
        }

        record.setAttributes(parseAttributes(node));

        OffsetDateTime timestamp = parseTimestamp(node, receiveTime);
        record.setTimestamp(timestamp);
        record.setTimestampMicros(toMicros(timestamp));

        enforceSizeLimit(record, mapper, maxLogBytes);
        return record;
    }

    private static final ObjectMapper SIZE_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static void enforceSizeLimit(LogRecord record, ObjectMapper mapper, long maxLogBytes) {
        try {
            byte[] bytes = SIZE_MAPPER.writeValueAsBytes(record);
            if (bytes.length > maxLogBytes) {
                throw new LogValidationException(ErrorCode.LOG_TOO_LARGE,
                        "log record size " + bytes.length + " bytes exceeds limit " + maxLogBytes);
            }
        } catch (JsonProcessingException e) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, "log record is not serializable");
        }
    }

    private static OffsetDateTime parseTimestamp(JsonNode node, OffsetDateTime receiveTime) {
        JsonNode ts = node.get("timestamp");
        if (ts == null || ts.isNull()) {
            return receiveTime;
        }
        if (ts.isNumber()) {
            long value = ts.asLong();
            // 大于 1e14 视为微秒，否则视为毫秒
            long micros = value >= 100_000_000_000_000L ? value : value * 1000L;
            return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(micros / 1000L), ZoneId.systemDefault())
                    .withNano((int) (micros % 1_000_000L) * 1000);
        }
        if (!ts.isTextual()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, "timestamp must be ISO-8601 text");
        }
        try {
            return parseFlexibleTimestamp(ts.asText().trim());
        } catch (LogValidationException e) {
            throw new LogValidationException(ErrorCode.INVALID_LOG,
                    "timestamp '" + ts.asText() + "' is not a valid ISO-8601 datetime");
        }
    }

    /**
     * 解析 ISO-8601 时间文本：支持带偏移（推荐）、UTC 'Z'、无偏移本地时间、epoch 毫秒/微秒数字文本。
     */
    public static OffsetDateTime parseFlexibleTimestamp(String text) {
        if (text == null || text.isBlank()) {
            throw new LogValidationException(ErrorCode.INVALID_REQUEST, "time must not be blank");
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return java.time.Instant.parse(text).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            long value = Long.parseLong(text.trim());
            long micros = value >= 100_000_000_000_000L ? value : value * 1000L;
            return fromMicros(micros);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new LogValidationException(ErrorCode.INVALID_REQUEST,
                "'" + text + "' is not a valid ISO-8601 datetime");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseAttributes(JsonNode node) {
        JsonNode attrs = node.get("attributes");
        if (attrs == null || attrs.isNull()) {
            return null;
        }
        if (!attrs.isObject()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, "attributes must be a JSON object");
        }
        if (attrs.size() > MAX_ATTRIBUTE_COUNT) {
            throw new LogValidationException(ErrorCode.INVALID_LOG,
                    "attributes must not contain more than " + MAX_ATTRIBUTE_COUNT + " entries");
        }
        var result = new java.util.LinkedHashMap<String, Object>();
        attrs.properties().forEach(entry -> {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > MAX_ATTRIBUTE_KEY) {
                throw new LogValidationException(ErrorCode.INVALID_LOG,
                        "attribute key must be 1-" + MAX_ATTRIBUTE_KEY + " chars");
            }
            JsonNode value = entry.getValue();
            if (value.isValueNode()) {
                result.put(key, mapperSafeValue(value));
            } else {
                // 嵌套结构序列化保存，避免存储端打平导致字段不可控
                result.put(key, value.toString());
            }
        });
        return result;
    }

    private static Object mapperSafeValue(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }
        return value.asText();
    }

    private static String parseEnvironment(JsonNode node) {
        JsonNode env = node.get("environment");
        if (env == null || env.isNull()) {
            return "local";
        }
        if (!env.isTextual()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, "environment must be a string");
        }
        String value = env.asText().trim().toLowerCase(Locale.ROOT);
        if (!ENVIRONMENTS.contains(value)) {
            throw new LogValidationException(ErrorCode.INVALID_LOG,
                    "environment must be one of " + ENVIRONMENTS);
        }
        return value;
    }

    private static String requireHex(JsonNode node, String field, Pattern pattern, String error) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, field + " is required");
        }
        if (!value.isTextual()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, error);
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        if (!pattern.matcher(text).matches()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, error);
        }
        return text;
    }

    private static String requireMatching(JsonNode node, String field, Pattern pattern, String error) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, field + " is required");
        }
        if (!value.isTextual()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, error);
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || !pattern.matcher(text).matches() || text.length() > 128) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, error);
        }
        return text;
    }

    private static String requireEnum(JsonNode node, String field, Set<String> allowed, String error) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, field + " is required");
        }
        if (!value.isTextual()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, error);
        }
        String text = value.asText().trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(text)) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, error);
        }
        return text;
    }

    private static String parseOptionalString(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new LogValidationException(ErrorCode.INVALID_LOG, field + " must be a string");
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > maxLength) {
            throw new LogValidationException(ErrorCode.INVALID_LOG,
                    field + " exceeds max length " + maxLength);
        }
        return text;
    }

    public static long toMicros(OffsetDateTime time) {
        return time.toInstant().getEpochSecond() * 1_000_000L + time.toInstant().getNano() / 1000L;
    }

    public static OffsetDateTime fromMicros(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        int nanos = (int) Math.floorMod(micros, 1_000_000L) * 1000;
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(seconds, nanos), ZoneId.systemDefault());
    }
}

package com.obsroman.tracelog.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsroman.tracelog.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogRecordParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OffsetDateTime receiveTime = OffsetDateTime.parse("2026-09-01T14:20:30.123+08:00");

    private LogRecord parse(String json) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(json);
            return LogRecordParser.parse(node, receiveTime, mapper, 65536);
        } catch (LogValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesValidRecord() {
        LogRecord record = parse("""
                {
                  "timestamp": "2026-09-01T14:20:30.123+08:00",
                  "trace_id": "4BF92F3577B34DA6A3CE929D0E0E4736",
                  "span_id": "00f067aa0ba902b7",
                  "request_id": "req_001",
                  "service": "order-service",
                  "service_version": "1.0.0",
                  "environment": "prod",
                  "level": "INFO",
                  "type": "application",
                  "event": "order.create",
                  "message": "创建订单成功",
                  "user_id": "10001",
                  "method": "POST",
                  "path": "/api/orders",
                  "status_code": 200,
                  "duration_ms": 123,
                  "host": "10.0.0.1",
                  "instance": "order-service-01",
                  "attributes": {"order_id": "ORD001"}
                }
                """);
        assertThat(record.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(record.getService()).isEqualTo("order-service");
        assertThat(record.getLevel()).isEqualTo("INFO");
        assertThat(record.getEnvironment()).isEqualTo("prod");
        assertThat(record.getStatusCode()).isEqualTo(200);
        assertThat(record.getDurationMs()).isEqualTo(123);
        assertThat(record.getAttributes()).containsEntry("order_id", "ORD001");
        assertThat(record.getTimestampMicros()).isEqualTo(OffsetDateTime.parse("2026-09-01T14:20:30.123+08:00").toInstant().toEpochMilli() * 1000);
    }

    @Test
    void fillsDefaultsForMissingOptionalFields() {
        LogRecord record = parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "order-service", "level": "info", "message": "m"}
                """);
        assertThat(record.getTimestamp()).isEqualTo(receiveTime);
        assertThat(record.getEnvironment()).isEqualTo("local");
        assertThat(record.getType()).isEqualTo("application");
        assertThat(record.getLevel()).isEqualTo("INFO");
    }

    @Test
    void rejectsMissingTraceId() {
        assertThatThrownBy(() -> parse("""
                {"service": "order-service", "level": "INFO", "message": "m"}
                """))
                .isInstanceOf(LogValidationException.class)
                .extracting(e -> ((LogValidationException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_LOG);
    }

    @Test
    void rejectsMalformedTraceId() {
        assertThatThrownBy(() -> parse("""
                {"trace_id": "short-id", "service": "s", "level": "INFO", "message": "m"}
                """)).isInstanceOf(LogValidationException.class);
    }

    @Test
    void rejectsUppercaseServiceName() {
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "Order_Service", "level": "INFO", "message": "m"}
                """)).isInstanceOf(LogValidationException.class);
    }

    @Test
    void rejectsUnknownLevelAndEnvironment() {
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "VERBOSE", "message": "m"}
                """)).isInstanceOf(LogValidationException.class);
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "environment": "uat", "message": "m"}
                """)).isInstanceOf(LogValidationException.class);
    }

    @Test
    void rejectsUnknownTopLevelField() {
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "message": "m", "extra": 1}
                """))
                .isInstanceOf(LogValidationException.class)
                .hasMessageContaining("unknown top-level field 'extra'");
    }

    @Test
    void rejectsBlankMessage() {
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "message": "  "}
                """)).isInstanceOf(LogValidationException.class);
    }

    @Test
    void rejectsOversizedLog() {
        // 单字段均在限内，但整体序列化后超过 64KB
        StringBuilder attributes = new StringBuilder("{");
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                attributes.append(',');
            }
            attributes.append("\"k").append(i).append("\":\"").append("v".repeat(8000)).append('"');
        }
        attributes.append('}');
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO",
                 "message": "%s", "attributes": %s}
                """.formatted("x".repeat(30000), attributes)))
                .isInstanceOf(LogValidationException.class)
                .extracting(e -> ((LogValidationException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOG_TOO_LARGE);
    }

    @Test
    void serializesNestedAttributesAsString() {
        LogRecord record = parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "message": "m",
                 "attributes": {"nested": {"a": 1}, "flag": true, "num": 3}}
                """);
        assertThat(record.getAttributes())
                .containsEntry("flag", true)
                .containsEntry("num", 3L);
        assertThat((String) record.getAttributes().get("nested")).contains("\"a\":1");
    }

    @Test
    void rejectsBadNumericFields() {
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "message": "m", "status_code": "abc"}
                """)).isInstanceOf(LogValidationException.class);
        assertThatThrownBy(() -> parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "message": "m", "duration_ms": -1}
                """)).isInstanceOf(LogValidationException.class);
    }

    @Test
    void acceptsEpochTimestamp() {
        LogRecord record = parse("""
                {"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "service": "s", "level": "INFO", "message": "m", "timestamp": 1788246030123000}
                """);
        assertThat(record.getTimestampMicros()).isEqualTo(1788246030123000L);
    }

    @Test
    void microsRoundTrip() {
        OffsetDateTime now = OffsetDateTime.now().withNano(123_000_000);
        long micros = LogRecordParser.toMicros(now);
        assertThat(LogRecordParser.fromMicros(micros).toInstant().toEpochMilli())
                .isEqualTo(now.toInstant().toEpochMilli());
    }

    @Test
    void attributesMapIsCopied() {
        LogRecord record = new LogRecord();
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("k", "v");
        record.setAttributes(attributes);
        attributes.put("k2", "v2");
        assertThat(record.getAttributes()).containsOnlyKeys("k");
        record.addAttribute("k3", 1);
        assertThat(record.getAttributes()).containsKey("k3");
    }
}

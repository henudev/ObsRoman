package com.obsroman.tracelog.storage.openobserve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.service.LogQueryFactory;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenObserveLogStorage 的序列化/反序列化映射测试（不发起 HTTP）。
 */
class OpenObserveLogStorageMappingTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OpenObserveLogStorage storage = new OpenObserveLogStorage(
            new OpenObserveClient(mapper, new TraceLogProperties.OpenObserve()),
            mapper, new TraceLogProperties());

    @Test
    void storageDocContainsTimestampAndFlatAttributes() throws Exception {
        LogRecord record = new LogRecord();
        record.setTimestamp(OffsetDateTime.parse("2026-09-01T14:20:30.123+08:00"));
        record.setTimestampMicros(1788246030123000L);
        record.setTraceId("4bf92f3577b34da6a3ce929d0e0e4736");
        record.setService("order-service");
        record.setLevel("INFO");
        record.setEnvironment("prod");
        record.setMessage("创建订单成功");
        record.setDurationMs(123L);
        record.setAttributes(Map.of("order_id", "ORD001"));

        var doc = storage.toStorageDoc(record);
        assertThat(doc.get("_timestamp").asLong()).isEqualTo(1788246030123000L);
        assertThat(doc.get("timestamp").asText()).isEqualTo("2026-09-01T14:20:30.123+08:00");
        assertThat(doc.get("trace_id").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        // attributes 以 JSON 字符串存储，避免嵌套字段被存储端打平
        assertThat(doc.get("attributes").isTextual()).isTrue();
        assertThat(doc.get("attributes").asText()).contains("\"order_id\":\"ORD001\"");
        assertThat(doc.has("span_id")).isFalse();
    }

    @Test
    void hitMappingParsesAttributesString() {
        var doc = storage.toStorageDoc(recordForMapping());
        var hit = mapper.createObjectNode();
        doc.fields().forEachRemaining(entry -> hit.set(entry.getKey(), entry.getValue()));
        hit.put("_timestamp", 1788246030123000L);

        LogRecord parsed = storage.fromHit(hit);
        assertThat(parsed.getTimestampMicros()).isEqualTo(1788246030123000L);
        assertThat(parsed.getService()).isEqualTo("order-service");
        assertThat(parsed.getAttributes()).containsEntry("order_id", "ORD001");
        assertThat(parsed.getMessage()).isEqualTo("创建订单成功");
        assertThat(parsed.getDurationMs()).isEqualTo(123L);
    }

    @Test
    void searchBuildsExpectedSql() {
        LogQueryFactory factory = new LogQueryFactory(new TraceLogProperties());
        LogSearchRequest request = new LogSearchRequest();
        request.setStartTime("2026-09-01T13:00:00+08:00");
        request.setEndTime("2026-09-01T14:00:00+08:00");
        request.setLevel(java.util.List.of("ERROR"));
        LogQuery query = factory.create(request, false);

        String sql = OpenObserveSql.selectAll(query, "trace_logs", query.isAscending());
        assertThat(sql).startsWith("SELECT * FROM \"trace_logs\" WHERE _timestamp >=");
        assertThat(sql).contains("level IN ('ERROR')").endsWith("ORDER BY _timestamp DESC");
    }

    private LogRecord recordForMapping() {
        LogRecord record = new LogRecord();
        record.setTimestamp(OffsetDateTime.parse("2026-09-01T14:20:30.123+08:00"));
        record.setTimestampMicros(1788246030123000L);
        record.setService("order-service");
        record.setMessage("创建订单成功");
        record.setDurationMs(123L);
        record.setAttributes(Map.of("order_id", "ORD001"));
        return record;
    }
}

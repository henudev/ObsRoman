package com.obsroman.tracelog.storage.openobserve;

import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.LogQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenObserveSqlTest {

    private LogQuery query() {
        LogQuery query = new LogQuery();
        query.setStartTimeMicros(1_000_000L);
        query.setEndTimeMicros(2_000_000L);
        return query;
    }

    @Test
    void buildsTimeRangeOnly() {
        String where = OpenObserveSql.whereClause(query());
        assertThat(where).isEqualTo("_timestamp >= 1000000 AND _timestamp < 2000000");
    }

    @Test
    void buildsAllFilters() {
        LogQuery query = query();
        query.setServices(List.of("order-service", "payment-service"));
        query.setEnvironments(List.of("prod"));
        query.setLevels(List.of("WARN", "ERROR"));
        query.setTypes(List.of("application"));
        query.setTraceId("abc");
        query.setRequestId("req_1");
        query.setUserId("10001");
        query.setKeyword("timeout");
        String where = OpenObserveSql.whereClause(query);

        assertThat(where)
                .contains("service IN ('order-service', 'payment-service')")
                .contains("environment IN ('prod')")
                .contains("level IN ('WARN', 'ERROR')")
                .contains("type IN ('application')")
                .contains("trace_id = 'abc'")
                .contains("request_id = 'req_1'")
                .contains("user_id = '10001'")
                .contains("str_match(message, 'timeout')")
                .contains("str_match(attributes, 'timeout')");
    }

    @Test
    void keywordWithQuotesIsNeutralized() {
        LogQuery query = query();
        query.setKeyword("a' OR '1'='1");
        String where = OpenObserveSql.whereClause(query);
        // 引号被剔除，无法逃逸出字符串字面量
        assertThat(where).doesNotContain("''").contains("str_match(message, 'a OR 1 = 1')");
    }

    @Test
    void escapesSingleQuotesToPreventInjection() {
        LogQuery query = query();
        query.setTraceId("x' OR '1'='1");
        String where = OpenObserveSql.whereClause(query);
        assertThat(where).contains("trace_id = 'x'' OR ''1''=''1'");
    }

    @Test
    void keywordIsSanitized() {
        assertThat(OpenObserveSql.sanitizeKeyword("  time'out\n  ")).isEqualTo("time out");
        assertThat(OpenObserveSql.sanitizeKeyword("a  b")).isEqualTo("a b");
        assertThat(OpenObserveSql.sanitizeKeyword("   ")).isNull();
        assertThat(OpenObserveSql.sanitizeKeyword(null)).isNull();
    }

    @Test
    void selectAllOrdersByTimestamp() {
        assertThat(OpenObserveSql.selectAll(query(), "trace_logs", true))
                .isEqualTo("SELECT * FROM \"trace_logs\" WHERE _timestamp >= 1000000 AND _timestamp < 2000000 ORDER BY _timestamp ASC");
        assertThat(OpenObserveSql.selectAll(query(), "trace_logs", false))
                .endsWith("ORDER BY _timestamp DESC");
    }

    @Test
    void overviewSqlContainsAggregations() {
        String sql = OpenObserveSql.overviewSql(new DashboardQuery(), "trace_logs");
        assertThat(sql)
                .contains("count(*) AS total_logs")
                .contains("SUM(CASE WHEN level IN ('ERROR', 'FATAL') THEN 1 ELSE 0 END) AS error_logs")
                .contains("count(DISTINCT trace_id) AS trace_count")
                .contains("approx_percentile_cont(duration_ms, 0.95)");
    }

    @Test
    void trendSqlUsesHistogram() {
        DashboardQuery query = new DashboardQuery();
        query.setStartTimeMicros(0);
        query.setEndTimeMicros(3600L * 1_000_000L);
        String sql = OpenObserveSql.trendSql(query, "trace_logs");
        assertThat(sql).contains("histogram(_timestamp, '1 minutes')").contains("GROUP BY bucket_ts");
    }

    @Test
    void histogramIntervalSnapsToBuckets() {
        DashboardQuery query = new DashboardQuery();
        query.setStartTimeMicros(0);
        query.setEndTimeMicros(15L * 60 * 1_000_000L); // 15 分钟 → 10s/15s 桶
        assertThat(OpenObserveSql.histogramInterval(query)).isEqualTo("15 seconds");

        query.setEndTimeMicros(24L * 3600 * 1_000_000L); // 24 小时 → 30 minutes
        assertThat(OpenObserveSql.histogramInterval(query)).isEqualTo("30 minutes");
    }

    @Test
    void traceSqlEscapesTraceId() {
        assertThat(OpenObserveSql.traceSql("abc", "trace_logs"))
                .isEqualTo("SELECT * FROM \"trace_logs\" WHERE trace_id = 'abc' ORDER BY _timestamp ASC");
    }

    @Test
    void streamRefStripsQuotes() {
        assertThat(OpenObserveSql.streamRef("tra\"ce_logs")).isEqualTo("\"trace_logs\"");
    }
}

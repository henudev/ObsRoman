package com.obsroman.tracelog.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.DashboardResult;
import com.obsroman.tracelog.model.LevelDistributionItem;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.model.LogSearchResult;
import com.obsroman.tracelog.model.ServiceRankingItem;
import com.obsroman.tracelog.model.TrendPoint;
import com.obsroman.tracelog.storage.openobserve.OpenObserveClient;
import com.obsroman.tracelog.storage.openobserve.OpenObserveLogStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * OpenObserve 集成测试（需要真实 OpenObserve）。
 *
 * 默认跳过。通过环境变量开启（docker compose up openobserve 后即可运行）：
 *   OO_IT_URL=http://localhost:5080
 *   OO_IT_USER=root@example.com
 *   OO_IT_PASSWORD=Complexpass#123
 */
@EnabledIfEnvironmentVariable(named = "OO_IT_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenObserveLogStorageIT {

    private static OpenObserveLogStorage storage;
    private static final String TRACE = "e05af3d1c9b34a0f8e37b1f05f2f4a10";

    @BeforeAll
    static void setUp() {
        TraceLogProperties properties = new TraceLogProperties();
        TraceLogProperties.OpenObserve oo = properties.getStorage().getOpenobserve();
        oo.setBaseUrl(System.getenv("OO_IT_URL"));
        oo.setOrganization(System.getenv().getOrDefault("OO_IT_ORG", "default"));
        oo.setUsername(System.getenv().getOrDefault("OO_IT_USER", "root@example.com"));
        oo.setPassword(System.getenv().getOrDefault("OO_IT_PASSWORD", "Complexpass#123"));
        oo.setStreamName(System.getenv().getOrDefault("OO_IT_STREAM", "trace_logs_it_" + System.currentTimeMillis()));
        storage = new OpenObserveLogStorage(new OpenObserveClient(new ObjectMapper(), oo),
                new ObjectMapper().findAndRegisterModules(), properties);
    }

    @AfterAll
    static void tearDown() {
        // 流按时间戳唯一命名，无需清理
    }

    private LogRecord log(String service, String level, String message, long micros, long durationMs) {
        LogRecord record = new LogRecord();
        record.setTimestamp(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(micros / 1000),
                java.time.ZoneId.systemDefault()));
        record.setTimestampMicros(micros);
        record.setTraceId(TRACE);
        record.setService(service);
        record.setEnvironment("prod");
        record.setLevel(level);
        record.setType("application");
        record.setMessage(message);
        record.setDurationMs(durationMs);
        record.setRequestId("req_it");
        record.setUserId("10001");
        record.setAttributes(Map.of("it", "true"));
        return record;
    }

    @Test
    @Order(1)
    void writesBatchToOpenObserve() {
        long now = System.currentTimeMillis() * 1000L;
        List<LogRecord> batch = new ArrayList<>();
        batch.add(log("gateway", "INFO", "收到请求", now - 3_000_000L, 5));
        batch.add(log("order-service", "INFO", "创建订单成功", now - 2_000_000L, 120));
        batch.add(log("payment-service", "ERROR", "支付超时 timeout", now - 1_000_000L, 2000));

        storage.write(batch);

        // OpenObserve 写入准实时可见，轮询确认落库
        await().atMost(java.time.Duration.ofSeconds(15)).until(() -> {
            LogQuery query = new LogQuery();
            query.setStartTimeMicros(now - 3_600_000_000L);
            query.setEndTimeMicros(now + 3_600_000_000L);
            query.setTraceId(TRACE);
            return storage.count(query) >= 3;
        });
    }

    @Test
    @Order(2)
    void searchReturnsPagedResults() {
        long now = System.currentTimeMillis() * 1000L;
        LogQuery query = new LogQuery();
        query.setStartTimeMicros(now - 3_600_000_000L);
        query.setEndTimeMicros(now + 3_600_000_000L);
        query.setLevels(List.of("ERROR"));
        query.setKeyword("timeout");
        query.setSize(10);

        LogSearchResult result = storage.search(query);
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(result.getLogs()).allSatisfy(log -> {
            assertThat(log.getLevel()).isEqualTo("ERROR");
            assertThat(log.getAttributes()).containsEntry("it", "true");
            assertThat(log.getTimestamp()).isNotNull();
        });
    }

    @Test
    @Order(3)
    void findByTraceIdReturnsAscendingLogs() {
        List<LogRecord> logs = storage.findByTraceId(TRACE);
        assertThat(logs).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 1; i < logs.size(); i++) {
            assertThat(logs.get(i).getTimestampMicros())
                    .isGreaterThanOrEqualTo(logs.get(i - 1).getTimestampMicros());
        }
        assertThat(logs.get(0).getService()).isEqualTo("gateway");
    }

    @Test
    @Order(4)
    void aggregateReturnsOverviewTrendLevelsRanking() {
        long now = System.currentTimeMillis() * 1000L;
        DashboardQuery query = new DashboardQuery();
        query.setStartTimeMicros(now - 3_600_000_000L);
        query.setEndTimeMicros(now + 3_600_000_000L);
        query.setEnvironment("prod");
        query.setMetrics(java.util.EnumSet.allOf(DashboardQuery.Metric.class));

        DashboardResult result = storage.aggregate(query);
        assertThat(result.getOverview().getTotalLogs()).isGreaterThanOrEqualTo(3);
        assertThat(result.getOverview().getErrorLogs()).isGreaterThanOrEqualTo(1);
        assertThat(result.getOverview().getP95DurationMs()).isNotNull();
        assertThat(result.getLogTrend()).isNotEmpty();
        // 桶升序
        for (int i = 1; i < result.getLogTrend().size(); i++) {
            assertThat(result.getLogTrend().get(i).bucket())
                    .isGreaterThanOrEqualTo(result.getLogTrend().get(i - 1).bucket());
        }
        assertThat(result.getLevelDistribution())
                .extracting(LevelDistributionItem::level).contains("ERROR", "INFO");
        assertThat(result.getServiceRanking())
                .extracting(ServiceRankingItem::service)
                .contains("payment-service");
    }

    @Test
    @Order(5)
    void exportCursorStreamsInBatches() {
        long now = System.currentTimeMillis() * 1000L;
        LogQuery query = new LogQuery();
        query.setStartTimeMicros(now - 3_600_000_000L);
        query.setEndTimeMicros(now + 3_600_000_000L);
        query.setTraceId(TRACE);
        query.setAscending(true);

        List<LogRecord> all = new ArrayList<>();
        try (LogCursor cursor = storage.searchForExport(query)) {
            List<LogRecord> batch;
            while (!(batch = cursor.nextBatch(2)).isEmpty()) {
                all.addAll(batch);
            }
        }
        assertThat(all.size()).isGreaterThanOrEqualTo(3);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).getTimestampMicros())
                    .isGreaterThanOrEqualTo(all.get(i - 1).getTimestampMicros());
        }
    }

    @Test
    @Order(6)
    void healthyReportsOpenObserveState() {
        assertThat(new OpenObserveClient(new ObjectMapper(), props()).healthy()).isTrue();
    }

    private TraceLogProperties.OpenObserve props() {
        TraceLogProperties.OpenObserve oo = new TraceLogProperties.OpenObserve();
        oo.setBaseUrl(System.getenv("OO_IT_URL"));
        oo.setUsername(System.getenv().getOrDefault("OO_IT_USER", "root@example.com"));
        oo.setPassword(System.getenv().getOrDefault("OO_IT_PASSWORD", "Complexpass#123"));
        return oo;
    }
}

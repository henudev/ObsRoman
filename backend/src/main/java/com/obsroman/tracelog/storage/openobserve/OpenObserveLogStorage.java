package com.obsroman.tracelog.storage.openobserve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.DashboardResult;
import com.obsroman.tracelog.model.LevelDistributionItem;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogSearchResult;
import com.obsroman.tracelog.model.ServiceRankingItem;
import com.obsroman.tracelog.model.TrendPoint;
import com.obsroman.tracelog.storage.LogCursor;
import com.obsroman.tracelog.storage.LogStorage;
import com.obsroman.tracelog.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * OpenObserve 存储实现。本项目所有 OpenObserve SQL 都集中在此类（经 OpenObserveSql 构建）。
 * 职责：写入（含 Retry）、条件查询、trace 链路查询、Dashboard 聚合、流式导出游标。
 *
 * 字段感知：OpenObserve 按流 Schema 校验 SQL 中的列名，本类缓存流 Schema
 * （TTL 60s），构建 SQL 时只引用已存在的列；命中 "unknown field" 类错误时刷新
 * Schema 并重建 SQL 重试一次。
 */
public class OpenObserveLogStorage implements LogStorage {

    private static final Logger log = LoggerFactory.getLogger(OpenObserveLogStorage.class);

    private static final int SERVICE_RANKING_LIMIT = 20;
    private static final long FIELD_CACHE_TTL_MS = 60_000;

    private final OpenObserveClient client;
    private final ObjectMapper mapper;
    private final String streamName;
    private final int traceMaxLogs;
    private final int exportPageSize;
    private final TraceLogProperties.OpenObserve retryProps;

    private volatile Set<String> fieldsCache;
    private volatile long fieldsFetchedAt;

    public OpenObserveLogStorage(OpenObserveClient client, ObjectMapper mapper, TraceLogProperties properties) {
        this.client = client;
        this.mapper = mapper;
        TraceLogProperties.OpenObserve oo = properties.getStorage().getOpenobserve();
        this.streamName = oo.getStreamName();
        this.traceMaxLogs = properties.getLimits().getTraceMaxLogs();
        this.exportPageSize = Math.max(1, properties.getLimits().getExportPageSize());
        this.retryProps = oo;
    }

    // ---------- 写入（Retry：仅网络异常/408/429/5xx，最多 3 次，100/500/2000ms） ----------

    @Override
    public void write(List<LogRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        List<ObjectNode> docs = new ArrayList<>(records.size());
        for (LogRecord record : records) {
            docs.add(toStorageDoc(record));
        }
        int attempt = 0;
        while (true) {
            try {
                OpenObserveClient.IngestResult result = client.ingest(docs);
                if (result.failed() > 0) {
                    log.warn("openobserve rejected {} of {} records (schema/type mismatch, not retried)",
                            result.failed(), docs.size());
                }
                return;
            } catch (StorageException e) {
                if (!e.isRetryable() || attempt >= retryProps.getRetry().getMaxAttempts()) {
                    throw e;
                }
                long backoff = backoffMs(attempt);
                log.warn("openobserve write failed (attempt {}), retrying in {}ms: {}",
                        attempt + 1, backoff, e.getMessage());
                sleep(backoff);
                attempt++;
            }
        }
    }

    private long backoffMs(int attempt) {
        List<Long> backoffs = retryProps.getRetry().getBackoffMs();
        if (backoffs == null || backoffs.isEmpty()) {
            return 100L * (attempt + 1);
        }
        return backoffs.get(Math.min(attempt, backoffs.size() - 1));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry interrupted", e);
        }
    }

    ObjectNode toStorageDoc(LogRecord record) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("_timestamp", record.getTimestampMicros());
        doc.put("timestamp", record.getTimestamp().toString());
        putIfNotNull(doc, "trace_id", record.getTraceId());
        putIfNotNull(doc, "span_id", record.getSpanId());
        putIfNotNull(doc, "parent_span_id", record.getParentSpanId());
        putIfNotNull(doc, "request_id", record.getRequestId());
        putIfNotNull(doc, "service", record.getService());
        putIfNotNull(doc, "service_version", record.getServiceVersion());
        putIfNotNull(doc, "environment", record.getEnvironment());
        putIfNotNull(doc, "level", record.getLevel());
        putIfNotNull(doc, "type", record.getType());
        putIfNotNull(doc, "event", record.getEvent());
        putIfNotNull(doc, "message", record.getMessage());
        putIfNotNull(doc, "user_id", record.getUserId());
        putIfNotNull(doc, "method", record.getMethod());
        putIfNotNull(doc, "path", record.getPath());
        if (record.getStatusCode() != null) {
            doc.put("status_code", record.getStatusCode());
        }
        if (record.getDurationMs() != null) {
            doc.put("duration_ms", record.getDurationMs());
        }
        putIfNotNull(doc, "host", record.getHost());
        putIfNotNull(doc, "instance", record.getInstance());
        if (record.getAttributes() != null && !record.getAttributes().isEmpty()) {
            doc.put("attributes", toJsonString(record.getAttributes()));
        }
        return doc;
    }

    private void putIfNotNull(ObjectNode doc, String field, String value) {
        if (value != null) {
            doc.put(field, value);
        }
    }

    private String toJsonString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    // ---------- Schema 感知 ----------

    private Set<String> knownFields() {
        Set<String> cached = fieldsCache;
        if (cached != null && System.currentTimeMillis() - fieldsFetchedAt < FIELD_CACHE_TTL_MS) {
            return cached;
        }
        Set<String> fresh = client.schemaFields();
        if (fresh != null && !fresh.isEmpty()) {
            fieldsCache = fresh;
            fieldsFetchedAt = System.currentTimeMillis();
            return fresh;
        }
        return cached != null ? cached : Set.of();
    }

    private Predicate<String> fieldPredicate() {
        Set<String> fields = knownFields();
        return fields.isEmpty() ? f -> true : fields::contains;
    }

    private void invalidateFields() {
        fieldsCache = null;
        fieldsFetchedAt = 0;
    }

    private static boolean isUnknownFieldError(StorageException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("unknown field") || message.contains("invalid field")
                || message.contains("no similar field");
    }

    /** 流不存在（如被删除后未重建）：查询返回空结果而非 502 */
    private static boolean isStreamMissingError(StorageException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return message.contains("stream not found") || message.contains("no such stream")
                || message.contains("stream does not exist");
    }

    /** 空响应（hits=[]），用于"条件不可能命中"时的统一降级 */
    private JsonNode emptyResponse() {
        ObjectNode response = mapper.createObjectNode();
        response.set("hits", mapper.createArrayNode());
        response.put("total", 0);
        return response;
    }

    /**
     * 执行一次查询：SQL 由 sqlFn 依据当前 Schema 构建；命中 unknown field 时
     * 刷新 Schema 并重建 SQL 重试一次，重建后条件不可能命中则返回空响应。
     */
    private JsonNode searchWithSchema(long startMicros, long endMicros, int from, int size,
                                      java.util.function.Function<Predicate<String>, String> sqlFn) {
        String sql = sqlFn.apply(fieldPredicate());
        if (sql == null) {
            return emptyResponse();
        }
        try {
            return client.search(startMicros, endMicros, sql, from, size);
        } catch (StorageException e) {
            if (isStreamMissingError(e)) {
                // 流被删除（如清空测试数据）：视为空结果并失效 Schema 缓存，下次写入会自动重建流
                invalidateFields();
                return emptyResponse();
            }
            if (!isUnknownFieldError(e)) {
                throw e;
            }
            invalidateFields();
            String rebuilt = sqlFn.apply(fieldPredicate());
            if (rebuilt == null) {
                return emptyResponse();
            }
            log.debug("retrying openobserve query after schema refresh");
            return client.search(startMicros, endMicros, rebuilt, from, size);
        }
    }

    // ---------- 查询 ----------

    @Override
    public LogSearchResult search(LogQuery query) {
        List<LogRecord> logs = mapHits(searchWithSchema(query.getStartTimeMicros(), query.getEndTimeMicros(),
                query.offset(), query.getSize(),
                fields -> OpenObserveSql.selectAll(query, streamName, query.isAscending(), fields)));
        // 新版 OpenObserve 的 total 字段为当前页命中数，分页总数以独立 count 查询为准
        long total;
        if (logs.size() < query.getSize() && query.offset() == 0) {
            total = logs.size();
        } else {
            total = count(query);
        }
        return new LogSearchResult(total, query.getPage(), query.getSize(), logs);
    }

    @Override
    public List<LogRecord> findByTraceId(String traceId) {
        String sql = OpenObserveSql.traceSql(traceId, streamName);
        // OpenObserve 拒绝 start=0 的无限时间范围：链路查询固定取最近 7 天
        long end = System.currentTimeMillis() * 1000L + 1_000_000L;
        long start = end - 7L * 24 * 3600 * 1_000_000L;
        JsonNode response = searchWithSchema(start, end, 0, traceMaxLogs, fields -> sql);
        List<LogRecord> logs = mapHits(response);
        // trace 查询要求 timestamp ASC
        logs.sort((a, b) -> Long.compare(a.getTimestampMicros(), b.getTimestampMicros()));
        return logs;
    }

    private List<LogRecord> mapHits(JsonNode response) {
        List<LogRecord> records = new ArrayList<>();
        JsonNode hits = response.get("hits");
        if (hits == null || !hits.isArray()) {
            return records;
        }
        for (JsonNode hit : hits) {
            records.add(fromHit(hit));
        }
        return records;
    }

    LogRecord fromHit(JsonNode hit) {
        LogRecord record = new LogRecord();
        record.setTimestampMicros(hit.path("_timestamp").asLong(0));

        JsonNode tsText = hit.get("timestamp");
        if (tsText != null && tsText.isTextual()) {
            try {
                record.setTimestamp(OffsetDateTime.parse(tsText.asText()));
            } catch (Exception e) {
                record.setTimestamp(OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(record.getTimestampMicros() / 1000L), ZoneId.systemDefault()));
            }
        } else {
            record.setTimestamp(OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(record.getTimestampMicros() / 1000L), ZoneId.systemDefault()));
        }

        record.setTraceId(textOrNull(hit, "trace_id"));
        record.setSpanId(textOrNull(hit, "span_id"));
        record.setParentSpanId(textOrNull(hit, "parent_span_id"));
        record.setRequestId(textOrNull(hit, "request_id"));
        record.setService(textOrNull(hit, "service"));
        record.setServiceVersion(textOrNull(hit, "service_version"));
        record.setEnvironment(textOrNull(hit, "environment"));
        record.setLevel(textOrNull(hit, "level"));
        record.setType(textOrNull(hit, "type"));
        record.setEvent(textOrNull(hit, "event"));
        record.setMessage(textOrNull(hit, "message"));
        record.setUserId(textOrNull(hit, "user_id"));
        record.setMethod(textOrNull(hit, "method"));
        record.setPath(textOrNull(hit, "path"));
        record.setHost(textOrNull(hit, "host"));
        record.setInstance(textOrNull(hit, "instance"));

        if (hit.hasNonNull("status_code")) {
            record.setStatusCode(hit.get("status_code").asLong());
        }
        if (hit.hasNonNull("duration_ms")) {
            record.setDurationMs(hit.get("duration_ms").asLong());
        }

        JsonNode attrs = hit.get("attributes");
        if (attrs != null && !attrs.isNull()) {
            if (attrs.isTextual()) {
                record.setAttributes(parseAttributesJson(attrs.asText()));
            } else if (attrs.isObject()) {
                record.setAttributes(mapper.convertValue(attrs, new TypeReference<Map<String, Object>>() {
                }));
            }
        }
        return record;
    }

    private Map<String, Object> parseAttributesJson(String text) {
        try {
            return mapper.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            var single = new LinkedHashMap<String, Object>();
            single.put("value", text);
            return single;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    // ---------- Dashboard 聚合 ----------

    @Override
    public DashboardResult aggregate(DashboardQuery query) {
        DashboardResult result = new DashboardResult();
        if (query.requires(DashboardQuery.Metric.OVERVIEW)) {
            result.setOverview(aggregateOverview(query));
        }
        if (query.requires(DashboardQuery.Metric.LOG_TREND)) {
            result.setLogTrend(aggregateTrend(query));
        }
        if (query.requires(DashboardQuery.Metric.LEVEL_DISTRIBUTION)) {
            result.setLevelDistribution(aggregateLevelDistribution(query));
        }
        if (query.requires(DashboardQuery.Metric.SERVICE_RANKING)) {
            result.setServiceRanking(aggregateServiceRanking(query));
        }
        return result;
    }

    private com.obsroman.tracelog.model.DashboardOverview aggregateOverview(DashboardQuery query) {
        JsonNode row = firstRow(searchWithSchema(query.getStartTimeMicros(), query.getEndTimeMicros(), 0, 1,
                fields -> OpenObserveSql.overviewSql(query, streamName, fields)));
        com.obsroman.tracelog.model.DashboardOverview overview = new com.obsroman.tracelog.model.DashboardOverview();
        long total = row.path("total_logs").asLong(0);
        long errors = row.path("error_logs").asLong(0);
        overview.setTotalLogs(total);
        overview.setErrorLogs(errors);
        overview.setWarnLogs(row.path("warn_logs").asLong(0));
        overview.setTraceCount(row.path("trace_count").asLong(0));
        overview.setActiveServices(row.path("active_services").asLong(0));
        overview.setErrorRate(total > 0 ? (double) errors / total : 0.0);
        if (row.hasNonNull("avg_duration_ms") && row.get("avg_duration_ms").isNumber()) {
            overview.setAvgDurationMs(round1(row.get("avg_duration_ms").asDouble()));
        }
        if (row.hasNonNull("p95_duration_ms") && row.get("p95_duration_ms").isNumber()) {
            overview.setP95DurationMs(round1(row.get("p95_duration_ms").asDouble()));
        }
        if (row.hasNonNull("p99_duration_ms") && row.get("p99_duration_ms").isNumber()) {
            overview.setP99DurationMs(round1(row.get("p99_duration_ms").asDouble()));
        }
        return overview;
    }

    private List<TrendPoint> aggregateTrend(DashboardQuery query) {
        JsonNode response = searchWithSchema(query.getStartTimeMicros(), query.getEndTimeMicros(), 0,
                OpenObserveSql.TARGET_BUCKETS * 4,
                fields -> OpenObserveSql.trendSql(query, streamName, fields));
        JsonNode hits = response.path("hits");

        long intervalSeconds = intervalSeconds(query);
        long startMs = Math.floorDiv(query.getStartTimeMicros() / 1000L, intervalSeconds * 1000L)
                * intervalSeconds * 1000L;
        long endMs = query.getEndTimeMicros() / 1000L;

        Map<Long, Long> buckets = new LinkedHashMap<>();
        for (long t = startMs; t < endMs && buckets.size() < OpenObserveSql.TARGET_BUCKETS * 4;
                t += intervalSeconds * 1000L) {
            buckets.put(t, 0L);
        }
        if (hits.isArray()) {
            for (JsonNode row : hits) {
                long bucketMs = parseBucketMs(row.get("bucket_ts"), intervalSeconds);
                if (bucketMs < startMs) {
                    bucketMs = startMs + Math.floorDiv(bucketMs - startMs, intervalSeconds * 1000L)
                            * intervalSeconds * 1000L;
                }
                buckets.merge(bucketMs, row.path("cnt").asLong(0), Long::sum);
            }
        }

        List<TrendPoint> points = new ArrayList<>(buckets.size());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        for (Map.Entry<Long, Long> entry : buckets.entrySet()) {
            String label = LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getKey()), ZoneId.systemDefault())
                    .format(fmt);
            points.add(new TrendPoint(label, entry.getValue(), entry.getKey()));
        }
        return points;
    }

    private List<LevelDistributionItem> aggregateLevelDistribution(DashboardQuery query) {
        JsonNode response = searchWithSchema(query.getStartTimeMicros(), query.getEndTimeMicros(), 0, 20,
                fields -> OpenObserveSql.levelDistributionSql(query, streamName, fields));
        List<LevelDistributionItem> items = new ArrayList<>();
        JsonNode hits = response.path("hits");
        if (hits.isArray()) {
            for (JsonNode row : hits) {
                items.add(new LevelDistributionItem(row.path("level").asText("UNKNOWN"),
                        row.path("cnt").asLong(0)));
            }
        }
        return items;
    }

    private List<ServiceRankingItem> aggregateServiceRanking(DashboardQuery query) {
        JsonNode response = searchWithSchema(query.getStartTimeMicros(), query.getEndTimeMicros(), 0,
                SERVICE_RANKING_LIMIT,
                fields -> OpenObserveSql.serviceRankingSql(query, streamName, SERVICE_RANKING_LIMIT, fields));
        List<ServiceRankingItem> items = new ArrayList<>();
        JsonNode hits = response.path("hits");
        if (hits.isArray()) {
            for (JsonNode row : hits) {
                long total = row.path("total").asLong(0);
                long errors = row.path("errors").asLong(0);
                String service = row.path("service").asText("unknown");
                items.add(new ServiceRankingItem(service, total, errors,
                        total > 0 ? (double) errors / total : 0.0));
            }
        }
        return items;
    }

    // ---------- count / export ----------

    @Override
    public long count(LogQuery query) {
        JsonNode response = searchWithSchema(query.getStartTimeMicros(), query.getEndTimeMicros(), 0, 1,
                fields -> OpenObserveSql.countSql(query, streamName, fields));
        JsonNode row = firstRow(response);
        return row.path("c").asLong(0);
    }

    @Override
    public LogCursor searchForExport(LogQuery query) {
        query.setAscending(true);
        return new OpenObserveCursor(query);
    }

    private class OpenObserveCursor implements LogCursor {

        private final LogQuery query;
        private final Predicate<String> fields = fieldPredicate();
        private int offset = 0;
        private boolean exhausted = false;

        private OpenObserveCursor(LogQuery query) {
            this.query = query;
        }

        @Override
        public List<LogRecord> nextBatch(int maxRows) {
            if (exhausted || maxRows <= 0) {
                return List.of();
            }
            int size = Math.min(maxRows, exportPageSize);
            String sql = OpenObserveSql.selectAll(query, streamName, true, fields);
            if (sql == null) {
                exhausted = true;
                return List.of();
            }
            JsonNode response = client.search(query.getStartTimeMicros(), query.getEndTimeMicros(),
                    sql, offset, size);
            List<LogRecord> batch = mapHits(response);
            offset += batch.size();
            if (batch.size() < size) {
                exhausted = true;
            }
            return batch;
        }

        @Override
        public void close() {
            exhausted = true;
        }
    }

    // ---------- helpers ----------

    private JsonNode firstRow(JsonNode response) {
        JsonNode hits = response.path("hits");
        if (hits.isArray() && !hits.isEmpty()) {
            return hits.get(0);
        }
        return mapper.createObjectNode();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private long intervalSeconds(DashboardQuery query) {
        String interval = OpenObserveSql.histogramInterval(query);
        String[] parts = interval.split(" ");
        long value = Long.parseLong(parts[0]);
        return switch (parts.length > 1 ? parts[1] : "seconds") {
            case "days" -> value * 86400;
            case "hours" -> value * 3600;
            case "minutes" -> value * 60;
            default -> value;
        };
    }

    private long parseBucketMs(JsonNode bucketTs, long intervalSeconds) {
        if (bucketTs == null || bucketTs.isNull()) {
            return 0;
        }
        if (bucketTs.isNumber()) {
            long value = bucketTs.asLong();
            // OO 可能返回微秒或毫秒
            if (value > 100_000_000_000_000L) {
                return value / 1000L;
            }
            if (value > 100_000_000_000L) {
                return value;
            }
            return value * 1000L;
        }
        String text = bucketTs.asText();
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            // OpenObserve histogram 返回 UTC 裸时间字符串（如 2026-09-01T07:02:00），必须按 UTC 解析
            return LocalDateTime.parse(text).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 计算桶对齐后的起始毫秒（供测试） */
    static long floorToInterval(long epochMs, long intervalSeconds) {
        long intervalMs = intervalSeconds * 1000L;
        return Math.floorDiv(epochMs, intervalMs) * intervalMs;
    }
}

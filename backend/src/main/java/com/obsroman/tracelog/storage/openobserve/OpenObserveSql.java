package com.obsroman.tracelog.storage.openobserve;

import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.LogQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * OpenObserve SQL 片段构建器（项目内唯一拼 SQL 的地方）。
 * 所有字符串值必须经 {@link #quote} 转义；枚举值以白名单校验为前提，仍做二次转义兜底。
 * 前端/Controller 禁止传入任何 SQL 片段。
 */
public final class OpenObserveSql {

    /** 趋势图目标桶数 */
    static final int TARGET_BUCKETS = 60;

    /** 桶宽吸附序列（秒） */
    private static final long[] BUCKET_STEPS = {
            1, 5, 10, 15, 30, 60, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200, 86400
    };

    private OpenObserveSql() {
    }

    /** 转义字符串字面量（单引号成对），并剔除控制字符 */
    public static String quote(String value) {
        String cleaned = value.replaceAll("[\\x00-\\x1f\\x7f]", " ").trim();
        return "'" + cleaned.replace("'", "''") + "'";
    }

    /** 流名引用：\"trace_logs\" */
    public static String streamRef(String streamName) {
        return "\"" + streamName.replace("\"", "") + "\"";
    }

    /** 关键词清洗：剔除引号/反斜杠/控制字符等 SQL 危险字符，空白折叠为单空格；空返回 null */
    public static String sanitizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String cleaned = keyword.replaceAll("['\"\\\\]", " ")
                .replaceAll("[^\\x20-\\x7e\\u4e00-\\u9fff]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** LogQuery → WHERE 子句（不含 where 关键字本身）；恒包含时间范围。known 为 null 时假定全部字段存在。 */
    public static String whereClause(LogQuery query) {
        return whereClause(query, null);
    }

    /**
     * 字段感知版本。OpenObserve 会按流 Schema 校验字段，引用不存在的列会报错；
     * 返回 null 表示条件不可能命中（如过滤字段在流中不存在），调用方应返回空结果。
     */
    public static String whereClause(LogQuery query, java.util.function.Predicate<String> known) {
        if (known != null) {
            List<String> filterFields = new ArrayList<>();
            if (!query.getServices().isEmpty()) {
                filterFields.add("service");
            }
            if (!query.getEnvironments().isEmpty()) {
                filterFields.add("environment");
            }
            if (!query.getLevels().isEmpty()) {
                filterFields.add("level");
            }
            if (!query.getTypes().isEmpty()) {
                filterFields.add("type");
            }
            if (notBlank(query.getTraceId())) {
                filterFields.add("trace_id");
            }
            if (notBlank(query.getRequestId())) {
                filterFields.add("request_id");
            }
            if (notBlank(query.getUserId())) {
                filterFields.add("user_id");
            }
            for (String field : filterFields) {
                if (!known.test(field)) {
                    return null;
                }
            }
            if (sanitizeKeyword(query.getKeyword()) != null
                    && java.util.stream.Stream.of("message", "event", "attributes").noneMatch(known)) {
                return null;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("_timestamp >= ").append(query.getStartTimeMicros())
                .append(" AND _timestamp < ").append(query.getEndTimeMicros());

        appendIn(sb, "service", query.getServices(), known);
        appendIn(sb, "environment", query.getEnvironments(), known);
        appendIn(sb, "level", query.getLevels(), known);
        appendIn(sb, "type", query.getTypes(), known);

        appendEquals(sb, "trace_id", query.getTraceId(), known);
        appendEquals(sb, "request_id", query.getRequestId(), known);
        appendEquals(sb, "user_id", query.getUserId(), known);

        String keyword = sanitizeKeyword(query.getKeyword());
        if (keyword != null) {
            // OpenObserve 的 match_all 仅覆盖默认全文索引字段，attributes 等自定义列不在其中，
            // 因此关键词搜索使用 str_match（大小写不敏感），并只引用流中实际存在的字段。
            String kw = quote(keyword);
            String separator = " AND (";
            for (String field : List.of("message", "event", "attributes")) {
                if (known != null && !known.test(field)) {
                    continue;
                }
                sb.append(separator).append("str_match(").append(field).append(", ").append(kw).append(")");
                separator = " OR ";
            }
            sb.append(")");
        }
        return sb.toString();
    }

    /** DashboardQuery → WHERE 子句（时间范围 + 可选 environment/service 等值过滤） */
    public static String whereClause(DashboardQuery query) {
        return whereClause(query, null);
    }

    public static String whereClause(DashboardQuery query, java.util.function.Predicate<String> known) {
        if (known != null && query.getService() != null && !query.getService().isBlank()
                && !known.test("service")) {
            return null;
        }
        if (known != null && query.getEnvironment() != null && !query.getEnvironment().isBlank()
                && !known.test("environment")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("_timestamp >= ").append(query.getStartTimeMicros())
                .append(" AND _timestamp < ").append(query.getEndTimeMicros());
        if (query.getEnvironment() != null && !query.getEnvironment().isBlank()) {
            sb.append(" AND environment = ").append(quote(query.getEnvironment().trim().toLowerCase(Locale.ROOT)));
        }
        if (query.getService() != null && !query.getService().isBlank()) {
            sb.append(" AND service = ").append(quote(query.getService().trim().toLowerCase(Locale.ROOT)));
        }
        return sb.toString();
    }

    public static String selectAll(LogQuery query, String streamName, boolean ascending) {
        return selectAll(query, streamName, ascending, null);
    }

    public static String selectAll(LogQuery query, String streamName, boolean ascending,
                                   java.util.function.Predicate<String> known) {
        String where = whereClause(query, known);
        if (where == null) {
            return null;
        }
        return "SELECT * FROM " + streamRef(streamName)
                + " WHERE " + where
                + " ORDER BY _timestamp " + (ascending ? "ASC" : "DESC");
    }

    public static String countSql(LogQuery query, String streamName) {
        return countSql(query, streamName, null);
    }

    public static String countSql(LogQuery query, String streamName, java.util.function.Predicate<String> known) {
        String where = whereClause(query, known);
        if (where == null) {
            return null;
        }
        return "SELECT count(*) AS c FROM " + streamRef(streamName) + " WHERE " + where;
    }

    public static String overviewSql(DashboardQuery query, String streamName) {
        return overviewSql(query, streamName, null);
    }

    public static String overviewSql(DashboardQuery query, String streamName,
                                     java.util.function.Predicate<String> known) {
        boolean hasDuration = known == null || known.test("duration_ms");
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT count(*) AS total_logs, ")
                .append("SUM(CASE WHEN level IN ('ERROR', 'FATAL') THEN 1 ELSE 0 END) AS error_logs, ")
                .append("SUM(CASE WHEN level = 'WARN' THEN 1 ELSE 0 END) AS warn_logs, ")
                .append("count(DISTINCT trace_id) AS trace_count, ")
                .append("count(DISTINCT service) AS active_services");
        if (hasDuration) {
            sb.append(", avg(duration_ms) AS avg_duration_ms")
                    .append(", approx_percentile_cont(duration_ms, 0.95) AS p95_duration_ms")
                    .append(", approx_percentile_cont(duration_ms, 0.99) AS p99_duration_ms");
        }
        sb.append(" FROM ").append(streamRef(streamName)).append(" WHERE ").append(whereClause(query, known));
        return sb.toString();
    }

    public static String trendSql(DashboardQuery query, String streamName) {
        return trendSql(query, streamName, null);
    }

    public static String trendSql(DashboardQuery query, String streamName, java.util.function.Predicate<String> known) {
        String where = whereClause(query, known);
        if (where == null) {
            return null;
        }
        return "SELECT histogram(_timestamp, '" + histogramInterval(query) + "') AS bucket_ts, "
                + "count(*) AS cnt FROM " + streamRef(streamName)
                + " WHERE " + where
                + " GROUP BY bucket_ts ORDER BY bucket_ts ASC";
    }

    public static String levelDistributionSql(DashboardQuery query, String streamName) {
        return levelDistributionSql(query, streamName, null);
    }

    public static String levelDistributionSql(DashboardQuery query, String streamName,
                                              java.util.function.Predicate<String> known) {
        String where = whereClause(query, known);
        if (where == null) {
            return null;
        }
        return "SELECT level, count(*) AS cnt FROM " + streamRef(streamName)
                + " WHERE " + where
                + " GROUP BY level ORDER BY cnt DESC";
    }

    public static String serviceRankingSql(DashboardQuery query, String streamName, int limit) {
        return serviceRankingSql(query, streamName, limit, null);
    }

    public static String serviceRankingSql(DashboardQuery query, String streamName, int limit,
                                           java.util.function.Predicate<String> known) {
        String where = whereClause(query, known);
        if (where == null) {
            return null;
        }
        return "SELECT service, count(*) AS total, "
                + "SUM(CASE WHEN level IN ('ERROR', 'FATAL') THEN 1 ELSE 0 END) AS errors "
                + "FROM " + streamRef(streamName)
                + " WHERE " + where
                + " GROUP BY service ORDER BY total DESC LIMIT " + limit;
    }

    public static String traceSql(String traceId, String streamName) {
        return "SELECT * FROM " + streamRef(streamName)
                + " WHERE trace_id = " + quote(traceId)
                + " ORDER BY _timestamp ASC";
    }

    /** 根据时间范围选择合适的 histogram 桶宽，返回如 \"5 minutes\" */
    public static String histogramInterval(DashboardQuery query) {
        long seconds = Math.max(1, (query.getEndTimeMicros() - query.getStartTimeMicros()) / 1_000_000L);
        long target = seconds / TARGET_BUCKETS;
        for (long step : BUCKET_STEPS) {
            if (step >= target) {
                return humanizeSeconds(step);
            }
        }
        return "1 days";
    }

    private static String humanizeSeconds(long seconds) {
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + " days";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + " hours";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + " minutes";
        }
        return seconds + " seconds";
    }

    private static void appendIn(StringBuilder sb, String field, List<String> values,
                                 java.util.function.Predicate<String> known) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String quoted = values.stream().map(OpenObserveSql::quote).collect(Collectors.joining(", "));
        sb.append(" AND ").append(field).append(" IN (").append(quoted).append(")");
    }

    private static void appendEquals(StringBuilder sb, String field, String value,
                                     java.util.function.Predicate<String> known) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(" AND ").append(field).append(" = ").append(quote(value.trim()));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

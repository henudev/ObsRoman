package com.obsroman.tracelog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.ExportRequest;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.storage.LogCursor;
import com.obsroman.tracelog.storage.LogStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 日志导出服务（P0：同步流式 CSV / JSONL）。
 * - 查询条件复用 LogQuery（与搜索完全一致）
 * - 流式处理：查询一批 → 写一批，禁止一次性加载全部数据进内存
 * - 限制：仅保留最大 24 小时时间范围（数量不设上限）
 */
@Service
public class LogExportService {

    private static final Logger log = LoggerFactory.getLogger(LogExportService.class);

    /** CSV 固定导出列（§18），attributes 转为 JSON 字符串 */
    private static final String[] CSV_COLUMNS = {
            "timestamp", "trace_id", "span_id", "request_id", "service", "environment",
            "level", "type", "event", "message", "user_id", "method", "path",
            "status_code", "duration_ms", "host", "instance", "attributes"
    };

    private static final String CSV_BOM = "﻿";

    private final LogStorage storage;
    private final LogQueryFactory queryFactory;
    private final ObjectMapper mapper;
    private final TraceLogProperties.Limits limits;

    public LogExportService(LogStorage storage, LogQueryFactory queryFactory, ObjectMapper mapper,
                            TraceLogProperties properties) {
        this.storage = storage;
        this.queryFactory = queryFactory;
        this.mapper = mapper;
        this.limits = properties.getLimits();
    }

    /** 导出前的校验与限额预检查 */
    public ExportPlan prepare(ExportRequest request) {
        String format = request.getFormat() == null || request.getFormat().isBlank()
                ? ExportRequest.FORMAT_CSV
                : request.getFormat().trim().toLowerCase(java.util.Locale.ROOT);
        if (!ExportRequest.FORMAT_CSV.equals(format) && !ExportRequest.FORMAT_JSONL.equals(format)) {
            throw new ApiException(ErrorCode.EXPORT_FORMAT_UNSUPPORTED,
                    "format must be csv or jsonl, got '" + format + "'");
        }

        LogSearchRequest search = toSearchRequest(request);
        LogQuery query = queryFactory.create(search, true);

        // 数量不设上限（可由运维通过配置调整），仅用于回显 X-Export-Rows 导出行数
        long count = storage.count(query);
        return new ExportPlan(query, format, count);
    }

    /** 流式写出：循环 查一批→写一批，直到读完或达到上限 */
    public void writeTo(ExportPlan plan, OutputStream out) {
        try {
            boolean csv = ExportRequest.FORMAT_CSV.equals(plan.format());
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                if (csv) {
                    writer.write(CSV_BOM);
                    writer.write(String.join(",", CSV_COLUMNS));
                    writer.write('\n');
                }
                long written = 0;
                try (LogCursor cursor = storage.searchForExport(plan.query())) {
                    while (true) {
                        List<LogRecord> batch = cursor.nextBatch(limits.getExportPageSize());
                        if (batch.isEmpty()) {
                            break;
                        }
                        for (LogRecord record : batch) {
                            writer.write(csv ? toCsvLine(record) : toJsonLine(record));
                            writer.write('\n');
                        }
                        written += batch.size();
                    }
                }
                writer.flush();
                log.info("export finished: format={}, rows={}", plan.format(), written);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("export streaming failed", e);
        }
    }

    private LogSearchRequest toSearchRequest(ExportRequest request) {
        LogSearchRequest search = new LogSearchRequest();
        search.setStartTime(request.getStartTime());
        search.setEndTime(request.getEndTime());
        search.setService(request.getService());
        search.setEnvironment(request.getEnvironment());
        search.setLevel(request.getLevel());
        search.setType(request.getType());
        search.setTraceId(request.getTraceId());
        search.setRequestId(request.getRequestId());
        search.setUserId(request.getUserId());
        search.setKeyword(request.getKeyword());
        return search;
    }

    private String toJsonLine(LogRecord record) {
        try {
            return mapper.writeValueAsString(record);
        } catch (IOException e) {
            throw new UncheckedIOException("jsonl serialization failed", e);
        }
    }

    private String toCsvLine(LogRecord record) {
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < CSV_COLUMNS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvEscape(csvValue(record, CSV_COLUMNS[i])));
        }
        return sb.toString();
    }

    private String csvValue(LogRecord record, String column) {
        return switch (column) {
            case "timestamp" -> record.getTimestamp() == null ? "" : record.getTimestamp().toString();
            case "trace_id" -> nullSafe(record.getTraceId());
            case "span_id" -> nullSafe(record.getSpanId());
            case "request_id" -> nullSafe(record.getRequestId());
            case "service" -> nullSafe(record.getService());
            case "environment" -> nullSafe(record.getEnvironment());
            case "level" -> nullSafe(record.getLevel());
            case "type" -> nullSafe(record.getType());
            case "event" -> nullSafe(record.getEvent());
            case "message" -> nullSafe(record.getMessage());
            case "user_id" -> nullSafe(record.getUserId());
            case "method" -> nullSafe(record.getMethod());
            case "path" -> nullSafe(record.getPath());
            case "status_code" -> record.getStatusCode() == null ? "" : String.valueOf(record.getStatusCode());
            case "duration_ms" -> record.getDurationMs() == null ? "" : String.valueOf(record.getDurationMs());
            case "host" -> nullSafe(record.getHost());
            case "instance" -> nullSafe(record.getInstance());
            case "attributes" -> record.getAttributes() == null || record.getAttributes().isEmpty()
                    ? "" : toJsonString(record.getAttributes());
            default -> "";
        };
    }

    private String toJsonString(Map<String, Object> attributes) {
        try {
            return mapper.writeValueAsString(attributes);
        } catch (IOException e) {
            return String.valueOf(attributes);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** RFC 4180 转义：包含逗号/引号/换行时整体加引号，内部引号成对 */
    static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    public record ExportPlan(LogQuery query, String format, long totalRows) {
    }
}

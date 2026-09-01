package com.obsroman.tracelog.service;

import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.TraceResult;
import com.obsroman.tracelog.storage.LogStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * trace_id 链路查询服务：聚合起止时间、耗时、状态与涉及服务。
 */
@Service
public class TraceQueryService {

    private final LogStorage storage;
    private final int traceMaxLogs;

    public TraceQueryService(LogStorage storage, TraceLogProperties properties) {
        this.storage = storage;
        this.traceMaxLogs = properties.getLimits().getTraceMaxLogs();
    }

    public TraceResult findByTraceId(String traceId) {
        String normalized = normalizeTraceId(traceId);

        List<LogRecord> logs;
        try {
            logs = storage.findByTraceId(normalized);
        } catch (ApiException e) {
            throw e;
        }
        if (logs.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "trace not found: " + normalized);
        }

        TraceResult result = new TraceResult();
        result.setTraceId(normalized);
        result.setLogs(logs);

        long startMicros = logs.get(0).getTimestampMicros();
        long endMicros = startMicros;
        boolean hasError = false;
        Set<String> services = new LinkedHashSet<>();
        for (LogRecord record : logs) {
            startMicros = Math.min(startMicros, record.getTimestampMicros());
            endMicros = Math.max(endMicros, record.getTimestampMicros());
            if ("ERROR".equals(record.getLevel()) || "FATAL".equals(record.getLevel())) {
                hasError = true;
            }
            if (record.getService() != null) {
                services.add(record.getService());
            }
        }

        result.setStartTime(logs.get(0).getTimestamp());
        result.setEndTime(logs.get(logs.size() - 1).getTimestamp());
        result.setDurationMs(Math.max(0, (endMicros - startMicros) / 1000L));
        result.setStatus(hasError ? TraceResult.Status.ERROR : TraceResult.Status.SUCCESS);
        result.setServices(new ArrayList<>(services));
        if (logs.size() >= traceMaxLogs) {
            result.setTruncated(true);
        }
        return result;
    }

    private String normalizeTraceId(String traceId) {
        if (traceId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "trace_id is required");
        }
        String normalized = traceId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{32}$")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "trace_id must be a 32-char hex string");
        }
        return normalized;
    }
}

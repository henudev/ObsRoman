package com.obsroman.tracelog.service;

import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecordParser;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.model.LogValidationException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * LogSearchRequest → LogQuery 的统一转换与限制校验。
 * Search / Export 共用，保证导出条件与搜索条件完全一致。
 */
@Component
public class LogQueryFactory {

    private final TraceLogProperties.Limits limits;

    public LogQueryFactory(TraceLogProperties properties) {
        this.limits = properties.getLimits();
    }

    public LogQuery create(LogSearchRequest request, boolean forExport) {
        OffsetDateTime end = parseTime(request.getEndTime(), "end_time");
        OffsetDateTime start = parseTime(request.getStartTime(), "start_time");

        if (start == null && end == null) {
            end = OffsetDateTime.now();
            start = end.minusMinutes(limits.getSearchDefaultRangeMinutes());
        } else if (start == null) {
            start = end.minusMinutes(limits.getSearchDefaultRangeMinutes());
        } else if (end == null) {
            end = OffsetDateTime.now();
        }
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "start_time must be before end_time");
        }
        long rangeMs = end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli();
        long maxRangeMs = forExport
                ? limits.getExportMaxRangeHours() * 3600_000L
                : limits.getSearchMaxRangeDays() * 24L * 3600_000L;
        if (rangeMs > maxRangeMs) {
            ErrorCode rangeError = forExport ? ErrorCode.EXPORT_LIMIT_EXCEEDED : ErrorCode.SEARCH_RANGE_EXCEEDED;
            throw new ApiException(rangeError,
                    (forExport ? "export" : "search") + " range " + (rangeMs / 3600_000L)
                            + "h exceeds limit " + (maxRangeMs / 3600_000L) + " hours");
        }

        LogQuery query = new LogQuery();
        query.setStartTimeMicros(LogRecordParser.toMicros(start));
        query.setEndTimeMicros(LogRecordParser.toMicros(end));
        query.setServices(cleanList(request.getService()));
        query.setEnvironments(validateEnums(request.getEnvironment(), "environment", LogRecordParser.ENVIRONMENTS));
        query.setLevels(validateEnums(request.getLevel(), "level", LogRecordParser.LEVELS));
        query.setTypes(cleanList(request.getType()));
        query.setTraceId(cleanSingle(request.getTraceId()));
        query.setRequestId(cleanSingle(request.getRequestId(), "request_id"));
        query.setUserId(cleanSingle(request.getUserId(), "user_id"));
        query.setKeyword(request.getKeyword());

        if (forExport) {
            query.setPage(1);
            query.setSize(limits.getExportPageSize());
            query.setAscending(true);
        } else {
            int page = request.getPage() == null ? 1 : request.getPage();
            if (page < 1) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "page must be >= 1");
            }
            query.setPage(page);
            int size = request.getSize() == null ? limits.getSearchDefaultSize() : request.getSize();
            if (size < 1) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "size must be >= 1");
            }
            if (size > limits.getSearchMaxSize()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "size must be <= " + limits.getSearchMaxSize());
            }
            query.setSize(size);
            query.setAscending(false);
        }
        return query;
    }

    private OffsetDateTime parseTime(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LogRecordParser.parseFlexibleTimestamp(text);
        } catch (LogValidationException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, field + " is not a valid ISO-8601 datetime");
        }
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
    }

    private List<String> validateEnums(List<String> values, String field, java.util.Set<String> allowed) {
        List<String> cleaned = cleanList(values);
        for (String value : cleaned) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (field.equals("level")) {
                normalized = normalized.toUpperCase(Locale.ROOT);
            }
            if (!allowed.contains(normalized)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        field + " value '" + value + "' is not allowed, must be one of " + allowed);
            }
        }
        return cleaned.stream().map(v -> field.equals("level")
                ? v.toUpperCase(Locale.ROOT)
                : v.toLowerCase(Locale.ROOT)).toList();
    }

    private String cleanSingle(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, field + " exceeds max length 128");
        }
        return trimmed;
    }

    private String cleanSingle(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}

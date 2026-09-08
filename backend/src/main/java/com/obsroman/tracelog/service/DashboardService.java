package com.obsroman.tracelog.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.DashboardOverview;
import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.DashboardResult;
import com.obsroman.tracelog.model.LevelDistributionItem;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecordParser;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.model.LogSearchResult;
import com.obsroman.tracelog.model.ServiceRankingItem;
import com.obsroman.tracelog.model.TrendPoint;
import com.obsroman.tracelog.storage.LogStorage;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dashboard 聚合服务：默认最近 1 小时，最大 24 小时。
 * 所有统计经 LogStorage.aggregate 读取，不直接访问 OpenObserve。
 */
@Service
public class DashboardService {

    private final LogStorage storage;
    private final LogQueryFactory queryFactory;
    private final TraceLogProperties.Limits limits;

    public DashboardService(LogStorage storage, LogQueryFactory queryFactory, TraceLogProperties properties) {
        this.storage = storage;
        this.queryFactory = queryFactory;
        this.limits = properties.getLimits();
    }

    public DashboardOverview overview(DashboardRequest request) {
        return aggregate(request, DashboardQuery.Metric.OVERVIEW).getOverview();
    }

    public java.util.List<TrendPoint> logTrend(DashboardRequest request) {
        DashboardResult result = aggregate(request, DashboardQuery.Metric.LOG_TREND);
        return result.getLogTrend() == null ? java.util.List.of() : result.getLogTrend();
    }

    public java.util.List<LevelDistributionItem> levelDistribution(DashboardRequest request) {
        DashboardResult result = aggregate(request, DashboardQuery.Metric.LEVEL_DISTRIBUTION);
        return result.getLevelDistribution() == null ? java.util.List.of() : result.getLevelDistribution();
    }

    public java.util.List<ServiceRankingItem> serviceRanking(DashboardRequest request) {
        DashboardResult result = aggregate(request, DashboardQuery.Metric.SERVICE_RANKING);
        return result.getServiceRanking() == null ? java.util.List.of() : result.getServiceRanking();
    }

    /** 最近 ERROR 日志：复用日志搜索（LogQuery），供 Dashboard 页面"最近 ERROR 日志"区块 */
    public LogSearchResult recentErrors(RecentErrorsRequest request) {
        LogSearchRequest search = new LogSearchRequest();
        search.setStartTime(request.startTime);
        search.setEndTime(request.endTime);
        search.setEnvironment(request.environment == null ? null : java.util.List.of(request.environment));
        search.setService(request.service == null ? null : java.util.List.of(request.service));
        search.setApiKeyAk(request.apiKeyAk);
        search.setLevel(java.util.List.of("ERROR", "FATAL"));
        search.setSize(request.size == null ? 10 : request.size);
        search.setPage(1);
        LogQuery query = queryFactory.create(search, false);
        query.setAscending(false);
        return storage.search(query);
    }

    private DashboardResult aggregate(DashboardRequest request, DashboardQuery.Metric metric) {
        DashboardQuery query = toQuery(request, metric);
        return storage.aggregate(query);
    }

    private DashboardQuery toQuery(DashboardRequest request, DashboardQuery.Metric metric) {
        OffsetDateTime end = parseTime(request.endTime, "end_time");
        OffsetDateTime start = parseTime(request.startTime, "start_time");
        if (start == null && end == null) {
            end = OffsetDateTime.now();
            start = end.minusMinutes(limits.getDashboardDefaultRangeMinutes());
        } else if (start == null) {
            start = end.minusMinutes(limits.getDashboardDefaultRangeMinutes());
        } else if (end == null) {
            end = OffsetDateTime.now();
        }
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "start_time must be before end_time");
        }

        DashboardQuery query = new DashboardQuery();
        query.setStartTime(start);
        query.setEndTime(end);
        query.setStartTimeMicros(LogRecordParser.toMicros(start));
        query.setEndTimeMicros(LogRecordParser.toMicros(end));
        if (request.environment != null && !request.environment.isBlank()) {
            String env = request.environment.trim().toLowerCase(Locale.ROOT);
            if (!LogRecordParser.ENVIRONMENTS.contains(env)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "environment must be one of " + LogRecordParser.ENVIRONMENTS);
            }
            query.setEnvironment(env);
        }
        if (request.service != null && !request.service.isBlank()) {
            query.setService(request.service.trim().toLowerCase(Locale.ROOT));
        }
        if (request.apiKeyAk != null && !request.apiKeyAk.isBlank()) {
            query.setApiKeyAk(request.apiKeyAk.trim().toLowerCase(Locale.ROOT));
        }
        query.setMetrics(EnumSet.of(metric));
        return query;
    }

    private OffsetDateTime parseTime(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LogRecordParser.parseFlexibleTimestamp(text);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, field + " is not a valid ISO-8601 datetime");
        }
    }

    /** Dashboard 通用筛选条件（时间范围 + environment + service + api_key_ak，空表示不限） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardRequest {
        @JsonProperty("start_time")
        public String startTime;

        @JsonProperty("end_time")
        public String endTime;

        @JsonProperty("environment")
        public String environment;

        @JsonProperty("service")
        public String service;

        @JsonProperty("api_key_ak")
        public String apiKeyAk;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecentErrorsRequest {
        @JsonProperty("start_time")
        public String startTime;

        @JsonProperty("end_time")
        public String endTime;

        @JsonProperty("environment")
        public String environment;

        @JsonProperty("service")
        public String service;

        @JsonProperty("api_key_ak")
        public String apiKeyAk;

        @JsonProperty("size")
        public Integer size;
    }
}

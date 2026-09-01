package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dashboard Overview 聚合结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardOverview {

    @JsonProperty("total_logs")
    private long totalLogs;

    @JsonProperty("error_logs")
    private long errorLogs;

    @JsonProperty("warn_logs")
    private long warnLogs;

    @JsonProperty("error_rate")
    private double errorRate;

    @JsonProperty("trace_count")
    private long traceCount;

    @JsonProperty("active_services")
    private long activeServices;

    @JsonProperty("avg_duration_ms")
    private Double avgDurationMs;

    @JsonProperty("p95_duration_ms")
    private Double p95DurationMs;

    @JsonProperty("p99_duration_ms")
    private Double p99DurationMs;

    public long getTotalLogs() {
        return totalLogs;
    }

    public void setTotalLogs(long totalLogs) {
        this.totalLogs = totalLogs;
    }

    public long getErrorLogs() {
        return errorLogs;
    }

    public void setErrorLogs(long errorLogs) {
        this.errorLogs = errorLogs;
    }

    public long getWarnLogs() {
        return warnLogs;
    }

    public void setWarnLogs(long warnLogs) {
        this.warnLogs = warnLogs;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = errorRate;
    }

    public long getTraceCount() {
        return traceCount;
    }

    public void setTraceCount(long traceCount) {
        this.traceCount = traceCount;
    }

    public long getActiveServices() {
        return activeServices;
    }

    public void setActiveServices(long activeServices) {
        this.activeServices = activeServices;
    }

    public Double getAvgDurationMs() {
        return avgDurationMs;
    }

    public void setAvgDurationMs(Double avgDurationMs) {
        this.avgDurationMs = avgDurationMs;
    }

    public Double getP95DurationMs() {
        return p95DurationMs;
    }

    public void setP95DurationMs(Double p95DurationMs) {
        this.p95DurationMs = p95DurationMs;
    }

    public Double getP99DurationMs() {
        return p99DurationMs;
    }

    public void setP99DurationMs(Double p99DurationMs) {
        this.p99DurationMs = p99DurationMs;
    }
}

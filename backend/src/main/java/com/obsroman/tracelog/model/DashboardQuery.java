package com.obsroman.tracelog.model;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Dashboard 聚合查询条件。service/environment 允许为空表示全部。
 * 时间已规范化为 epoch 微秒。
 */
public class DashboardQuery {

    public enum Metric {
        OVERVIEW, LOG_TREND, LEVEL_DISTRIBUTION, SERVICE_RANKING
    }

    private long startTimeMicros;
    private long endTimeMicros;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;

    private String environment;
    private String service;

    private Set<Metric> metrics;

    public long getStartTimeMicros() {
        return startTimeMicros;
    }

    public void setStartTimeMicros(long startTimeMicros) {
        this.startTimeMicros = startTimeMicros;
    }

    public long getEndTimeMicros() {
        return endTimeMicros;
    }

    public void setEndTimeMicros(long endTimeMicros) {
        this.endTimeMicros = endTimeMicros;
    }

    public OffsetDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(OffsetDateTime startTime) {
        this.startTime = startTime;
    }

    public OffsetDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(OffsetDateTime endTime) {
        this.endTime = endTime;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public Set<Metric> getMetrics() {
        return metrics;
    }

    public void setMetrics(Set<Metric> metrics) {
        this.metrics = metrics;
    }

    public boolean requires(Metric metric) {
        return metrics == null || metrics.contains(metric);
    }
}

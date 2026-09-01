package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * trace_id 完整链路查询结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceResult {

    public enum Status {
        SUCCESS, ERROR, UNKNOWN
    }

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("start_time")
    private OffsetDateTime startTime;

    @JsonProperty("end_time")
    private OffsetDateTime endTime;

    @JsonProperty("duration_ms")
    private Long durationMs;

    @JsonProperty("status")
    private Status status;

    @JsonProperty("services")
    private List<String> services;

    @JsonProperty("logs")
    private List<LogRecord> logs;

    /** 日志数超过 trace-max-logs 时为 true，表示链路日志被截断 */
    @JsonProperty("truncated")
    private Boolean truncated;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public List<String> getServices() {
        return services;
    }

    public void setServices(List<String> services) {
        this.services = services;
    }

    public List<LogRecord> getLogs() {
        return logs;
    }

    public void setLogs(List<LogRecord> logs) {
        this.logs = logs;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }
}

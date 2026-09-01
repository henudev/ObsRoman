package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 日志导出请求：查询条件与 LogSearchRequest 完全一致（复用 LogQuery），
 * 仅多一个 format 字段：csv | jsonl。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExportRequest {

    public static final String FORMAT_CSV = "csv";
    public static final String FORMAT_JSONL = "jsonl";

    @JsonProperty("start_time")
    private String startTime;

    @JsonProperty("end_time")
    private String endTime;

    @JsonProperty("service")
    private java.util.List<String> service;

    @JsonProperty("environment")
    private java.util.List<String> environment;

    @JsonProperty("level")
    private java.util.List<String> level;

    @JsonProperty("type")
    private java.util.List<String> type;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("keyword")
    private String keyword;

    @JsonProperty("format")
    private String format;

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public java.util.List<String> getService() {
        return service;
    }

    public void setService(java.util.List<String> service) {
        this.service = service;
    }

    public java.util.List<String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(java.util.List<String> environment) {
        this.environment = environment;
    }

    public java.util.List<String> getLevel() {
        return level;
    }

    public void setLevel(java.util.List<String> level) {
        this.level = level;
    }

    public java.util.List<String> getType() {
        return type;
    }

    public void setType(java.util.List<String> type) {
        this.type = type;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }
}

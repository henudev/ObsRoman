package com.obsroman.tracelog.model;

import java.util.List;

/**
 * 已规范化的日志查询条件（时间统一为 epoch 微秒）。
 * Search / Export / Count 共用同一模型，导出禁止重新定义查询条件。
 */
public class LogQuery {

    private long startTimeMicros;
    private long endTimeMicros;

    private List<String> services = List.of();
    private List<String> environments = List.of();
    private List<String> levels = List.of();
    private List<String> types = List.of();

    private String traceId;
    private String requestId;
    private String userId;
    private String apiKeyAk;
    private String keyword;

    private int page = 1;
    private int size = 50;
    /** true = timestamp ASC（导出），false = timestamp DESC（搜索默认最新在前） */
    private boolean ascending = false;

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

    public List<String> getServices() {
        return services;
    }

    public void setServices(List<String> services) {
        this.services = services == null ? List.of() : List.copyOf(services);
    }

    public List<String> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<String> environments) {
        this.environments = environments == null ? List.of() : List.copyOf(environments);
    }

    public List<String> getLevels() {
        return levels;
    }

    public void setLevels(List<String> levels) {
        this.levels = levels == null ? List.of() : List.copyOf(levels);
    }

    public List<String> getTypes() {
        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types == null ? List.of() : List.copyOf(types);
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

    public String getApiKeyAk() {
        return apiKeyAk;
    }

    public void setApiKeyAk(String apiKeyAk) {
        this.apiKeyAk = apiKeyAk;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public boolean isAscending() {
        return ascending;
    }

    public void setAscending(boolean ascending) {
        this.ascending = ascending;
    }

    public int offset() {
        return (page - 1) * size;
    }
}

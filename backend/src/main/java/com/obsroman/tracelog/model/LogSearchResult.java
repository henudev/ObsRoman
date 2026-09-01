package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogSearchResult {

    private long total;
    private int page;
    private int size;
    private List<LogRecord> logs;

    public LogSearchResult() {
    }

    public LogSearchResult(long total, int page, int size, List<LogRecord> logs) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.logs = logs;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
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

    public List<LogRecord> getLogs() {
        return logs;
    }

    public void setLogs(List<LogRecord> logs) {
        this.logs = logs;
    }
}

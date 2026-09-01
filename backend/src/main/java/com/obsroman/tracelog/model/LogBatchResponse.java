package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 批量写入响应：支持部分成功，rejected>0 时附 per-entry 错误明细。
 * dropped>0 表示队列满被丢弃的条数（仍计入 accepted，202 语义为"已进入服务"）。
 * accepted/rejected 恒输出（即使为 0），便于调用方判断。
 */
public class LogBatchResponse {

    @JsonProperty("accepted")
    private int accepted;

    @JsonProperty("rejected")
    private int rejected;

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonProperty("dropped")
    private long dropped;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("errors")
    private List<LogEntryError> errors;

    public LogBatchResponse() {
    }

    public LogBatchResponse(int accepted, int rejected, long dropped, List<LogEntryError> errors) {
        this.accepted = accepted;
        this.rejected = rejected;
        this.dropped = dropped;
        this.errors = errors;
    }

    public int getAccepted() {
        return accepted;
    }

    public void setAccepted(int accepted) {
        this.accepted = accepted;
    }

    public int getRejected() {
        return rejected;
    }

    public void setRejected(int rejected) {
        this.rejected = rejected;
    }

    public long getDropped() {
        return dropped;
    }

    public void setDropped(long dropped) {
        this.dropped = dropped;
    }

    public List<LogEntryError> getErrors() {
        return errors;
    }

    public void setErrors(List<LogEntryError> errors) {
        this.errors = errors;
    }
}

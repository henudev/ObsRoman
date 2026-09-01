package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 批量写入中单条日志的失败原因。
 */
public record LogEntryError(
        @JsonProperty("index") int index,
        @JsonProperty("code") int code,
        @JsonProperty("message") String message) {
}

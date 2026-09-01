package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 日志趋势数据点。time 为 "HH:mm" 展示标签，bucket 为该桶起始 epoch 毫秒。
 */
public record TrendPoint(
        @JsonProperty("time") String time,
        @JsonProperty("count") long count,
        @JsonProperty("bucket") long bucket) {
}

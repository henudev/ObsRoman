package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 服务日志 Top：日志量、ERROR 数量与 error_rate。
 */
public record ServiceRankingItem(
        @JsonProperty("service") String service,
        @JsonProperty("total") long total,
        @JsonProperty("errors") long errors,
        @JsonProperty("error_rate") double errorRate) {
}

package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LevelDistributionItem(
        @JsonProperty("level") String level,
        @JsonProperty("count") long count) {
}

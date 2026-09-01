package com.obsroman.tracelog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Storage 聚合接口的返回值，各 Dashboard API 按需取用其中一部分。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardResult {

    @JsonProperty("overview")
    private DashboardOverview overview;

    @JsonProperty("log_trend")
    private List<TrendPoint> logTrend;

    @JsonProperty("level_distribution")
    private List<LevelDistributionItem> levelDistribution;

    @JsonProperty("service_ranking")
    private List<ServiceRankingItem> serviceRanking;

    public DashboardOverview getOverview() {
        return overview;
    }

    public void setOverview(DashboardOverview overview) {
        this.overview = overview;
    }

    public List<TrendPoint> getLogTrend() {
        return logTrend;
    }

    public void setLogTrend(List<TrendPoint> logTrend) {
        this.logTrend = logTrend;
    }

    public List<LevelDistributionItem> getLevelDistribution() {
        return levelDistribution;
    }

    public void setLevelDistribution(List<LevelDistributionItem> levelDistribution) {
        this.levelDistribution = levelDistribution;
    }

    public List<ServiceRankingItem> getServiceRanking() {
        return serviceRanking;
    }

    public void setServiceRanking(List<ServiceRankingItem> serviceRanking) {
        this.serviceRanking = serviceRanking;
    }
}

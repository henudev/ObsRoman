package com.obsroman.tracelog.controller;

import com.obsroman.tracelog.common.ApiResponse;
import com.obsroman.tracelog.model.DashboardOverview;
import com.obsroman.tracelog.model.LevelDistributionItem;
import com.obsroman.tracelog.model.ServiceRankingItem;
import com.obsroman.tracelog.model.TrendPoint;
import com.obsroman.tracelog.service.DashboardService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dashboard 统计 API：不直接查询 OpenObserve，全部经 LogStorage 聚合接口。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PostMapping("/overview")
    public ApiResponse<DashboardOverview> overview(@RequestBody DashboardService.DashboardRequest request) {
        return ApiResponse.ok(dashboardService.overview(request));
    }

    @PostMapping("/log-trend")
    public ApiResponse<List<TrendPoint>> logTrend(@RequestBody DashboardService.DashboardRequest request) {
        return ApiResponse.ok(dashboardService.logTrend(request));
    }

    @PostMapping("/level-distribution")
    public ApiResponse<List<LevelDistributionItem>> levelDistribution(
            @RequestBody DashboardService.DashboardRequest request) {
        return ApiResponse.ok(dashboardService.levelDistribution(request));
    }

    @PostMapping("/service-ranking")
    public ApiResponse<List<ServiceRankingItem>> serviceRanking(
            @RequestBody DashboardService.DashboardRequest request) {
        return ApiResponse.ok(dashboardService.serviceRanking(request));
    }

    /** 最近 ERROR 日志（Dashboard 页面区块） */
    @PostMapping("/recent-errors")
    public ApiResponse<?> recentErrors(@RequestBody DashboardService.RecentErrorsRequest request) {
        return ApiResponse.ok(dashboardService.recentErrors(request));
    }
}

package com.obsroman.tracelog.controller;

import com.obsroman.tracelog.common.ApiResponse;
import com.obsroman.tracelog.model.TraceResult;
import com.obsroman.tracelog.service.TraceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * trace_id 完整链路查询。
 */
@RestController
@RequestMapping("/api/v1/traces")
public class TraceController {

    private final TraceQueryService traceQueryService;

    public TraceController(TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }

    @GetMapping("/{traceId}")
    public ApiResponse<TraceResult> trace(@PathVariable String traceId) {
        return ApiResponse.ok(traceQueryService.findByTraceId(traceId));
    }
}

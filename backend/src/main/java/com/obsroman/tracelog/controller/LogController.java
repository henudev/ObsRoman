package com.obsroman.tracelog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.obsroman.tracelog.common.ApiResponse;
import com.obsroman.tracelog.model.LogBatchResponse;
import com.obsroman.tracelog.model.LogSearchRequest;
import com.obsroman.tracelog.security.ApiKey;
import com.obsroman.tracelog.security.ApiKeyFilter;
import com.obsroman.tracelog.service.LogIngestionService;
import com.obsroman.tracelog.service.LogQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志写入与搜索 API。Controller 只做请求接收与响应，业务在 Service。
 */
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    private final LogIngestionService ingestionService;
    private final LogQueryService queryService;

    public LogController(LogIngestionService ingestionService, LogQueryService queryService) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> ingest(@RequestBody JsonNode body, HttpServletRequest request) {
        ingestionService.ingestSingle(body, currentApiKey(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.accepted());
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<LogBatchResponse>> ingestBatch(@RequestBody JsonNode body,
                                                                     HttpServletRequest request) {
        long contentLength = Math.max(0, request.getContentLengthLong());
        LogBatchResponse response = ingestionService.ingestBatch(body, currentApiKey(request), contentLength);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    @PostMapping("/search")
    public ApiResponse<?> search(@RequestBody LogSearchRequest searchRequest) {
        return ApiResponse.ok(queryService.search(searchRequest));
    }

    private ApiKey currentApiKey(HttpServletRequest request) {
        Object attr = request.getAttribute(ApiKeyFilter.ATTR_API_KEY);
        return attr instanceof ApiKey apiKey ? apiKey : null;
    }
}

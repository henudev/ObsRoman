package com.obsroman.tracelog.controller;

import com.obsroman.tracelog.model.ExportRequest;
import com.obsroman.tracelog.service.LogExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志导出 API：HTTP 流式响应（CSV / JSONL），内存占用与结果集无关。
 */
@RestController
@RequestMapping("/api/v1/logs")
public class ExportController {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final LogExportService exportService;

    public ExportController(LogExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestBody ExportRequest request) {
        LogExportService.ExportPlan plan = exportService.prepare(request);

        boolean csv = ExportRequest.FORMAT_CSV.equals(plan.format());
        MediaType mediaType = csv
                ? MediaType.parseMediaType("text/csv;charset=UTF-8")
                : MediaType.parseMediaType("application/x-ndjson");
        String extension = csv ? "csv" : "jsonl";
        String filename = "logs-" + LocalDateTime.now().format(FILE_TIMESTAMP) + "." + extension;

        StreamingResponseBody body = outputStream -> exportService.writeTo(plan, outputStream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                // 导出行数在流式写出前已知，前端据此提示（0 条时给出明确警告）
                .header("X-Export-Rows", String.valueOf(plan.totalRows()))
                .contentType(mediaType)
                .body(body);
    }
}

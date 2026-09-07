package com.obsroman.tracelog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.storage.InMemoryLogStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogExportServiceTest {

    private InMemoryLogStorage storage;
    private LogExportService service;
    private LogQueryFactory queryFactory;

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @BeforeEach
    void setUp() {
        storage = new InMemoryLogStorage();
        queryFactory = new LogQueryFactory(new TraceLogProperties());
        service = new LogExportService(storage, queryFactory,
                new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                new TraceLogProperties());
        long micros = OffsetDateTime.parse("2026-09-01T13:30:00+08:00").toInstant().toEpochMilli() * 1000;
        LogRecord record = storage.record(TRACE, "payment-service", "ERROR", micros);
        record.setMessage("timeout, \"waiting\" long");
        record.setAttributes(java.util.Map.of("order_id", "ORD001"));
        record.setRequestId("req_001");
        record.setDurationMs(123L);
        record.setStatusCode(500L);
        storage.records.add(record);
    }

    private LogExportService.ExportPlan plan(String format) {
        com.obsroman.tracelog.model.ExportRequest request = new com.obsroman.tracelog.model.ExportRequest();
        request.setStartTime("2026-09-01T13:00:00+08:00");
        request.setEndTime("2026-09-01T14:00:00+08:00");
        request.setLevel(List.of("ERROR"));
        request.setFormat(format);
        return service.prepare(request);
    }

    @Test
    void csvExportContainsFixedColumnsAndEscaping() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeTo(plan("csv"), out);
        String csv = out.toString(StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿timestamp,trace_id,span_id,request_id,service,environment,"
                + "level,type,event,message,user_id,method,path,status_code,duration_ms,host,instance,attributes\n");
        // 消息包含逗号与引号 → 整体加引号且内部引号成对
        assertThat(csv).contains("\"timeout, \"\"waiting\"\" long\"");
        // attributes 转 JSON 字符串
        assertThat(csv).contains("\"{\"\"order_id\"\":\"\"ORD001\"\"}\"");
        assertThat(csv.trim().split("\n")).hasSize(2); // header + 1 行
    }

    @Test
    void jsonlExportWritesOneJsonPerLine() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeTo(plan("jsonl"), out);
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).startsWith("{\"timestamp\":").contains("\"trace_id\":\"" + TRACE + "\"");
        assertThat(lines[0]).contains("\"attributes\":{\"order_id\":\"ORD001\"}");
    }

    @Test
    void unsupportedFormatIsRejected() {
        com.obsroman.tracelog.model.ExportRequest request = new com.obsroman.tracelog.model.ExportRequest();
        request.setStartTime("2026-09-01T13:00:00+08:00");
        request.setEndTime("2026-09-01T14:00:00+08:00");
        request.setFormat("xlsx");
        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(1304);
    }

    @Test
    void overLimitExportIsAllowedNow() {
        // 数量不再设上限：命中超过 100,000 条也能正常生成导出计划（不再抛 1303）
        storage.records.clear();
        long micros = OffsetDateTime.parse("2026-09-01T13:30:00+08:00").toInstant().toEpochMilli() * 1000;
        for (int i = 0; i < 100_001; i++) {
            storage.records.add(storage.record(TRACE, "s", "ERROR", micros + i));
        }
        LogExportService.ExportPlan exportPlan = plan("csv");
        assertThat(exportPlan.totalRows()).isEqualTo(100_001);
    }

    @Test
    void csvEscapeFollowsRfc4180() {
        assertThat(LogExportService.csvEscape("plain")).isEqualTo("plain");
        assertThat(LogExportService.csvEscape("a,b")).isEqualTo("\"a,b\"");
        assertThat(LogExportService.csvEscape("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(LogExportService.csvEscape("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(LogExportService.csvEscape(null)).isEqualTo("");
    }

    @Test
    void exportCursorIsStreaming() {
        storage.records.clear();
        long micros = OffsetDateTime.parse("2026-09-01T13:30:00+08:00").toInstant().toEpochMilli() * 1000;
        for (int i = 0; i < 25; i++) {
            storage.records.add(storage.record(TRACE, "s", "ERROR", micros + i));
        }
        LogExportService.ExportPlan exportPlan = plan("jsonl");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeTo(exportPlan, out);
        assertThat(out.toString(StandardCharsets.UTF_8).trim().split("\n")).hasSize(25);
    }
}

package com.obsroman.tracelog.service;

import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.TraceResult;
import com.obsroman.tracelog.storage.InMemoryLogStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceQueryServiceTest {

    private InMemoryLogStorage storage;
    private TraceQueryService service;

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @BeforeEach
    void setUp() {
        storage = new InMemoryLogStorage();
        service = new TraceQueryService(storage, new TraceLogProperties());
        OffsetDateTime base = OffsetDateTime.parse("2026-09-01T14:20:00+08:00");
        long micros = base.toInstant().toEpochMilli() * 1000;

        storage.records.add(log("gateway", "INFO", micros));
        storage.records.add(log("order-service", "INFO", micros + 100_000));
        storage.records.add(log("payment-service", "ERROR", micros + 1_500_000));
        storage.records.add(log("payment-service", "INFO", micros + 2_000_000));
    }

    private LogRecord log(String service, String level, long micros) {
        return storage.record(TRACE, service, level, micros);
    }

    @Test
    void aggregatesTraceAcrossServices() {
        TraceResult result = service.findByTraceId(TRACE);
        assertThat(result.getTraceId()).isEqualTo(TRACE);
        assertThat(result.getServices()).containsExactly("gateway", "order-service", "payment-service");
        assertThat(result.getDurationMs()).isEqualTo(2000L);
        assertThat(result.getStatus()).isEqualTo(TraceResult.Status.ERROR);
        assertThat(result.getLogs()).hasSize(4);
        // timestamp ASC
        for (int i = 1; i < result.getLogs().size(); i++) {
            assertThat(result.getLogs().get(i).getTimestampMicros())
                    .isGreaterThanOrEqualTo(result.getLogs().get(i - 1).getTimestampMicros());
        }
    }

    @Test
    void statusIsSuccessWhenNoError() {
        storage.records.clear();
        long micros = OffsetDateTime.parse("2026-09-01T14:20:00+08:00").toInstant().toEpochMilli() * 1000;
        storage.records.add(log("gateway", "INFO", micros));
        storage.records.add(log("gateway", "WARN", micros + 1000));
        assertThat(service.findByTraceId(TRACE).getStatus()).isEqualTo(TraceResult.Status.SUCCESS);
    }

    @Test
    void unknownTraceIs404() {
        assertThatThrownBy(() -> service.findByTraceId("4bf92f3577b34da6a3ce929d0e0e4737"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(1201);
    }

    @Test
    void invalidTraceIdIs400() {
        assertThatThrownBy(() -> service.findByTraceId("bad-id"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(1001);
    }
}

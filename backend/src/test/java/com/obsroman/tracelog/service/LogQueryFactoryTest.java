package com.obsroman.tracelog.service;

import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogSearchRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogQueryFactoryTest {

    private final LogQueryFactory factory = new LogQueryFactory(new TraceLogProperties());

    @Test
    void defaultsToLast15MinutesAndSize50() {
        LogQuery query = factory.create(new LogSearchRequest(), false);
        long rangeMs = (query.getEndTimeMicros() - query.getStartTimeMicros()) / 1000L;
        assertThat(rangeMs).isBetween(14L * 60_000, 16L * 60_000);
        assertThat(query.getSize()).isEqualTo(50);
        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.isAscending()).isFalse();
    }

    @Test
    void parsesExplicitRangeAndNormalizesEnums() {
        LogSearchRequest request = new LogSearchRequest();
        request.setStartTime("2026-09-01T13:00:00+08:00");
        request.setEndTime("2026-09-01T14:00:00+08:00");
        request.setService(List.of("Payment-Service"));
        request.setEnvironment(List.of("prod"));
        request.setLevel(List.of("warn", "ERROR"));
        request.setSize(100);
        LogQuery query = factory.create(request, false);

        assertThat(query.getStartTimeMicros())
                .isEqualTo(OffsetDateTime.parse("2026-09-01T13:00:00+08:00").toInstant().toEpochMilli() * 1000);
        assertThat(query.getServices()).containsExactly("Payment-Service");
        assertThat(query.getLevels()).containsExactly("WARN", "ERROR");
        assertThat(query.getSize()).isEqualTo(100);
    }

    @Test
    void rejectsRangeOver7Days() {
        LogSearchRequest request = new LogSearchRequest();
        request.setStartTime("2026-08-01T00:00:00+08:00");
        request.setEndTime("2026-09-01T00:00:00+08:00");
        assertThatThrownBy(() -> factory.create(request, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(1301);
    }

    @Test
    void exportUses24HourLimit() {
        LogSearchRequest request = new LogSearchRequest();
        request.setStartTime("2026-08-30T00:00:00+08:00");
        request.setEndTime("2026-08-31T01:00:00+08:00"); // 25h
        assertThatThrownBy(() -> factory.create(request, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode().code())
                .isEqualTo(1303);
    }

    @Test
    void rejectsInvalidLevelAndSize() {
        LogSearchRequest request = new LogSearchRequest();
        request.setLevel(List.of("VERBOSE"));
        assertThatThrownBy(() -> factory.create(request, false)).isInstanceOf(ApiException.class);

        LogSearchRequest bigSize = new LogSearchRequest();
        bigSize.setSize(501);
        assertThatThrownBy(() -> factory.create(bigSize, false)).isInstanceOf(ApiException.class);

        LogSearchRequest zeroPage = new LogSearchRequest();
        zeroPage.setPage(0);
        assertThatThrownBy(() -> factory.create(zeroPage, false)).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidTimeTextIsRejected() {
        LogSearchRequest request = new LogSearchRequest();
        request.setStartTime("not-a-time");
        assertThatThrownBy(() -> factory.create(request, false)).isInstanceOf(ApiException.class);
    }

    @Test
    void exportQueryIsAscendingWithExportPageSize() {
        LogSearchRequest request = new LogSearchRequest();
        request.setSize(5000); // 导出忽略 page/size
        LogQuery query = factory.create(request, true);
        assertThat(query.isAscending()).isTrue();
        assertThat(query.getSize()).isEqualTo(1000);
    }
}

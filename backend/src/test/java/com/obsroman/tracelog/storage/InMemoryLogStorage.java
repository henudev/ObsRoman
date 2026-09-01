package com.obsroman.tracelog.storage;

import com.obsroman.tracelog.model.DashboardQuery;
import com.obsroman.tracelog.model.DashboardResult;
import com.obsroman.tracelog.model.LogQuery;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogSearchResult;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 测试用内存存储实现：同时充当 LogStorage 抽象的参照实现。
 */
public class InMemoryLogStorage implements LogStorage {

    public final List<LogRecord> records = new CopyOnWriteArrayList<>();
    public final AtomicInteger writeCalls = new AtomicInteger();
    public final AtomicInteger searchCalls = new AtomicInteger();
    public final AtomicInteger countCalls = new AtomicInteger();
    public final AtomicLong injectedFailures = new AtomicLong();
    public Predicate<LogRecord> filter = r -> true;

    @Override
    public void write(List<LogRecord> records) {
        writeCalls.incrementAndGet();
        if (injectedFailures.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            throw new StorageException(com.obsroman.tracelog.common.ErrorCode.STORAGE_ERROR,
                    "injected failure", null, true);
        }
        this.records.addAll(records);
    }

    @Override
    public LogSearchResult search(LogQuery query) {
        searchCalls.incrementAndGet();
        List<LogRecord> matched = match(query);
        long total = matched.size();
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.getSize(), matched.size());
        return new LogSearchResult(total, query.getPage(), query.getSize(), new ArrayList<>(matched.subList(from, to)));
    }

    @Override
    public List<LogRecord> findByTraceId(String traceId) {
        return records.stream()
                .filter(r -> traceId.equals(r.getTraceId()))
                .sorted(Comparator.comparingLong(LogRecord::getTimestampMicros))
                .toList();
    }

    @Override
    public DashboardResult aggregate(DashboardQuery query) {
        return new DashboardResult();
    }

    @Override
    public long count(LogQuery query) {
        countCalls.incrementAndGet();
        return match(query).size();
    }

    @Override
    public LogCursor searchForExport(LogQuery query) {
        List<LogRecord> all = new ArrayList<>(match(query));
        all.sort(Comparator.comparingLong(LogRecord::getTimestampMicros));
        return new LogCursor() {
            private int offset = 0;

            @Override
            public List<LogRecord> nextBatch(int maxRows) {
                if (offset >= all.size() || maxRows <= 0) {
                    return List.of();
                }
                int to = Math.min(offset + maxRows, all.size());
                List<LogRecord> batch = new ArrayList<>(all.subList(offset, to));
                offset = to;
                return batch;
            }

            @Override
            public void close() {
            }
        };
    }

    private List<LogRecord> match(LogQuery query) {
        return records.stream()
                .filter(filter)
                .filter(r -> r.getTimestampMicros() >= query.getStartTimeMicros()
                        && r.getTimestampMicros() < query.getEndTimeMicros())
                .filter(r -> query.getServices().isEmpty() || query.getServices().contains(r.getService()))
                .filter(r -> query.getEnvironments().isEmpty() || query.getEnvironments().contains(r.getEnvironment()))
                .filter(r -> query.getLevels().isEmpty() || query.getLevels().contains(r.getLevel()))
                .filter(r -> query.getTypes().isEmpty() || query.getTypes().contains(r.getType()))
                .filter(r -> query.getTraceId() == null || query.getTraceId().equals(r.getTraceId()))
                .filter(r -> query.getRequestId() == null || query.getRequestId().equals(r.getRequestId()))
                .filter(r -> query.getUserId() == null || query.getUserId().equals(r.getUserId()))
                .toList();
    }

    public LogRecord record(String traceId, String service, String level, long timestampMicros) {
        try {
            var constructor = LogRecord.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            LogRecord record = constructor.newInstance();
            record.setTraceId(traceId);
            record.setService(service);
            record.setLevel(level);
            record.setTimestampMicros(timestampMicros);
            record.setTimestamp(java.time.OffsetDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestampMicros / 1000),
                    java.time.ZoneId.systemDefault()));
            record.setMessage("msg");
            return record;
        } catch (Exception e) {
            throw Assertions.<RuntimeException>fail("failed to build record: " + e.getMessage());
        }
    }
}

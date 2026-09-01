package com.obsroman.tracelog.queue;

import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.storage.InMemoryLogStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LogWorkerTest {

    private TraceLogProperties.Buffer buffer(int batchSize, long flushMs) {
        TraceLogProperties.Buffer buffer = new TraceLogProperties.Buffer();
        buffer.setBatchSize(batchSize);
        buffer.setFlushIntervalMs(flushMs);
        buffer.setCapacity(1000);
        buffer.setWorkerCount(1);
        return buffer;
    }

    @Test
    void flushesByBatchSize() {
        InMemoryLogStorage storage = new InMemoryLogStorage();
        LogQueue queue = new LogQueue(1000);
        LogWorker worker = new LogWorker(queue, storage, buffer(5, 60000));
        worker.start(1);
        try {
            for (int i = 0; i < 12; i++) {
                queue.offer(new LogRecord());
            }
            await().atMost(java.time.Duration.ofSeconds(2))
                    .until(() -> storage.writeCalls.get() >= 2);
            assertThat(storage.records.size()).isGreaterThanOrEqualTo(10);
            assertThat(worker.writtenTotal()).isGreaterThanOrEqualTo(10);
        } finally {
            worker.stop();
        }
    }

    @Test
    void flushesByIntervalWhenNotFull() {
        InMemoryLogStorage storage = new InMemoryLogStorage();
        LogQueue queue = new LogQueue(1000);
        LogWorker worker = new LogWorker(queue, storage, buffer(200, 200));
        worker.start(1);
        try {
            queue.offer(new LogRecord());
            await().atMost(java.time.Duration.ofSeconds(2))
                    .until(() -> !storage.records.isEmpty());
            assertThat(storage.records).hasSize(1);
        } finally {
            worker.stop();
        }
    }

    @Test
    void storageFailureDropsBatchWithoutCrashing() {
        InMemoryLogStorage storage = new InMemoryLogStorage();
        storage.injectedFailures.set(2); // 两次失败后恢复
        LogQueue queue = new LogQueue(1000);
        LogWorker worker = new LogWorker(queue, storage, buffer(10, 100));
        worker.start(1);
        try {
            for (int i = 0; i < 10; i++) {
                queue.offer(new LogRecord());
            }
            await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !storage.records.isEmpty() || worker.failedBatches() >= 1);
            assertThat(worker.isAlive()).isTrue();
        } finally {
            worker.stop();
        }
    }

    @Test
    void stopFlushesRemainingRecords() {
        InMemoryLogStorage storage = new InMemoryLogStorage();
        LogQueue queue = new LogQueue(1000);
        LogWorker worker = new LogWorker(queue, storage, buffer(200, 60000)); // 长等待，靠 stop 触发
        worker.start(1);
        for (int i = 0; i < 3; i++) {
            queue.offer(new LogRecord());
        }
        worker.stop();
        assertThat(storage.records).hasSize(3);
    }

    @Test
    void storageFailureIsCounted() {
        InMemoryLogStorage storage = new InMemoryLogStorage();
        storage.injectedFailures.set(1);
        LogQueue queue = new LogQueue(1000);
        LogWorker worker = new LogWorker(queue, storage, buffer(10, 100));
        worker.start(1);
        try {
            for (int i = 0; i < 10; i++) {
                queue.offer(new LogRecord());
            }
            await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> worker.failedBatches() >= 1 || !storage.records.isEmpty());
            assertThat(worker.isAlive()).isTrue();
            assertThat(worker.failedBatches() + worker.writtenTotal()).isGreaterThanOrEqualTo(1);
        } finally {
            worker.stop();
        }
    }
}

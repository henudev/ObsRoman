package com.obsroman.tracelog.queue;

import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.storage.LogStorage;
import com.obsroman.tracelog.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 后台写线程：凑批（batch-size）或超时（flush-interval-ms）即批量写入存储。
 * 写入失败不重试业务请求（Retry 在 Storage 层内部），最终失败按 DROP 计数。
 * OpenObserve 故障只体现为日志丢弃，绝不影响日志接收路径。
 */
public class LogWorker {

    private static final Logger log = LoggerFactory.getLogger(LogWorker.class);

    private final LogQueue queue;
    private final LogStorage storage;
    private final int batchSize;
    private final long flushIntervalMs;
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicLong writtenTotal = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private volatile boolean running = true;

    public LogWorker(LogQueue queue, LogStorage storage, TraceLogProperties.Buffer buffer) {
        this.queue = queue;
        this.storage = storage;
        this.batchSize = Math.max(1, buffer.getBatchSize());
        this.flushIntervalMs = Math.max(50, buffer.getFlushIntervalMs());
    }

    public void start(int workerCount) {
        for (int i = 0; i < Math.max(1, workerCount); i++) {
            Thread thread = new Thread(this::runLoop, "log-worker-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        log.info("log worker started: workers={}, batch-size={}, flush-interval-ms={}, queue-capacity={}",
                threads.size(), batchSize, flushIntervalMs, queue.size());
    }

    private void runLoop() {
        List<LogRecord> batch = new ArrayList<>(batchSize);
        while (running || queue.size() > 0) {
            batch.clear();
            try {
                LogRecord first = queue.poll(flushIntervalMs);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("log worker unexpected error", e);
            }
        }
    }

    private void flush(List<LogRecord> batch) {
        try {
            storage.write(batch);
            writtenTotal.addAndGet(batch.size());
        } catch (StorageException e) {
            failedBatches.incrementAndGet();
            queue.recordWriteFailed(batch.size());
            log.error("log batch write failed after retries, dropped {} records: {}",
                    batch.size(), e.getMessage());
        }
    }

    /** 优雅停机：唤醒线程并尽力清空队列 */
    public void stop() {
        running = false;
        for (Thread thread : threads) {
            LockSupport.unpark(thread);
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<LogRecord> remaining = new ArrayList<>();
        int pending = queue.size();
        if (pending > 0) {
            queue.drainTo(remaining, pending);
        }
        if (!remaining.isEmpty()) {
            try {
                storage.write(remaining);
                writtenTotal.addAndGet(remaining.size());
            } catch (Exception e) {
                queue.recordWriteFailed(remaining.size());
                log.error("shutdown flush failed, dropped {} records", remaining.size());
            }
        }
        log.info("log worker stopped, written-total={}", writtenTotal.get());
    }

    public boolean isAlive() {
        return threads.stream().anyMatch(Thread::isAlive);
    }

    public int workerCount() {
        return threads.size();
    }

    public long writtenTotal() {
        return writtenTotal.get();
    }

    public long failedBatches() {
        return failedBatches.get();
    }
}

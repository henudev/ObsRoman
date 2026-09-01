package com.obsroman.tracelog.queue;

import com.obsroman.tracelog.model.LogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界内存队列。
 * 原则：日志系统故障不能拖垮业务系统 —— 队列满立即 DROP 并计数，绝不阻塞请求线程。
 */
public class LogQueue {

    private static final Logger log = LoggerFactory.getLogger(LogQueue.class);

    private final BlockingQueue<LogRecord> queue;
    private final AtomicLong enqueuedTotal = new AtomicLong();
    private final AtomicLong queueFullDropped = new AtomicLong();
    private final AtomicLong writeFailedDropped = new AtomicLong();

    public LogQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
    }

    /**
     * 非阻塞提交。队列满返回 false（该条日志被丢弃并计数）。
     */
    public boolean offer(LogRecord record) {
        boolean accepted = queue.offer(record);
        if (accepted) {
            enqueuedTotal.incrementAndGet();
        } else {
            long dropped = queueFullDropped.incrementAndGet();
            if (dropped % 1000 == 1) {
                log.warn("log queue full, dropped total={} (capacity={})", dropped, queue.size() + queue.remainingCapacity());
            }
        }
        return accepted;
    }

    /**
     * 取出一条，最多等待 timeoutMillis；队列空返回 null。
     */
    public LogRecord poll(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 立即取走当前可用的最多 maxRecords 条（凑批用）。
     */
    public List<LogRecord> drainTo(List<LogRecord> sink, int maxRecords) {
        int bound = Math.min(maxRecords, 1024);
        List<LogRecord> drained = new ArrayList<>(bound);
        queue.drainTo(drained, maxRecords);
        sink.addAll(drained);
        return drained;
    }

    public int size() {
        return queue.size();
    }

    public long enqueuedTotal() {
        return enqueuedTotal.get();
    }

    public long queueFullDropped() {
        return queueFullDropped.get();
    }

    public long totalDropped() {
        return queueFullDropped.get() + writeFailedDropped.get();
    }

    public void recordWriteFailed(int count) {
        if (count > 0) {
            writeFailedDropped.addAndGet(count);
        }
    }

    public long writeFailedDropped() {
        return writeFailedDropped.get();
    }
}

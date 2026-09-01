package com.obsroman.tracelog.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace Log Service Java SDK（零第三方依赖，JDK 17+）。
 *
 * <p>核心语义与服务端一致：
 * <ul>
 *   <li>{@link #send(LogRecord)}：非阻塞提交内存队列，立即返回；队列满或后续写入失败按 DROP 计数，
 *       绝不阻塞业务线程（"日志故障不影响业务"）。</li>
 *   <li>后台线程凑批（默认 200 条或 1 秒）批量写入；网络异常与 408/429/5xx 重试
 *       （默认 3 次，退避 100/500/2000ms），400/401/403/413 不重试。</li>
 *   <li>{@link #sendSync(LogRecord)} / {@link #sendBatchSync(List)}：同步直发，失败抛 {@link TraceLogException}。</li>
 * </ul>
 */
public final class TraceLogClient implements AutoCloseable {

    /** 单批最大条数（服务端限制） */
    public static final int MAX_BATCH_RECORDS = 500;
    /** 单条日志字节上限（服务端限制） */
    public static final int MAX_LOG_BYTES = 65536;

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final long[] backoffMs;
    private final int maxRetries;

    private final ArrayBlockingQueue<LogRecord> queue;
    private final int batchSize;
    private final long flushIntervalMs;
    private final Thread worker;
    /** 已提交但尚未完成写入/丢弃的日志条数（flush 依据，无竞态） */
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean running = true;

    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    private TraceLogClient(Builder builder) {
        this.endpoint = builder.endpoint;
        this.apiKey = builder.apiKey;
        this.requestTimeout = Duration.ofMillis(builder.writeTimeoutMs);
        this.backoffMs = builder.backoffMs;
        this.maxRetries = builder.maxRetries;
        this.batchSize = Math.min(Math.max(1, builder.batchSize), MAX_BATCH_RECORDS);
        this.flushIntervalMs = builder.flushIntervalMs;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, builder.queueCapacity));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(builder.connectTimeoutMs))
                .build();
        this.worker = new Thread(this::runLoop, "trace-log-sdk");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------- 异步接口（推荐，业务路径使用） ----------

    /**
     * 非阻塞提交日志到内部队列，由后台线程凑批写入。
     *
     * @return false 表示队列满被丢弃（已计数，不影响调用方）
     */
    public boolean send(LogRecord record) {
        byte[] payload = record.toJson().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_LOG_BYTES) {
            droppedCount.incrementAndGet();
            return false;
        }
        boolean accepted = queue.offer(record);
        if (accepted) {
            pending.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
        }
        return accepted;
    }

    // ---------- 同步接口（脚本/测试/强一致场景使用） ----------

    /** 同步单条写入；校验失败（400）或重试耗尽抛 {@link TraceLogException} */
    public void sendSync(LogRecord record) {
        byte[] body = record.toJson().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_LOG_BYTES) {
            throw new TraceLogException("log record exceeds " + MAX_LOG_BYTES + " bytes");
        }
        postWithRetry("/api/v1/logs", body, "send log");
        sentCount.incrementAndGet();
    }

    /** 同步批量写入：自动按 500 条分块；返回服务端 accepted/rejected 汇总 */
    public BatchResult sendBatchSync(List<LogRecord> records) {
        int accepted = 0;
        int rejected = 0;
        for (int start = 0; start < records.size(); start += MAX_BATCH_RECORDS) {
            List<Map<String, Object>> chunk = new ArrayList<>();
            for (LogRecord record : records.subList(start, Math.min(start + MAX_BATCH_RECORDS, records.size()))) {
                chunk.add(record.toMap());
            }
            Map<String, Object> payload = Map.of("logs", chunk);
            String response = postWithRetry("/api/v1/logs/batch", Json.write(payload).getBytes(StandardCharsets.UTF_8),
                    "send batch");
            Map<String, Object> parsed = Json.parseObject(response);
            Map<String, Object> data = asMap(parsed.get("data"));
            accepted += asLong(data.get("accepted"));
            rejected += asLong(data.get("rejected"));
        }
        sentCount.addAndGet(accepted + rejected);
        rejectedCount.addAndGet(rejected);
        return new BatchResult(accepted, rejected);
    }

    /** 等待内部队列清空（最多 timeoutMillis） */
    public void flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (pending.get() == 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 尽力清空残留
        List<LogRecord> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try {
                postBatch(remaining);
                sentCount.addAndGet(remaining.size());
            } catch (Exception e) {
                droppedCount.addAndGet(remaining.size());
            } finally {
                pending.addAndGet(-remaining.size());
            }
        }
    }

    public long getSentCount() {
        return sentCount.get();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    // ---------- 内部实现 ----------

    private void runLoop() {
        List<LogRecord> batch = new ArrayList<>(batchSize);
        while (running || queue.size() > 0) {
            batch.clear();
            try {
                LogRecord first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                try {
                    postBatch(batch);
                    sentCount.addAndGet(batch.size());
                } catch (Exception e) {
                    droppedCount.addAndGet(batch.size());
                } finally {
                    pending.addAndGet(-batch.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void postBatch(List<LogRecord> records) throws Exception {
        for (int start = 0; start < records.size(); start += MAX_BATCH_RECORDS) {
            List<Map<String, Object>> chunk = new ArrayList<>();
            for (LogRecord record : records.subList(start, Math.min(start + MAX_BATCH_RECORDS, records.size()))) {
                chunk.add(record.toMap());
            }
            Map<String, Object> payload = Map.of("logs", chunk);
            postWithRetry("/api/v1/logs/batch", Json.write(payload).getBytes(StandardCharsets.UTF_8), "send batch");
        }
    }

    /** POST 并处理重试；返回 2xx 响应体，失败抛 TraceLogException */
    private String postWithRetry(String path, byte[] body, String action) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint.resolve(path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        int attempts = maxRetries + 1;
        Exception last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                sleep(backoffMs[Math.min(attempt - 1, backoffMs.length - 1)]);
            }
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                // 与服务端一致的不可重试清单
                if (status == 400 || status == 401 || status == 403 || status == 413) {
                    throw new TraceLogException(action + " failed: HTTP " + status
                            + " " + snippet(response.body()));
                }
                last = new TraceLogException(action + " failed: HTTP " + status + " " + snippet(response.body()));
            } catch (IOException e) {
                last = new TraceLogException(action + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TraceLogException(action + " interrupted", e);
            }
        }
        throw last instanceof TraceLogException tle
                ? tle
                : new TraceLogException(action + " failed after retries", last);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String snippet(String text) {
        return text == null ? "" : text.substring(0, Math.min(160, text.length()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 批量写入结果 */
    public record BatchResult(int accepted, int rejected) {
    }

    public static final class Builder {
        private URI endpoint;
        private String apiKey;
        private int queueCapacity = 10_000;
        private int batchSize = 200;
        private long flushIntervalMs = 1000;
        private long connectTimeoutMs = 500;
        private long writeTimeoutMs = 5000;
        private int maxRetries = 3;
        private long[] backoffMs = {100, 500, 2000};

        /** 服务端地址，如 http://localhost:8080（无需以 / 结尾） */
        public Builder endpoint(String endpoint) {
            String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            this.endpoint = URI.create(normalized);
            return this;
        }

        /** 具备 log:write 权限的 API Key */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** 内部队列容量（默认 10000） */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        /** 凑批条数（默认 200，上限 500） */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /** 凑批间隔毫秒（默认 1000） */
        public Builder flushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder writeTimeoutMs(long writeTimeoutMs) {
            this.writeTimeoutMs = writeTimeoutMs;
            return this;
        }

        /** 网络类失败重试次数（默认 3；400/401/403/413 永不重试） */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /** 重试退避序列（默认 100/500/2000ms） */
        public Builder backoffMs(long... backoffMs) {
            this.backoffMs = backoffMs;
            return this;
        }

        public TraceLogClient build() {
            if (endpoint == null) {
                throw new TraceLogException("endpoint is required");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new TraceLogException("apiKey is required");
            }
            return new TraceLogClient(this);
        }
    }
}

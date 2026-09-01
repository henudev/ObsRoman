package com.obsroman.tracelog.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceLogClientTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    /** 状态码序列：弹出顺序决定每次响应；空队列时返回 200 */
    private final ConcurrentLinkedQueue<Integer> statusSequence = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean failAll = new AtomicBoolean(false);
    private final AtomicInteger calls = new AtomicInteger();
    private String baseUrl;

    private record Received(String path, String auth, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(body, StandardCharsets.UTF_8)));
            calls.incrementAndGet();
            Integer status = statusSequence.poll();
            int code = failAll.get() ? 500 : (status == null ? 200 : status);
            byte[] responseBody = code == 200
                    ? ("{\"code\":0,\"message\":\"ok\",\"data\":{\"accepted\":"
                        + countLogs(body) + ",\"rejected\":0}}").getBytes(StandardCharsets.UTF_8)
                    : "{\"code\":1500,\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static int countLogs(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("\"trace_id\"", index)) >= 0) {
            count++;
            index += 10;
        }
        return count;
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private LogRecord record() {
        return LogRecord.builder()
                .traceId(TraceContext.generate().traceId())
                .service("order-service")
                .environment("prod")
                .level("INFO")
                .event("order.create")
                .message("创建订单成功")
                .durationMs(123)
                .attribute("order_id", "ORD001")
                .build();
    }

    private TraceLogClient client() {
        return TraceLogClient.builder()
                .endpoint(baseUrl)
                .apiKey("test-key")
                .maxRetries(2)
                .backoffMs(10, 10, 10)
                .build();
    }

    @Test
    void asyncSendIsFlushedAsBatch() {
        try (TraceLogClient client = client()) {
            for (int i = 0; i < 5; i++) {
                assertTrue(client.send(record()));
            }
            client.flush(5000);
            assertEquals(0, client.getQueueSize());
            assertEquals(5, client.getSentCount());
            assertEquals(0, client.getDroppedCount());
            assertTrue(calls.get() >= 1);

            Received batch = received.peek();
            assertEquals("/api/v1/logs/batch", batch.path());
            assertEquals("Bearer test-key", batch.auth());
            assertTrue(batch.body().contains("\"trace_id\""));
            assertTrue(batch.body().contains("order-service"));
        }
    }

    @Test
    void sendSyncPostsSingleLog() {
        try (TraceLogClient client = client()) {
            client.sendSync(record());
            Received single = received.peek();
            assertEquals("/api/v1/logs", single.path());
            assertTrue(single.body().contains("创建订单成功"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void batchSyncChunksOverLimit() {
        try (TraceLogClient client = client()) {
            // 501 条 → 两个批量请求（500 + 1）
            List<LogRecord> records = new ArrayList<>();
            for (int i = 0; i < 501; i++) {
                records.add(record());
            }
            TraceLogClient.BatchResult result = client.sendBatchSync(records);
            assertEquals(2, calls.get());
            assertEquals(0, result.rejected());
        }
    }

    @Test
    void syncRetriesOnServerErrorsThenSucceeds() {
        statusSequence.add(500);
        statusSequence.add(503);
        try (TraceLogClient client = client()) {
            client.sendSync(record());
            assertEquals(3, calls.get());
            assertEquals(1, client.getSentCount());
        }
    }

    @Test
    void syncFailsFastOn400() {
        statusSequence.add(400);
        try (TraceLogClient client = client()) {
            assertThrows(TraceLogException.class, () -> client.sendSync(record()));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void asyncDropsWhenServerAlwaysFails() {
        failAll.set(true);
        try (TraceLogClient client = TraceLogClient.builder()
                .endpoint(baseUrl)
                .apiKey("test-key")
                .maxRetries(1)
                .backoffMs(1, 1, 1)
                .build()) {
            client.send(record());
            client.flush(10000);
            assertEquals(1, client.getDroppedCount());
            assertEquals(0, client.getSentCount());
        }
    }

    @Test
    void oversizedRecordIsDroppedSilently() {
        try (TraceLogClient client = client()) {
            LogRecord oversized = LogRecord.builder()
                    .traceId(TraceContext.generate().traceId())
                    .service("s")
                    .level("INFO")
                    .message("x".repeat(70000))
                    .build();
            assertFalse(client.send(oversized));
            assertEquals(1, client.getDroppedCount());
        }
    }

    @Test
    void traceContextHelpers() {
        TraceContext generated = TraceContext.generate();
        assertTrue(generated.traceId().matches("^[0-9a-f]{32}$"));
        assertTrue(generated.spanId().matches("^[0-9a-f]{16}$"));
        assertEquals("00-" + generated.traceId() + "-" + generated.spanId() + "-01", generated.traceparent());

        TraceContext parsed = TraceContext.parse(generated.traceparent());
        assertEquals(generated.traceId(), parsed.traceId());

        TraceContext child = TraceContext.child(parsed);
        assertEquals(parsed.traceId(), child.traceId());

        // 非法输入回退 null
        assertFalse(TraceContext.parse("bad-input") != null);
    }

    @Test
    void builderValidation() {
        assertThrows(TraceLogException.class, () -> TraceLogClient.builder().apiKey("k").build());
        assertThrows(TraceLogException.class, () -> TraceLogClient.builder().endpoint(baseUrl).build());
        assertThrows(TraceLogException.class, () -> LogRecord.builder().service("s").message("m").build());
        assertThrows(TraceLogException.class, () -> LogRecord.builder()
                .traceId("bad").service("s").level("INFO").message("m").build());
    }

    @Test
    void missingTraceIdIsGeneratedLocally() {
        LogRecord record = LogRecord.builder().service("s").level("INFO").message("m").build();
        assertTrue(record.toMap().containsKey("trace_id"));
        String traceId = String.valueOf(record.toMap().get("trace_id"));
        assertTrue(traceId.matches("^[0-9a-f]{32}$"));
    }
}

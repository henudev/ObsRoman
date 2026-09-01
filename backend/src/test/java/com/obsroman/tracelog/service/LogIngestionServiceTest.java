package com.obsroman.tracelog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogBatchResponse;
import com.obsroman.tracelog.queue.LogQueue;
import com.obsroman.tracelog.security.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogIngestionServiceTest {

    private LogQueue queue;
    private LogIngestionService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @BeforeEach
    void setUp() {
        queue = new LogQueue(10);
        service = new LogIngestionService(queue, mapper, new TraceLogProperties());
    }

    private ObjectNode log(String service, String level) {
        ObjectNode node = mapper.createObjectNode();
        node.put("trace_id", TRACE);
        node.put("service", service);
        node.put("environment", "prod");
        node.put("level", level);
        node.put("message", "msg");
        return node;
    }

    @Test
    void singleIngestEnqueuesWithoutBlocking() {
        service.ingestSingle(log("order-service", "INFO"), null);
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.enqueuedTotal()).isEqualTo(1);
    }

    @Test
    void batchSupportsPartialSuccess() {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("logs")
                .add(log("order-service", "INFO"))
                .add(log("payment-service", "ERROR"))
                .add(log("order-service", "BAD_LEVEL"));

        LogBatchResponse response = service.ingestBatch(body, null, 100);
        assertThat(response.getAccepted()).isEqualTo(2);
        assertThat(response.getRejected()).isEqualTo(1);
        assertThat(response.getErrors()).hasSize(1);
        assertThat(response.getErrors().get(0).index()).isEqualTo(2);
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    void batchRequiresLogsArray() {
        assertThatThrownBy(() -> service.ingestBatch(mapper.createObjectNode(), null, 100))
                .isInstanceOf(com.obsroman.tracelog.common.ApiException.class);
        assertThatThrownBy(() -> service.ingestBatch(null, null, 100))
                .isInstanceOf(com.obsroman.tracelog.common.ApiException.class);
    }

    @Test
    void batchOverByteLimitIsRejected() {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("logs").add(log("order-service", "INFO"));
        assertThatThrownBy(() -> service.ingestBatch(body, null, 10 * 1024 * 1024))
                .isInstanceOf(com.obsroman.tracelog.common.ApiException.class)
                .extracting(e -> ((com.obsroman.tracelog.common.ApiException) e).getErrorCode().code())
                .isEqualTo(1005);
    }

    @Test
    void writeKeyBindingEnforcesServiceAndEnvironment() {
        ApiKey boundKey = new ApiKey("order-writer", "k", Set.of("log:write"), "order-service", "prod");

        service.ingestSingle(log("order-service", "INFO"), boundKey); // OK

        assertThatThrownBy(() -> service.ingestSingle(log("payment-service", "INFO"), boundKey))
                .isInstanceOf(com.obsroman.tracelog.model.LogValidationException.class)
                .hasMessageContaining("bound to service");
    }

    @Test
    void queueFullDropsSilentlyAndCounts() {
        for (int i = 0; i < 10; i++) {
            service.ingestSingle(log("order-service", "INFO"), null);
        }
        assertThat(queue.size()).isEqualTo(10);
        service.ingestSingle(log("order-service", "INFO"), null); // 队列满 → DROP
        assertThat(queue.size()).isEqualTo(10);
        assertThat(queue.queueFullDropped()).isEqualTo(1);
        assertThat(service.droppedTotal()).isEqualTo(1);
    }
}

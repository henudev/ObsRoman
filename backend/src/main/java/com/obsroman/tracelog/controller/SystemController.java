package com.obsroman.tracelog.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.obsroman.tracelog.common.AppVersion;
import com.obsroman.tracelog.queue.LogQueue;
import com.obsroman.tracelog.queue.LogWorker;
import com.obsroman.tracelog.storage.openobserve.OpenObserveClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统 API：/health（进程存活）、/ready（依赖就绪状态）。
 * OpenObserve 故障时 /ready 返回 DEGRADED 而不是让服务崩溃重启。
 */
@RestController
public class SystemController {

    private final OpenObserveClient openObserveClient;
    private final LogQueue logQueue;
    private final LogWorker logWorker;

    public SystemController(OpenObserveClient openObserveClient, LogQueue logQueue, LogWorker logWorker) {
        this.openObserveClient = openObserveClient;
        this.logQueue = logQueue;
        this.logWorker = logWorker;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        HealthResponse response = new HealthResponse();
        response.status = "UP";
        response.version = AppVersion.VERSION;
        response.releaseDate = AppVersion.RELEASE_DATE;
        return response;
    }

    @GetMapping("/ready")
    public ReadyResponse ready() {
        boolean ooUp = openObserveClient.healthy();
        boolean queueUp = logWorker.isAlive() && logWorker.workerCount() > 0;

        ReadyResponse response = new ReadyResponse();
        response.status = ooUp ? "UP" : "DEGRADED";
        response.openobserve = ooUp ? "UP" : "DOWN";
        response.queue = queueUp ? "UP" : "DOWN";

        // 内部运行计数（log_drop_total 等），便于运维观测降级情况
        response.logDropTotal = logQueue.totalDropped();
        response.queueFullDropped = logQueue.queueFullDropped();
        response.writeFailedDropped = logQueue.writeFailedDropped();
        response.enqueuedTotal = logQueue.enqueuedTotal();
        response.queueSize = logQueue.size();
        return response;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HealthResponse {
        @JsonProperty("status")
        public String status;

        @JsonProperty("version")
        public String version;

        @JsonProperty("release_date")
        public String releaseDate;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReadyResponse {
        @JsonProperty("status")
        public String status;

        @JsonProperty("openobserve")
        public String openobserve;

        @JsonProperty("queue")
        public String queue;

        @JsonProperty("log_drop_total")
        public Long logDropTotal;

        @JsonProperty("queue_full_dropped")
        public Long queueFullDropped;

        @JsonProperty("write_failed_dropped")
        public Long writeFailedDropped;

        @JsonProperty("log_enqueued_total")
        public Long enqueuedTotal;

        @JsonProperty("queue_size")
        public Integer queueSize;
    }
}

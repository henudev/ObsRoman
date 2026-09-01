package com.obsroman.tracelog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.model.LogBatchResponse;
import com.obsroman.tracelog.model.LogEntryError;
import com.obsroman.tracelog.model.LogRecord;
import com.obsroman.tracelog.model.LogRecordParser;
import com.obsroman.tracelog.model.LogValidationException;
import com.obsroman.tracelog.queue.LogQueue;
import com.obsroman.tracelog.security.ApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 日志写入服务：校验 → 规范化 → 提交内存队列（立即返回，202 不等待持久化）。
 * 单条失败不影响批量其余条目；队列满 DROP 并计数，绝不阻塞请求线程。
 */
@Service
public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    private final LogQueue queue;
    private final ObjectMapper mapper;
    private final long maxLogBytes;
    private final int maxBatchRecords;
    private final long maxBatchBytes;

    public LogIngestionService(LogQueue queue, ObjectMapper mapper, TraceLogProperties properties) {
        this.queue = queue;
        this.mapper = mapper;
        this.maxLogBytes = properties.getLimits().getMaxLogBytes();
        this.maxBatchRecords = properties.getLimits().getMaxBatchRecords();
        this.maxBatchBytes = properties.getLimits().getMaxBatchBytes();
    }

    /** 单条写入：返回即表示日志已被服务受理（进入队列或被降级丢弃） */
    public void ingestSingle(JsonNode body, ApiKey apiKey) {
        LogRecord record = parseSafe(body);
        checkBinding(record, apiKey);
        submit(record);
    }

    /** 批量写入：部分成功，单条校验失败只拒绝该条 */
    public LogBatchResponse ingestBatch(JsonNode body, ApiKey apiKey, long contentLength) {
        if (contentLength > maxBatchBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "batch body " + contentLength + " bytes exceeds limit " + maxBatchBytes);
        }
        if (body == null || !body.isObject() || !body.has("logs") || !body.get("logs").isArray()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "field 'logs' (array) is required");
        }
        ArrayNode logs = (ArrayNode) body.get("logs");
        if (logs.size() > maxBatchRecords) {
            throw new ApiException(ErrorCode.BATCH_TOO_LARGE,
                    "batch contains " + logs.size() + " records, exceeds limit " + maxBatchRecords);
        }

        int accepted = 0;
        List<LogEntryError> errors = new ArrayList<>();
        OffsetDateTime receiveTime = OffsetDateTime.now();

        for (int i = 0; i < logs.size(); i++) {
            try {
                LogRecord record = LogRecordParser.parse(logs.get(i), receiveTime, mapper, maxLogBytes);
                checkBinding(record, apiKey);
                submit(record);
                accepted++;
            } catch (LogValidationException e) {
                errors.add(new LogEntryError(i, e.getErrorCode().code(), e.getMessage()));
            }
        }
        return new LogBatchResponse(accepted, errors.size(), 0, errors.isEmpty() ? null : errors);
    }

    private LogRecord parseSafe(JsonNode body) {
        return LogRecordParser.parse(body, OffsetDateTime.now(), mapper, maxLogBytes);
    }

    /** 写入 Key 绑定的 service / environment 校验 */
    private void checkBinding(LogRecord record, ApiKey apiKey) {
        if (apiKey == null) {
            return;
        }
        if (apiKey.serviceBound() && !apiKey.boundService().equals(record.getService())) {
            throw new LogValidationException(ErrorCode.FORBIDDEN,
                    "api key is bound to service '" + apiKey.boundService() + "' but log service is '"
                            + record.getService() + "'");
        }
        if (apiKey.environmentBound() && !apiKey.boundEnvironment().equals(record.getEnvironment())) {
            throw new LogValidationException(ErrorCode.FORBIDDEN,
                    "api key is bound to environment '" + apiKey.boundEnvironment() + "' but log environment is '"
                            + record.getEnvironment() + "'");
        }
    }

    private void submit(LogRecord record) {
        boolean enqueued = queue.offer(record);
        if (!enqueued) {
            // 队列满：DROP（已计数），202 语义为"已被服务受理"，持久化为尽力而为
            log.debug("queue full, log dropped (service={}, trace_id={})", record.getService(), record.getTraceId());
        }
    }

    public long droppedTotal() {
        return queue.totalDropped();
    }
}

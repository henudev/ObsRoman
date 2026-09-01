package com.obsroman.tracelog.storage.openobserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import com.obsroman.tracelog.storage.StorageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * OpenObserve HTTP 客户端。仅负责协议交互（ingest / _search / healthz），
 * 不含任何业务 SQL —— SQL 构建集中在 {@link OpenObserveSql} 与 OpenObserveLogStorage。
 *
 * 超时策略：connect 500ms；写入 5s；查询 10s；健康探测 2s。
 */
public class OpenObserveClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String organization;
    private final String streamName;
    private final String authHeaderValue;
    private final RestClient writeClient;
    private final RestClient queryClient;
    private final RestClient healthClient;

    public OpenObserveClient(ObjectMapper mapper, TraceLogProperties.OpenObserve props) {
        this.mapper = mapper;
        this.baseUrl = trimTrailingSlash(props.getBaseUrl());
        this.organization = props.getOrganization();
        this.streamName = props.getStreamName();
        this.authHeaderValue = resolveAuth(props);
        this.writeClient = buildRestClient(props.getConnectTimeoutMs(), props.getWriteTimeoutMs());
        this.queryClient = buildRestClient(props.getConnectTimeoutMs(), props.getQueryTimeoutMs());
        this.healthClient = buildRestClient(props.getConnectTimeoutMs(), props.getHealthTimeoutMs());
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String resolveAuth(TraceLogProperties.OpenObserve props) {
        if (props.getToken() != null && !props.getToken().isBlank()) {
            return "Bearer " + props.getToken().trim();
        }
        String raw = (nullSafe(props.getUsername()) + ":" + nullSafe(props.getPassword()));
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static RestClient buildRestClient(long connectTimeoutMs, long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public String ingestUrl() {
        return baseUrl + "/api/" + organization + "/" + streamName + "/_json";
    }

    /**
     * 批量 JSON ingest。
     *
     * @return failed 数（OO 对单条记录的 schema 拒绝计入 failed，不重试）
     */
    public IngestResult ingest(List<ObjectNode> docs) {
        String url = ingestUrl();
        try {
            byte[] body = mapper.writeValueAsBytes(docs);
            String response = writeClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, authHeaderValue)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseIngestResponse(response);
        } catch (RestClientResponseException e) {
            throw mapHttpError("ingest", e);
        } catch (ResourceAccessException e) {
            throw new StorageException(ErrorCode.STORAGE_TIMEOUT,
                    "openobserve ingest timeout/unreachable: " + e.getMessage(), e, true);
        } catch (Exception e) {
            throw new StorageException(ErrorCode.STORAGE_ERROR,
                    "openobserve ingest failed: " + e.getMessage(), e, false);
        }
    }

    private IngestResult parseIngestResponse(String response) {
        long successful = 0;
        long failed = 0;
        try {
            JsonNode root = mapper.readTree(response == null ? "{}" : response);
            JsonNode statusArr = root.get("status");
            if (statusArr != null && statusArr.isArray()) {
                for (JsonNode s : statusArr) {
                    successful += s.path("successful").asLong(0);
                    failed += s.path("failed").asLong(0);
                }
            }
        } catch (Exception ignored) {
            // 响应体解析失败不影响写入结果判定
        }
        return new IngestResult(successful, failed);
    }

    /**
     * 执行 OpenObserve search SQL（时间单位：epoch 微秒）。
     *
     * @return 原始响应 JSON（hits / total / ...）
     */
    public JsonNode search(long startTimeMicros, long endTimeMicros, String sql, int from, int size) {
        ObjectNode query = mapper.createObjectNode();
        query.put("start_time", startTimeMicros);
        query.put("end_time", endTimeMicros);
        query.put("sql", sql);
        query.put("from", Math.max(0, from));
        query.put("size", Math.max(1, size));

        ObjectNode body = mapper.createObjectNode();
        body.set("query", query);

        try {
            return queryClient.post()
                    .uri(baseUrl + "/api/" + organization + "/_search")
                    .header(HttpHeaders.AUTHORIZATION, authHeaderValue)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw mapHttpError("search", e);
        } catch (ResourceAccessException e) {
            throw new StorageException(ErrorCode.STORAGE_TIMEOUT,
                    "openobserve query timeout/unreachable: " + e.getMessage(), e, false);
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(ErrorCode.STORAGE_ERROR,
                    "openobserve search failed: " + e.getMessage(), e, false);
        }
    }

    /** GET /healthz 探活，任意异常返回 false（不向上抛，供 /ready 降级判断） */
    public boolean healthy() {
        try {
            String body = healthClient.get()
                    .uri(baseUrl + "/healthz")
                    .header(HttpHeaders.AUTHORIZATION, authHeaderValue)
                    .retrieve()
                    .body(String.class);
            return body != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 拉取流的 Schema 字段名集合（用于字段感知 SQL 构建）。流不存在或异常返回 null。
     */
    public java.util.Set<String> schemaFields() {
        try {
            JsonNode response = queryClient.get()
                    .uri(baseUrl + "/api/" + organization + "/streams/" + streamName + "/schema")
                    .header(HttpHeaders.AUTHORIZATION, authHeaderValue)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode schema = response == null ? null : response.get("schema");
            if (schema == null || !schema.isArray()) {
                return null;
            }
            var fields = new java.util.HashSet<String>();
            for (JsonNode field : schema) {
                fields.add(field.path("name").asText());
            }
            return fields;
        } catch (Exception e) {
            return null;
        }
    }

    private StorageException mapHttpError(String action, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        // 仅网络异常/408/429/5xx 可重试；400/401/403/404/413 等不重试
        boolean retryable = status == 408 || status == 429 || status >= 500;
        return new StorageException(ErrorCode.STORAGE_ERROR,
                "openobserve " + action + " http " + status + ": " + e.getResponseBodyAsString(),
                e, retryable);
    }

    public record IngestResult(long successful, long failed) {
    }

    /** 供测试与健康检查使用 */
    public static ArrayNode newArrayNode(ObjectMapper mapper) {
        return mapper.createArrayNode();
    }
}

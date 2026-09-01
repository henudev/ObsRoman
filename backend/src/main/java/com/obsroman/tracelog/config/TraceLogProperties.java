package com.obsroman.tracelog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * trace-log 配置根节点。敏感信息（OpenObserve 凭据、API Key）一律通过环境变量注入，
 * 仓库内不保存任何真实密码与 Token。
 */
@ConfigurationProperties(prefix = "trace-log")
public class TraceLogProperties {

    private Buffer buffer = new Buffer();
    private Security security = new Security();
    private Limits limits = new Limits();
    private Storage storage = new Storage();

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public static class Buffer {
        private int capacity = 10000;
        private int batchSize = 200;
        private long flushIntervalMs = 1000;
        private int workerCount = 2;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
        }

        public int getWorkerCount() {
            return workerCount;
        }

        public void setWorkerCount(int workerCount) {
            this.workerCount = workerCount;
        }
    }

    public static class Security {
        /** false 时关闭鉴权，仅限本地调试 */
        private boolean enabled = true;
        private List<ApiKeyConfig> apiKeys = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<ApiKeyConfig> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(List<ApiKeyConfig> apiKeys) {
            this.apiKeys = apiKeys;
        }
    }

    public static class ApiKeyConfig {
        private String name;
        private String key;
        /** 逗号分隔或 YAML 列表：log:write,log:read,trace:read,dashboard:read,log:export */
        private List<String> permissions = new ArrayList<>();
        /** 写入 Key 可绑定 service / environment（空表示不限制） */
        private String service;
        private String environment;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public List<String> getPermissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }
    }

    public static class Limits {
        private long maxLogBytes = 65536;
        private long maxBatchBytes = 5242880;
        private int maxBatchRecords = 500;
        private int searchDefaultSize = 50;
        private int searchMaxSize = 500;
        private long searchDefaultRangeMinutes = 15;
        private long searchMaxRangeDays = 7;
        private long dashboardDefaultRangeMinutes = 60;
        private long dashboardMaxRangeHours = 24;
        private long exportMaxRows = 100000;
        private long exportMaxRangeHours = 24;
        private int exportPageSize = 1000;
        private int traceMaxLogs = 5000;

        public long getMaxLogBytes() {
            return maxLogBytes;
        }

        public void setMaxLogBytes(long maxLogBytes) {
            this.maxLogBytes = maxLogBytes;
        }

        public long getMaxBatchBytes() {
            return maxBatchBytes;
        }

        public void setMaxBatchBytes(long maxBatchBytes) {
            this.maxBatchBytes = maxBatchBytes;
        }

        public int getMaxBatchRecords() {
            return maxBatchRecords;
        }

        public void setMaxBatchRecords(int maxBatchRecords) {
            this.maxBatchRecords = maxBatchRecords;
        }

        public int getSearchDefaultSize() {
            return searchDefaultSize;
        }

        public void setSearchDefaultSize(int searchDefaultSize) {
            this.searchDefaultSize = searchDefaultSize;
        }

        public int getSearchMaxSize() {
            return searchMaxSize;
        }

        public void setSearchMaxSize(int searchMaxSize) {
            this.searchMaxSize = searchMaxSize;
        }

        public long getSearchDefaultRangeMinutes() {
            return searchDefaultRangeMinutes;
        }

        public void setSearchDefaultRangeMinutes(long searchDefaultRangeMinutes) {
            this.searchDefaultRangeMinutes = searchDefaultRangeMinutes;
        }

        public long getSearchMaxRangeDays() {
            return searchMaxRangeDays;
        }

        public void setSearchMaxRangeDays(long searchMaxRangeDays) {
            this.searchMaxRangeDays = searchMaxRangeDays;
        }

        public long getDashboardDefaultRangeMinutes() {
            return dashboardDefaultRangeMinutes;
        }

        public void setDashboardDefaultRangeMinutes(long dashboardDefaultRangeMinutes) {
            this.dashboardDefaultRangeMinutes = dashboardDefaultRangeMinutes;
        }

        public long getDashboardMaxRangeHours() {
            return dashboardMaxRangeHours;
        }

        public void setDashboardMaxRangeHours(long dashboardMaxRangeHours) {
            this.dashboardMaxRangeHours = dashboardMaxRangeHours;
        }

        public long getExportMaxRows() {
            return exportMaxRows;
        }

        public void setExportMaxRows(long exportMaxRows) {
            this.exportMaxRows = exportMaxRows;
        }

        public long getExportMaxRangeHours() {
            return exportMaxRangeHours;
        }

        public void setExportMaxRangeHours(long exportMaxRangeHours) {
            this.exportMaxRangeHours = exportMaxRangeHours;
        }

        public int getExportPageSize() {
            return exportPageSize;
        }

        public void setExportPageSize(int exportPageSize) {
            this.exportPageSize = exportPageSize;
        }

        public int getTraceMaxLogs() {
            return traceMaxLogs;
        }

        public void setTraceMaxLogs(int traceMaxLogs) {
            this.traceMaxLogs = traceMaxLogs;
        }
    }

    public static class Storage {
        private OpenObserve openobserve = new OpenObserve();

        public OpenObserve getOpenobserve() {
            return openobserve;
        }

        public void setOpenobserve(OpenObserve openobserve) {
            this.openobserve = openobserve;
        }
    }

    public static class OpenObserve {
        private String baseUrl = "http://localhost:5080";
        private String organization = "default";
        private String streamName = "trace_logs";
        private String username = "";
        private String password = "";
        /** 可选：设置后使用 Bearer token 而非 Basic 认证 */
        private String token = "";
        private long connectTimeoutMs = 500;
        private long writeTimeoutMs = 5000;
        private long queryTimeoutMs = 10000;
        private long healthTimeoutMs = 2000;
        private Retry retry = new Retry();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getOrganization() {
            return organization;
        }

        public void setOrganization(String organization) {
            this.organization = organization;
        }

        public String getStreamName() {
            return streamName;
        }

        public void setStreamName(String streamName) {
            this.streamName = streamName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getWriteTimeoutMs() {
            return writeTimeoutMs;
        }

        public void setWriteTimeoutMs(long writeTimeoutMs) {
            this.writeTimeoutMs = writeTimeoutMs;
        }

        public long getQueryTimeoutMs() {
            return queryTimeoutMs;
        }

        public void setQueryTimeoutMs(long queryTimeoutMs) {
            this.queryTimeoutMs = queryTimeoutMs;
        }

        public long getHealthTimeoutMs() {
            return healthTimeoutMs;
        }

        public void setHealthTimeoutMs(long healthTimeoutMs) {
            this.healthTimeoutMs = healthTimeoutMs;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private List<Long> backoffMs = List.of(100L, 500L, 2000L);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public List<Long> getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(List<Long> backoffMs) {
            this.backoffMs = backoffMs;
        }
    }
}

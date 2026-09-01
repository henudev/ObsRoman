package com.obsroman.tracelog.security;

import com.obsroman.tracelog.config.TraceLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * API Key 注册表：启动时从配置装载，key 为空白的条目跳过（fail-closed）。
 */
@Component
public class ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);

    public static final String PERM_LOG_WRITE = "log:write";
    public static final String PERM_LOG_READ = "log:read";
    public static final String PERM_TRACE_READ = "trace:read";
    public static final String PERM_DASHBOARD_READ = "dashboard:read";
    public static final String PERM_LOG_EXPORT = "log:export";

    public static final Set<String> ALL_PERMISSIONS = Set.of(
            PERM_LOG_WRITE, PERM_LOG_READ, PERM_TRACE_READ, PERM_DASHBOARD_READ, PERM_LOG_EXPORT);

    private final Map<String, ApiKey> keys = new LinkedHashMap<>();
    private final boolean enabled;

    public ApiKeyRegistry(TraceLogProperties properties) {
        this.enabled = properties.getSecurity().isEnabled();
        List<TraceLogProperties.ApiKeyConfig> configs = properties.getSecurity().getApiKeys();
        for (TraceLogProperties.ApiKeyConfig config : configs) {
            if (config.getKey() == null || config.getKey().isBlank()) {
                continue;
            }
            var permissions = config.getPermissions().stream()
                    .map(p -> p.trim().toLowerCase(Locale.ROOT))
                    .filter(ALL_PERMISSIONS::contains)
                    .collect(java.util.stream.Collectors.toSet());
            String boundService = config.getService() == null || config.getService().isBlank()
                    ? null : config.getService().trim().toLowerCase(Locale.ROOT);
            String boundEnvironment = config.getEnvironment() == null || config.getEnvironment().isBlank()
                    ? null : config.getEnvironment().trim().toLowerCase(Locale.ROOT);
            keys.put(config.getKey().trim(), new ApiKey(
                    config.getName() == null ? "unnamed" : config.getName(),
                    config.getKey().trim(), permissions, boundService, boundEnvironment));
        }
        log.info("api key registry loaded: enabled={}, keys={}", enabled, keys.size());
        if (enabled && keys.isEmpty()) {
            log.warn("security enabled but no api key configured: all business APIs will return 401");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ApiKey findByKey(String key) {
        return key == null ? null : keys.get(key);
    }
}

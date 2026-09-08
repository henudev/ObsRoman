package com.obsroman.tracelog.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.config.TraceLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * API Key 注册表（AK/SK 模型，统一管理员权限，不再细分）。
 * <ul>
 *   <li>启动时始终内置一个管理员默认 Key（{@code admin} / {@code admin}，可通过配置覆盖）。</li>
 *   <li>运行期 Key 持久化到 {@code dataFile}（默认 {@code ./data/api-keys.json}），鉴权不依赖 OpenObserve。</li>
 *   <li>所有 Key 权限一致（可读可写），鉴权仅校验 AK/SK 是否有效且启用。</li>
 * </ul>
 */
@Component
public class ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);

    private static final String AK_PREFIX = "OB-";
    private static final String BUILTIN_ADMIN_NAME = "内置管理员";

    private final ObjectMapper mapper;
    private final String dataFile;
    private final boolean enabled;
    private final String adminAk;
    private final String adminSk;
    private final Map<String, ApiKey> keys = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public ApiKeyRegistry(TraceLogProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.enabled = properties.getSecurity().isEnabled();
        this.dataFile = properties.getSecurity().getDataFile();
        this.adminAk = properties.getSecurity().getAdminAk();
        this.adminSk = properties.getSecurity().getAdminSk();
        seedBuiltinAdmin();
        loadFromFile();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ---------- 解析与鉴权 ----------

    /** AK/SK 鉴权：按 ak 查，校验 sk 且 enabled */
    public ApiKey find(String ak, String sk) {
        if (ak == null || sk == null) {
            return null;
        }
        ApiKey key = keys.get(ak.trim());
        if (key == null || !key.enabled() || key.sk() == null) {
            return null;
        }
        return key.sk().equals(sk) ? key : null;
    }

    /** 旧式单 key 鉴权（无冒号 Bearer）：匹配 legacyKey 且 enabled */
    public ApiKey findByLegacy(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        for (ApiKey key : keys.values()) {
            if (key.enabled() && key.legacyKey() != null && key.legacyKey().equals(t)) {
                return key;
            }
        }
        return null;
    }

    // ---------- 管理 CRUD ----------

    /** 列表（不含 sk / legacyKey） */
    public synchronized List<ApiKeyView> list() {
        return keys.values().stream()
                .sorted(Comparator.comparingLong(ApiKey::createdAt))
                .map(ApiKeyView::from)
                .toList();
    }

    /** 创建：生成 ak + sk，持久化，返回完整 ApiKey（含 sk，仅此一次回传） */
    public synchronized ApiKey create(String name, String service, String environment) {
        String ak = generateAk();
        String sk = generateSk();
        ApiKey key = new ApiKey(name, ak, sk, null,
                normalize(service), normalize(environment), true, System.currentTimeMillis());
        keys.put(ak, key);
        persist();
        log.info("created api key ak={} name={}", ak, name);
        return key;
    }

    /** 更新 enabled / 绑定 / 名称 */
    public synchronized ApiKey update(String ak, Boolean enabled, String service, String environment, String name) {
        ApiKey existing = keys.get(ak);
        if (existing == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "api key not found: " + ak);
        }
        ApiKey updated = new ApiKey(
                name != null && !name.isBlank() ? name.trim() : existing.name(),
                existing.ak(), existing.sk(), existing.legacyKey(),
                service != null ? normalize(service) : existing.boundService(),
                environment != null ? normalize(environment) : existing.boundEnvironment(),
                enabled != null ? enabled : existing.enabled(),
                existing.createdAt());
        keys.put(ak, updated);
        persist();
        return updated;
    }

    /** 删除 */
    public synchronized void delete(String ak) {
        if (keys.remove(ak) != null) {
            persist();
            log.info("deleted api key ak={}", ak);
        }
    }

    // ---------- 装载与持久化 ----------

    private void seedBuiltinAdmin() {
        keys.put(adminAk, new ApiKey(BUILTIN_ADMIN_NAME, adminAk, adminSk, null,
                null, null, true, System.currentTimeMillis()));
    }

    private void loadFromFile() {
        File file = new File(dataFile);
        if (!file.exists()) {
            if (enabled) {
                persist();
            }
            return;
        }
        try {
            List<ApiKey> loaded = mapper.readValue(file, new TypeReference<List<ApiKey>>() {
            });
            for (ApiKey key : loaded) {
                if (key != null && key.ak() != null) {
                    keys.put(key.ak(), key);
                }
            }
            log.info("loaded {} api keys from {}", keys.size(), dataFile);
        } catch (IOException e) {
            log.error("failed to load api keys from {}: {}", dataFile, e.getMessage());
        }
    }

    private void persist() {
        if (!enabled) {
            return;
        }
        try {
            File file = new File(dataFile);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            mapper.writeValue(file, new ArrayList<>(keys.values()));
        } catch (IOException e) {
            log.error("failed to persist api keys to {}: {}", dataFile, e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String generateAk() {
        return AK_PREFIX + hex(random, 16);
    }

    private String generateSk() {
        return hex(random, 32);
    }

    private static String hex(SecureRandom random, int bytes) {
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (int i = 0; i < bytes; i++) {
            sb.append(String.format("%02x", random.nextInt(256)));
        }
        return sb.toString();
    }

    /** API 响应视图：不含 sk / legacyKey */
    public record ApiKeyView(
            String name, String ak,
            String boundService, String boundEnvironment, boolean enabled, long createdAt, boolean legacy) {

        public static ApiKeyView from(ApiKey key) {
            return new ApiKeyView(
                    key.name(), key.ak(),
                    key.boundService(), key.boundEnvironment(), key.enabled(), key.createdAt(),
                    key.legacyKey() != null);
        }
    }
}

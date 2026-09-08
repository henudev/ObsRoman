package com.obsroman.tracelog.controller;

import com.obsroman.tracelog.common.ApiException;
import com.obsroman.tracelog.common.ApiResponse;
import com.obsroman.tracelog.common.ErrorCode;
import com.obsroman.tracelog.security.ApiKey;
import com.obsroman.tracelog.security.ApiKeyRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API Key 管理接口。
 * <p>安全约定：列表与更新返回的视图不包含 {@code sk}；仅创建时一次性返回 {@code ak}/{@code sk}。</p>
 */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyRegistry registry;

    public ApiKeyController(ApiKeyRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public ApiResponse<List<ApiKeyRegistry.ApiKeyView>> list() {
        return ApiResponse.ok(registry.list());
    }

    @PostMapping
    public ApiResponse<CreatedKey> create(@RequestBody CreateKeyRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "name is required");
        }
        ApiKey key = registry.create(request.name().trim(), request.service(), request.environment());
        return ApiResponse.ok(new CreatedKey(
                key.name(), key.ak(), key.sk(), key.boundService(), key.boundEnvironment(), key.enabled()));
    }

    @PutMapping("/{ak}")
    public ApiResponse<ApiKeyRegistry.ApiKeyView> update(@PathVariable String ak,
                                                         @RequestBody UpdateKeyRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "request body is required");
        }
        ApiKey updated = registry.update(ak, request.enabled(), request.service(), request.environment(), request.name());
        return ApiResponse.ok(ApiKeyRegistry.ApiKeyView.from(updated));
    }

    @DeleteMapping("/{ak}")
    public ApiResponse<Void> delete(@PathVariable String ak) {
        registry.delete(ak);
        return ApiResponse.ok();
    }

    /** 创建请求体 */
    public record CreateKeyRequest(String name, String service, String environment) {
    }

    /** 更新请求体（字段可空=不改） */
    public record UpdateKeyRequest(Boolean enabled, String service, String environment, String name) {
    }

    /** 创建结果：sk 仅此一次返回 */
    public record CreatedKey(String name, String ak, String sk, String boundService, String boundEnvironment, boolean enabled) {
    }
}

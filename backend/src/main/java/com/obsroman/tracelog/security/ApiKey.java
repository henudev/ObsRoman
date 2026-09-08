package com.obsroman.tracelog.security;

/**
 * API Key（AK/SK 模型，统一管理员权限）。
 * <p>
 * <ul>
 *   <li>{@code ak}：Access Key，公开标识（唯一）。</li>
 *   <li>{@code sk}：Secret Key，机密凭据，用于 {@code Bearer ak:sk} 鉴权，永不通过 API 回传。</li>
 *   <li>{@code legacyKey}：旧式单 key 令牌（无冒号的 Bearer），用于向后兼容。</li>
 * </ul>
 * 所有 Key 权限一致（可读可写），不再细分权限；写入 Key 可绑定
 * {@code boundService} / {@code boundEnvironment}（null 表示不限制）。
 */
public record ApiKey(
        String name,
        String ak,
        String sk,
        String legacyKey,
        String boundService,
        String boundEnvironment,
        boolean enabled,
        long createdAt) {

    public boolean serviceBound() {
        return boundService != null && !boundService.isBlank();
    }

    public boolean environmentBound() {
        return boundEnvironment != null && !boundEnvironment.isBlank();
    }
}

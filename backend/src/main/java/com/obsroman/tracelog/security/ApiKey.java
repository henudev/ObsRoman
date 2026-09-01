package com.obsroman.tracelog.security;

import java.util.Set;

/**
 * API Key：携带权限集合；写入 Key 可绑定 service / environment（null 表示不限制）。
 */
public record ApiKey(
        String name,
        String key,
        Set<String> permissions,
        String boundService,
        String boundEnvironment) {

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean serviceBound() {
        return boundService != null && !boundService.isBlank();
    }

    public boolean environmentBound() {
        return boundEnvironment != null && !boundEnvironment.isBlank();
    }
}

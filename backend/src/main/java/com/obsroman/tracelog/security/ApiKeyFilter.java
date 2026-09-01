package com.obsroman.tracelog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.common.ApiResponse;
import com.obsroman.tracelog.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 基础鉴权过滤器：Authorization: Bearer &lt;api-key&gt;。
 * - /health、/ready、CORS 预检：公开
 * - 其余按路径映射所需权限（log:write / log:read / trace:read / dashboard:read / log:export）
 * 鉴权失败以统一 ApiResponse 结构返回 401/403。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    /** 请求属性：鉴权通过的 ApiKey */
    public static final String ATTR_API_KEY = "trace-log.api-key";

    private final ApiKeyRegistry registry;
    private final ObjectMapper mapper;

    public ApiKeyFilter(ApiKeyRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return "/health".equals(path) || "/ready".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!registry.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        ApiKey apiKey = authenticate(request);
        if (apiKey == null) {
            writeError(response, ErrorCode.UNAUTHORIZED);
            return;
        }

        String required = requiredPermission(request);
        if (required != null && !apiKey.hasPermission(required)) {
            writeError(response, ErrorCode.FORBIDDEN);
            return;
        }

        request.setAttribute(ATTR_API_KEY, apiKey);
        chain.doFilter(request, response);
    }

    private ApiKey authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String key = header.substring(7).trim();
        return registry.findByKey(key);
    }

    private String requiredPermission(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/traces/")) {
            return ApiKeyRegistry.PERM_TRACE_READ;
        }
        if (path.startsWith("/api/v1/dashboard/")) {
            return ApiKeyRegistry.PERM_DASHBOARD_READ;
        }
        if (path.equals("/api/v1/logs") || path.equals("/api/v1/logs/batch")) {
            return "POST".equals(method) ? ApiKeyRegistry.PERM_LOG_WRITE : null;
        }
        if (path.equals("/api/v1/logs/search")) {
            return ApiKeyRegistry.PERM_LOG_READ;
        }
        if (path.equals("/api/v1/logs/export")) {
            return ApiKeyRegistry.PERM_LOG_EXPORT;
        }
        // 未知路径默认要求登录态（任意权限即通过），最终落到 404
        return null;
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.error(errorCode, errorCode.defaultMessage())));
    }
}

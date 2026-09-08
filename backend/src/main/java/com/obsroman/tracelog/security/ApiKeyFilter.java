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

        // 统一管理员权限：任意有效 Key 均可访问全部业务接口，不再细分权限
        request.setAttribute(ATTR_API_KEY, apiKey);
        chain.doFilter(request, response);
    }

    private ApiKey authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String credential = header.substring(7).trim();
        if (credential.isEmpty()) {
            return null;
        }
        // AK/SK：Bearer <ak>:<sk>；无冒号视为旧式单 key（向后兼容）
        int colon = credential.indexOf(':');
        if (colon >= 0) {
            return registry.find(credential.substring(0, colon).trim(), credential.substring(colon + 1).trim());
        }
        return registry.findByLegacy(credential);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.error(errorCode, errorCode.defaultMessage())));
    }
}

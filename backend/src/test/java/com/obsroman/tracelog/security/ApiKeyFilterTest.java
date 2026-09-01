package com.obsroman.tracelog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.config.TraceLogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    private ApiKeyFilter filter;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TraceLogProperties properties = new TraceLogProperties();
        TraceLogProperties.ApiKeyConfig writer = new TraceLogProperties.ApiKeyConfig();
        writer.setName("writer");
        writer.setKey("writer-key");
        writer.setPermissions(List.of("log:write"));
        writer.setService("order-service");

        TraceLogProperties.ApiKeyConfig reader = new TraceLogProperties.ApiKeyConfig();
        reader.setName("reader");
        reader.setKey("reader-key");
        reader.setPermissions(List.of("log:read", "trace:read", "dashboard:read"));

        TraceLogProperties.ApiKeyConfig blank = new TraceLogProperties.ApiKeyConfig();
        blank.setName("blank");
        blank.setKey("");
        blank.setPermissions(List.of("log:write"));

        properties.getSecurity().getApiKeys().addAll(List.of(writer, reader, blank));
        ApiKeyRegistry registry = new ApiKeyRegistry(properties);
        filter = new ApiKeyFilter(registry, mapper);
    }

    private record Result(int status, Object attr) {
    }

    private Result run(String method, String uri, String authHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        Object attr = request.getAttribute(ApiKeyFilter.ATTR_API_KEY);
        return new Result(response.getStatus(), attr);
    }

    @Test
    void missingKeyIs401() throws Exception {
        assertThat(run("POST", "/api/v1/logs", null).status()).isEqualTo(401);
        assertThat(run("POST", "/api/v1/logs", "Token abc").status()).isEqualTo(401);
        assertThat(run("POST", "/api/v1/logs", "Bearer unknown").status()).isEqualTo(401);
    }

    @Test
    void wrongPermissionIs403() throws Exception {
        assertThat(run("POST", "/api/v1/logs", "Bearer reader-key").status()).isEqualTo(403);
        assertThat(run("POST", "/api/v1/logs/search", "Bearer writer-key").status()).isEqualTo(403);
        assertThat(run("POST", "/api/v1/logs/export", "Bearer reader-key").status()).isEqualTo(403);
        assertThat(run("GET", "/api/v1/traces/abc", "Bearer writer-key").status()).isEqualTo(403);
        assertThat(run("POST", "/api/v1/dashboard/overview", "Bearer writer-key").status()).isEqualTo(403);
    }

    @Test
    void correctPermissionPassesAndExposesApiKey() throws Exception {
        Result result = run("POST", "/api/v1/logs", "Bearer writer-key");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.attr()).isInstanceOf(ApiKey.class);
        assertThat(((ApiKey) result.attr()).boundService()).isEqualTo("order-service");

        assertThat(run("POST", "/api/v1/logs/search", "Bearer reader-key").status()).isEqualTo(200);
        assertThat(run("GET", "/api/v1/traces/abc", "Bearer reader-key").status()).isEqualTo(200);
        assertThat(run("POST", "/api/v1/dashboard/log-trend", "Bearer reader-key").status()).isEqualTo(200);
    }

    @Test
    void blankKeysAreIgnored() throws Exception {
        assertThat(run("POST", "/api/v1/logs", "Bearer ").status()).isEqualTo(401);
    }

    @Test
    void healthAndReadyArePublic() throws Exception {
        assertThat(run("GET", "/health", null).status()).isEqualTo(200);
        assertThat(run("GET", "/ready", null).status()).isEqualTo(200);
    }

    @Test
    void disabledSecurityPassesEverything() throws Exception {
        TraceLogProperties properties = new TraceLogProperties();
        properties.getSecurity().setEnabled(false);
        ApiKeyFilter disabled = new ApiKeyFilter(new ApiKeyRegistry(properties), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/logs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        disabled.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

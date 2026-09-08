package com.obsroman.tracelog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.config.TraceLogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    private ApiKeyFilter filter;
    private final ObjectMapper mapper = new ObjectMapper();
    private ApiKeyRegistry registry;

    @BeforeEach
    void setUp() {
        TraceLogProperties properties = new TraceLogProperties();
        // 测试用唯一临时数据文件，避免污染仓库 ./data/ 与跨用例污染
        properties.getSecurity().setDataFile(System.getProperty("java.io.tmpdir")
                + "/obsroman-keys-" + System.nanoTime() + ".json");
        registry = new ApiKeyRegistry(properties, mapper);
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
        assertThat(run("POST", "/api/v1/logs", "Bearer unknown:sk").status()).isEqualTo(401);
    }

    @Test
    void builtInAdminKeyPasses() throws Exception {
        Result result = run("POST", "/api/v1/logs", "Bearer admin:admin");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.attr()).isInstanceOf(ApiKey.class);
        assertThat(((ApiKey) result.attr()).ak()).isEqualTo("admin");
    }

    @Test
    void anyValidKeyPassesAllEndpoints() throws Exception {
        ApiKey key = registry.create("svc", null, null);
        String bearer = "Bearer " + key.ak() + ":" + key.sk();
        // 统一管理员权限：任意有效 Key 均可访问全部业务接口
        assertThat(run("POST", "/api/v1/logs", bearer).status()).isEqualTo(200);
        assertThat(run("POST", "/api/v1/logs/search", bearer).status()).isEqualTo(200);
        assertThat(run("GET", "/api/v1/traces/abc", bearer).status()).isEqualTo(200);
        assertThat(run("POST", "/api/v1/dashboard/overview", bearer).status()).isEqualTo(200);
        assertThat(run("POST", "/api/v1/logs/export", bearer).status()).isEqualTo(200);
        assertThat(run("GET", "/api/v1/api-keys", bearer).status()).isEqualTo(200);
    }

    @Test
    void wrongSecretIs401() throws Exception {
        ApiKey key = registry.create("svc", null, null);
        assertThat(run("POST", "/api/v1/logs", "Bearer " + key.ak() + ":wrong").status()).isEqualTo(401);
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
        ApiKeyFilter disabled = new ApiKeyFilter(new ApiKeyRegistry(properties, mapper), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/logs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        disabled.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

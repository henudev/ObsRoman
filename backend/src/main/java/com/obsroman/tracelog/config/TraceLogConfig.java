package com.obsroman.tracelog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsroman.tracelog.queue.LogQueue;
import com.obsroman.tracelog.queue.LogWorker;
import com.obsroman.tracelog.security.ApiKeyFilter;
import com.obsroman.tracelog.security.ApiKeyRegistry;
import com.obsroman.tracelog.storage.LogStorage;
import com.obsroman.tracelog.storage.openobserve.OpenObserveClient;
import com.obsroman.tracelog.storage.openobserve.OpenObserveLogStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bean 装配：Storage / Queue / Worker / 鉴权过滤器 / CORS。
 */
@Configuration
public class TraceLogConfig {

    private static final Logger log = LoggerFactory.getLogger(TraceLogConfig.class);

    @Bean
    public OpenObserveClient openObserveClient(ObjectMapper mapper, TraceLogProperties properties) {
        return new OpenObserveClient(mapper, properties.getStorage().getOpenobserve());
    }

    @Bean
    public LogStorage logStorage(OpenObserveClient client, ObjectMapper mapper, TraceLogProperties properties) {
        log.info("log storage: openobserve (base-url={}, org={}, stream={})",
                properties.getStorage().getOpenobserve().getBaseUrl(),
                properties.getStorage().getOpenobserve().getOrganization(),
                properties.getStorage().getOpenobserve().getStreamName());
        return new OpenObserveLogStorage(client, mapper, properties);
    }

    @Bean
    public LogQueue logQueue(TraceLogProperties properties) {
        return new LogQueue(properties.getBuffer().getCapacity());
    }

    @Bean(destroyMethod = "stop")
    public LogWorker logWorker(LogQueue queue, LogStorage storage, TraceLogProperties properties) {
        LogWorker worker = new LogWorker(queue, storage, properties.getBuffer());
        worker.start(properties.getBuffer().getWorkerCount());
        return worker;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeyRegistry registry, ObjectMapper mapper) {
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(registry, mapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("apiKeyFilter");
        return registration;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${trace-log.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Content-Disposition")
                        .maxAge(3600);
            }
        };
    }
}

package com.obsroman.tracelog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TraceLogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceLogServiceApplication.class, args);
    }
}

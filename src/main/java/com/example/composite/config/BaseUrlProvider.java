package com.example.composite.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BaseUrlProvider {

    @Getter
    private String baseUrl = "";

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.address:localhost}")
    private String serverAddress;

    @Value("${base.url.protocol:http}")
    private String protocol;

    private final Environment environment;

    public BaseUrlProvider(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        try {
            String host = System.getenv("HOST");
            String port = System.getenv("PORT");

            if (host == null) {
                host = serverAddress;
                log.debug("Using configured server address: {}", host);
            } else {
                log.debug("Using HOST from environment variable: {}", host);
            }

            if (port == null) {
                port = String.valueOf(serverPort);
                log.debug("Using configured server port: {}", port);
            } else {
                log.debug("Using PORT from environment variable: {}", port);
            }

            // Use HTTPS if specified in properties or if we're in a production environment
            if ("https".equals(protocol) || environment.matchesProfiles("prod")) {
                protocol = "https";
            }

            baseUrl = String.format("%s://%s:%s/", protocol, host, port);
            log.info("Base URL initialized to: {}", baseUrl);
        } catch (Exception e) {
            log.error("Error initializing base URL", e);
            // Set a default value in case of error
            baseUrl = "http://localhost:8080/";
        }
    }
}

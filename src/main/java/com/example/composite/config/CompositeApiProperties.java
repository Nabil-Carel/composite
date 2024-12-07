package com.example.composite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "composite-api")
public class CompositeApiProperties {
    private String scheme = "http";
    private String host = "localhost";
    private int port = 8080;
    
    public String getBaseUrl() {
        return String.format("%s://%s:%d", scheme, host, port);
    }
}

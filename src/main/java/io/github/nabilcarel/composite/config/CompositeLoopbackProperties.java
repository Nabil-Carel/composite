package io.github.nabilcarel.composite.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "composite.loopback")
@Getter
public final class CompositeLoopbackProperties {
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final String protocol;

    public CompositeLoopbackProperties(
            @DefaultValue("PT5S") Duration connectTimeout,
            @DefaultValue("PT10S") Duration responseTimeout,
            @DefaultValue("http") String protocol) {
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.protocol = protocol;
    }
}

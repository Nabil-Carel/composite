package io.github.nabilcarel.composite.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "composite.loopback")
@Getter
@Setter
public final class CompositeLoopbackProperties {
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration responseTimeout = Duration.ofSeconds(10);
    private String protocol = "http";
}

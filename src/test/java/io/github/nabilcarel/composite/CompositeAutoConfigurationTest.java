package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration;
import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeAutoConfigurationTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private Environment environment;

    private CompositeProperties properties;
    private CompositeAutoConfiguration autoConfiguration;

    @BeforeEach
    void setUp() {
        properties = new CompositeProperties();
        autoConfiguration = new CompositeAutoConfiguration(properties, environment);
    }

    @Test
    void objectMapper_createsNewObjectMapper() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = autoConfiguration.objectMapper();

        assertThat(mapper).isNotNull();
    }

    @Test
    void activeBatches_startsEmpty() {
        ActiveBatches activeBatches = autoConfiguration.compositeActiveBatches();

        assertThat(activeBatches.isEmpty()).isTrue();
    }


}

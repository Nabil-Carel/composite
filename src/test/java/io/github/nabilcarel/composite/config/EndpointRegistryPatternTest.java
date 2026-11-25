package io.github.nabilcarel.composite.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.util.pattern.PathPatternParser;

class EndpointRegistryPatternTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/orders/{id:\\d+}|/orders/{id}",
        "/codes/{code:[a-z]{3}}/items/{n}|/codes/{code}/items/{n}",
        "/files/{*path}|/files/{*path}",
        "/a/{x:\\{}|/a/{x}",
        "/plain/path|/plain/path"
    })
    void constraintsAreRemovedFromVariablesOnly(String pattern, String expected) {
        String relaxed = EndpointRegistry.withoutConstraints(pattern);

        assertThat(relaxed).isEqualTo(expected);
        assertThat(PathPatternParser.defaultInstance.parse(relaxed)).isNotNull();
    }
}

package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** An application on AntPathMatcher must not start with composite endpoints that can never match. */
class CompositeAntPathMatcherIntegrationTest {

    @Test
    void antPathMatcher_stopsStartupWithAClearMessage() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CompositeIntegrationTestApplication.class)
                .properties("server.port=0", "spring.mvc.pathmatch.matching-strategy=ant_path_matcher")
                .run()
                .close())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.mvc.pathmatch.matching-strategy=ant_path_matcher");
    }
}

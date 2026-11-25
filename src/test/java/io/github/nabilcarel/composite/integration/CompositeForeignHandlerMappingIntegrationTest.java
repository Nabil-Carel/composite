package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

/**
 * A handler mapping that runs before Spring MVC's, like Actuator's endpoint mapping, can claim
 * a path that also matches an annotated route. The final check must still see the handler it
 * selects and refuse it, because that handler has no {@code @CompositeEndpoint}.
 */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(CompositeForeignHandlerMappingIntegrationTest.ShadowMapping.class)
class CompositeForeignHandlerMappingIntegrationTest {

    static final AtomicInteger SHADOW_CALLS = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @TestConfiguration
    static class ShadowMapping {

        /** Claims /api/adv/items/shadow, which also matches the annotated /api/adv/items/{id}. */
        @Bean
        SimpleUrlHandlerMapping shadowHandlerMapping() {
            HttpRequestHandler handler = (request, response) -> {
                SHADOW_CALLS.incrementAndGet();
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"shadow\":true}");
            };
            return new SimpleUrlHandlerMapping(Map.of("/api/adv/items/shadow", handler), -100);
        }
    }

    @BeforeEach
    void reset() {
        SHADOW_CALLS.set(0);
    }

    @Test
    void handlerFromAnotherMapping_isRefusedInsideABatch() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/adv/items/shadow\"}]}";

        JsonNode response = new ObjectMapper().readTree(
            rest.postForEntity("/api/composite/execute", new HttpEntity<>(body, headers), String.class).getBody());

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(403);
        assertThat(SHADOW_CALLS).hasValue(0);
    }

    @Test
    void handlerFromAnotherMapping_stillServesDirectRequests() {
        assertThat(rest.getForObject("/api/adv/items/shadow", String.class)).contains("shadow");
        assertThat(SHADOW_CALLS).hasValue(1);
    }
}

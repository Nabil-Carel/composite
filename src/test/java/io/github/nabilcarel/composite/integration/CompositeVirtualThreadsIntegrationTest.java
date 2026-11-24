package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runs only with {@code -PvirtualThreads=true} and proves the suite really used virtual threads. */
@EnabledIfSystemProperty(named = "spring.threads.virtual.enabled", matches = "true")
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(CompositeVirtualThreadsIntegrationTest.ThreadController.class)
class CompositeVirtualThreadsIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @TestConfiguration
    @RestController
    static class ThreadController {

        @CompositeEndpoint
        @GetMapping("/api/vt/thread")
        public Map<String, Object> thread() throws Exception {
            Thread current = Thread.currentThread();
            // Thread.isVirtual exists only on Java 21, and this source compiles for Java 17.
            boolean virtual = (boolean) Thread.class.getMethod("isVirtual").invoke(current);
            return Map.of("virtual", virtual, "name", current.getName());
        }
    }

    @Test
    void outerRequestAndSubRequests_runOnVirtualThreads() throws Exception {
        assertThat(rest.getForObject("/api/vt/thread", JsonNode.class).get("virtual").asBoolean()).isTrue();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"subRequests\":["
            + "{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/vt/thread\"},"
            + "{\"referenceId\":\"b\",\"method\":\"GET\",\"url\":\"/api/vt/thread?after=${a.name}\"}]}";
        JsonNode response = new ObjectMapper().readTree(
            rest.postForEntity("/api/composite/execute", new HttpEntity<>(body, headers), String.class).getBody());

        assertThat(response.at("/responses/a/body/virtual").asBoolean()).isTrue();
        assertThat(response.at("/responses/b/body/virtual").asBoolean()).isTrue();
        assertThat(response.at("/responses/b/httpStatus").asInt()).isEqualTo(200);
    }
}

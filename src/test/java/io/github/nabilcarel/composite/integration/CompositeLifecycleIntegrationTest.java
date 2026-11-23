package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestLifecycleConfiguration;
import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import io.github.nabilcarel.composite.model.ResponseTracker;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Verifies that a composite request is accepted before it runs and is always cleaned up. */
class CompositeLifecycleIntegrationTest {

    private static final String EXECUTE = "/api/composite/execute";

    private static String sub(String referenceId, String method, String url, String extra) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"" + method + "\",\"url\":\"" + url + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private static String batch(String... subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String url, String body, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        return rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.security.forwarded-headers=Authorization")
    class Acceptance {

        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @BeforeEach
        void reset() {
            TestSecurityController.reset();
        }

        @Test
        void unsupportedContentType_returns415_andRunsNothing() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE,
                batch(sub("a", "POST", "/api/sec/echo", "\"body\":{\"x\":1}")), MediaType.TEXT_PLAIN);

            Thread.sleep(300);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(TestSecurityController.lastEcho).isNull();
            assertThat(responseStore).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{not json",
            "{}",
            "{\"subRequests\":null}",
            "{\"subRequests\":[]}",
            "{\"subRequests\":[null]}",
            "{\"subRequests\":[{\"referenceId\":\"a\",\"url\":\"/api/sec/secret\"}]}",
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\"}]}",
            "{\"subRequests\":[{\"method\":\"GET\",\"url\":\"/api/sec/secret\"}]}",
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/sec/headers\",\"headers\":{\"Accept\":null}}]}"
        })
        void invalidInput_returns400(String body) {
            ResponseEntity<String> response = post(rest, EXECUTE, body, MediaType.APPLICATION_JSON);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responseStore).isEmpty();
        }

        @Test
        void headersFromEarlierFilters_survive() {
            ResponseEntity<String> response = post(rest, EXECUTE,
                batch(sub("a", "GET", "/api/sec/secret", null)), MediaType.APPLICATION_JSON);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(TestLifecycleConfiguration.FILTER_HEADER))
                .isEqualTo("present");
        }

        @Test
        void completedBatch_leavesNoTracker() {
            post(rest, EXECUTE, batch(sub("a", "GET", "/api/sec/secret", null)), MediaType.APPLICATION_JSON);

            assertThat(responseStore).isEmpty();
        }

        @Test
        void functionalRoute_shadowingAnnotatedPath_isRefused() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE,
                batch(sub("a", "GET", "/api/sec/users/functional", null)), MediaType.APPLICATION_JSON);

            assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
            JsonNode json = new ObjectMapper().readTree(response.getBody());
            assertThat(json.at("/responses/a/httpStatus").asInt()).isEqualTo(403);
        }

        @Test
        void errorFromAnnotatedHandler_keepsItsStatus() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE,
                batch(sub("a", "GET", "/api/sec/missing", null)), MediaType.APPLICATION_JSON);

            JsonNode json = new ObjectMapper().readTree(response.getBody());
            assertThat(json.at("/responses/a/httpStatus").asInt()).isEqualTo(404);
        }

        @Test
        void forwardedCredentials_reachDependentSubRequests() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth("caller-token");
            String body = batch(
                sub("a", "GET", "/api/sec/delay/150", null),
                sub("b", "GET", "/api/sec/headers", "\"headers\":{\"Accept-Language\":\"${a.id}\"}"));

            rest.postForEntity(EXECUTE, new HttpEntity<>(body, headers), String.class);

            assertThat(TestSecurityController.lastHeaders)
                .containsEntry("authorization", "Bearer caller-token");
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "composite.request-timeout=900ms",
        "spring.mvc.async.request-timeout=200ms"
    })
    class Timeouts {

        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @BeforeEach
        void warmUp() {
            post(rest, EXECUTE, batch(sub("warm", "GET", "/api/sec/secret", null)), MediaType.APPLICATION_JSON);
            TestSecurityController.reset();
        }

        @Test
        void subSecondTimeout_allowsFastRequest() {
            ResponseEntity<String> response = post(rest, EXECUTE,
                batch(sub("a", "GET", "/api/sec/secret", null)), MediaType.APPLICATION_JSON);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void compositeTimeout_isNotCutShortByContainerAsyncTimeout() {
            ResponseEntity<String> response = post(rest, EXECUTE,
                batch(sub("a", "GET", "/api/sec/delay/400", null)), MediaType.APPLICATION_JSON);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void timeout_returns504_cancelsDependents_andCleansUp() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE, batch(
                sub("slow", "GET", "/api/sec/delay/1500", null),
                sub("after", "POST", "/api/sec/echo", "\"body\":{\"v\":\"${slow.id}\"}")),
                MediaType.APPLICATION_JSON);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(responseStore).isEmpty();

            Thread.sleep(1000);
            assertThat(TestSecurityController.lastEcho).isNull();
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "composite.base-path=/api/batch",
        "spring.jackson.property-naming-strategy=SNAKE_CASE"
    })
    class HostConfiguration {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void customBasePath_andHostNamingStrategy_keepTheCompositeContract() throws Exception {
            ResponseEntity<String> response = post(rest, "/api/batch/execute",
                batch(sub("a", "GET", "/api/sec/secret", null)), MediaType.APPLICATION_JSON);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode json = new ObjectMapper().readTree(response.getBody());
            assertThat(json.has("hasErrors")).isTrue();
            assertThat(json.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        }
    }
}

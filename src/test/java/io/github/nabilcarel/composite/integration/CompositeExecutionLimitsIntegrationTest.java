package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestLimitsController;
import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import io.github.nabilcarel.composite.model.ResponseTracker;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Verifies batch limits, cancellation and strict request parsing. */
class CompositeExecutionLimitsIntegrationTest {

    private static final String EXECUTE = "/api/composite/execute";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String get(String referenceId, String url) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"" + url + "\"}";
    }

    private static String batch(List<String> subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    private static List<String> workBatch(int size) {
        return IntStream.range(0, size).mapToObj(i -> get("w" + i, "/api/limits/work/" + i)).toList();
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(EXECUTE, new HttpEntity<>(body, headers), String.class);
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.max-concurrent-sub-requests=3")
    class ConfiguredConcurrency {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestLimitsController.reset();
        }

        @Test
        void batchNeverExceedsTheLimit_andEverySubRequestRuns() throws Exception {
            JsonNode response = JSON.readTree(post(rest, batch(workBatch(12))).getBody());

            assertThat(response.get("hasErrors").asBoolean()).isFalse();
            assertThat(response.get("responses")).hasSize(12);
            assertThat(TestLimitsController.COMPLETED).hasValue(12);
            assertThat(TestLimitsController.MAX_ACTIVE.get()).isBetween(2, 3);
        }

        @Test
        void dependentSubRequests_stillRunUnderTheLimit() throws Exception {
            List<String> subRequests = new ArrayList<>(workBatch(4));
            subRequests.add(get("after", "/api/limits/work/${w0.id}"));

            JsonNode response = JSON.readTree(post(rest, batch(subRequests)).getBody());

            assertThat(response.at("/responses/after/httpStatus").asInt()).isEqualTo(200);
            assertThat(TestLimitsController.COMPLETED).hasValue(5);
            assertThat(TestLimitsController.MAX_ACTIVE.get()).isLessThanOrEqualTo(3);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class Defaults {

        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private ApplicationContext context;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @BeforeEach
        void reset() {
            TestLimitsController.reset();
            TestSecurityController.reset();
        }

        @Test
        void defaultConcurrencyLimit_isFive() {
            post(rest, batch(workBatch(12)));

            assertThat(TestLimitsController.COMPLETED).hasValue(12);
            assertThat(TestLimitsController.MAX_ACTIVE.get()).isBetween(2, 5);
        }

        @Test
        void subRequestMayRunLongerThanTenSeconds_whenBatchDeadlineAllows() throws Exception {
            JsonNode response = JSON.readTree(
                post(rest, batch(List.of(get("slow", "/api/sec/delay/10500")))).getBody());

            assertThat(response.at("/responses/slow/httpStatus").asInt()).isEqualTo(200);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{\"allOrNone\":true,\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/limits/work/1\"}]}",
            "{\"subRequest\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/limits/work/1\"}]}",
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"uri\":\"/api/limits/work/1\",\"url\":\"/api/limits/work/1\"}]}"
        })
        void unknownFields_areRejectedBeforeAnythingRuns(String body) throws Exception {
            ResponseEntity<String> response = post(rest, body);

            Thread.sleep(250);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestLimitsController.COMPLETED).hasValue(0);
        }

        @Test
        void healthIndicator_isRegistered() {
            HealthIndicator indicator = context.getBean("compositeHealthIndicator", HealthIndicator.class);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKeys("activeCompositeRequests", "availableEndpoints");
            assertThat((Integer) health.getDetails().get("availableEndpoints")).isPositive();
        }

        @Test
        void subResponse_hasNoHeadersField() throws Exception {
            JsonNode response = JSON.readTree(
                post(rest, batch(List.of(get("a", "/api/sec/secret")))).getBody());

            assertThat(response.at("/responses/a").has("headers")).isFalse();
            assertThat(responseStore).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.loopback.response-timeout=200ms")
    class ExplicitLoopbackTimeout {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void configuredResponseTimeout_isStillHonored() throws Exception {
            JsonNode response = JSON.readTree(
                post(rest, batch(List.of(get("slow", "/api/sec/delay/700")))).getBody());

            assertThat(response.at("/responses/slow/httpStatus").asInt()).isEqualTo(503);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "composite.max-concurrent-sub-requests=1",
        "composite.request-timeout=900ms"
    })
    class QueuedWork {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void warmUp() {
            post(rest, batch(List.of(get("warm", "/api/sec/secret"))));
            TestSecurityController.reset();
        }

        @Test
        void queuedSubRequest_doesNotStartAfterTimeout() throws Exception {
            ResponseEntity<String> response = post(rest, batch(List.of(
                get("slow", "/api/sec/delay/1500"),
                "{\"referenceId\":\"queued\",\"method\":\"POST\",\"url\":\"/api/sec/echo\",\"body\":{\"x\":1}}")));

            Thread.sleep(1000);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(TestSecurityController.lastEcho).isNull();
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.request-timeout=900ms")
    class ClientDisconnect {

        @LocalServerPort
        private int port;
        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @BeforeEach
        void warmUp() {
            post(rest, batch(List.of(get("warm", "/api/sec/secret"))));
            TestSecurityController.reset();
        }

        @Test
        void abandonedBatch_stopsAtTheDeadline_withoutStartingDependents() throws Exception {
            String body = batch(List.of(
                get("slow", "/api/sec/delay/1500"),
                "{\"referenceId\":\"after\",\"method\":\"POST\",\"url\":\"/api/sec/echo\",\"body\":{\"v\":\"${slow.id}\"}}"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream output = socket.getOutputStream();
                output.write(("POST " + EXECUTE + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.flush();
                waitUntil(() -> !responseStore.isEmpty(), 1000);
            }

            // Embedded Tomcat does not report the disconnect, so the batch deadline is the bound.
            waitUntil(responseStore::isEmpty, 1400);
            assertThat(responseStore).isEmpty();

            Thread.sleep(1000);
            assertThat(TestSecurityController.lastEcho).isNull();
        }

        private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        }
    }
}

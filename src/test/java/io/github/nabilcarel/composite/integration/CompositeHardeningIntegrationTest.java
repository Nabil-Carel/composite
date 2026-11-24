package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestAdversarialController;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Covers request bodies, request size limits and forged dispatch headers. */
class CompositeHardeningIntegrationTest {

    private static final String EXECUTE = "/api/composite/execute";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ResponseEntity<String> post(TestRestTemplate rest, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(EXECUTE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String write(String referenceId, String body) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"POST\",\"url\":\"/api/adv/write\",\"body\":"
            + body + "}";
    }

    private static String get(String referenceId, String url) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"" + url + "\"}";
    }

    private static String batch(String... subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class RequestBodies {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestAdversarialController.reset();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "[]", "\"text\"", "\"\"", "5", "0", "1.5", "true", "false"})
        void everyJsonBody_reachesTheHandler(String body) throws Exception {
            JsonNode response = JSON.readTree(post(rest, batch(write("w", body))).getBody());

            assertThat(response.at("/responses/w/httpStatus").asInt()).isEqualTo(200);
            assertThat(JSON.readTree(TestAdversarialController.lastWrite)).isEqualTo(JSON.readTree(body));
        }

        @Test
        void wholeBodyReference_isResolved() throws Exception {
            JsonNode response = JSON.readTree(post(rest, batch(
                get("v", "/api/adv/values"),
                write("w", "\"${v.sql}\""))).getBody());

            assertThat(response.at("/responses/w/httpStatus").asInt()).isEqualTo(200);
            assertThat(JSON.readTree(TestAdversarialController.lastWrite).asText()).isEqualTo("1' OR '1'='1");
        }

        @Test
        void wholeBodyReference_runsAfterItsDependency() throws Exception {
            JsonNode response = JSON.readTree(post(rest, batch(
                write("w", "\"${v.hidden}\""),
                get("v", "/api/adv/values"))).getBody());

            assertThat(response.at("/responses/w/httpStatus").asInt()).isEqualTo(200);
            assertThat(JSON.readTree(TestAdversarialController.lastWrite).asText()).isEqualTo("hidden");
        }

        @Test
        void nullBody_isStillRejected() {
            ResponseEntity<String> response = post(rest, batch(write("w", "null")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.max-request-size=64KB")
    class RequestSize {

        @LocalServerPort
        private int port;
        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestAdversarialController.reset();
        }

        private String oversized() {
            StringBuilder fields = new StringBuilder();
            for (int i = 0; i < 12_000; i++) {
                fields.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":1");
            }
            return batch(write("w", "{" + fields + "}"));
        }

        @Test
        void bodyUnderTheLimit_runs() {
            assertThat(post(rest, batch(write("w", "{\"x\":1}"))).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(TestAdversarialController.WRITES).hasValue(1);
        }

        @Test
        void bodyOverTheLimit_isRejectedBeforeAnythingRuns() throws Exception {
            ResponseEntity<String> response = post(rest, oversized());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(JSON.readTree(response.getBody()).at("/errors/0").asText()).contains("too large");
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        @Test
        void chunkedBodyOverTheLimit_isRejected() throws Exception {
            byte[] bytes = oversized().getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + EXECUTE))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.loopback.max-connections=1")
    class ForgedDispatchHeader {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestAdversarialController.reset();
        }

        @Test
        void forgedHeader_cannotConsumeTheHandlerLimit() throws Exception {
            HttpHeaders forged = new HttpHeaders();
            forged.set("X-Composite-Dispatch", "true");
            CompletableFuture<ResponseEntity<String>> slow = CompletableFuture.supplyAsync(() ->
                rest.exchange("/api/adv/slow/900", HttpMethod.GET, new HttpEntity<>(forged), String.class));
            long deadline = System.currentTimeMillis() + 2000;
            while (TestAdversarialController.ACTIVE.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            JsonNode response = JSON.readTree(post(rest, batch(get("a", "/api/adv/items/1"))).getBody());

            assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
            assertThat(slow.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void forgedHeader_isTreatedLikeANormalDirectRequest() {
            HttpHeaders forged = new HttpHeaders();
            forged.set("X-Composite-Dispatch", "true");

            ResponseEntity<String> response = rest.exchange("/api/adv/hidden", HttpMethod.GET,
                new HttpEntity<>(forged), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}

package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import io.github.nabilcarel.composite.model.ResponseTracker;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Verifies that {@code stopOnFailure} stops remaining work without claiming to roll back. */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CompositeStopOnFailureIntegrationTest {

    private static final String FAIL = get("fail", "/api/sec/missing");
    private static final String SLOW = get("slow", "/api/sec/delay/700");
    private static final String AFTER_SLOW =
        "{\"referenceId\":\"after\",\"method\":\"POST\",\"url\":\"/api/sec/echo\",\"body\":{\"v\":\"${slow.id}\"}}";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ConcurrentMap<String, ResponseTracker> responseStore;

    @BeforeEach
    void warmUp() {
        execute("false", get("warm", "/api/sec/secret"));
        TestSecurityController.reset();
    }

    private static String get(String referenceId, String url) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"" + url + "\"}";
    }

    private ResponseEntity<String> execute(String stopOnFailure, String... subRequests) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"stopOnFailure\":" + stopOnFailure + ",\"subRequests\":["
            + String.join(",", subRequests) + "]}";
        return rest.postForEntity("/api/composite/execute", new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return new ObjectMapper().readTree(response.getBody());
    }

    @Test
    void failure_cancelsRunningWork_andSkipsUnstartedWork() throws Exception {
        long started = System.nanoTime();
        ResponseEntity<String> response = execute("true", SLOW, AFTER_SLOW, FAIL);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        JsonNode result = json(response);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.get("hasErrors").asBoolean()).isTrue();
        assertThat(result.at("/responses/fail/httpStatus").asInt()).isEqualTo(404);
        assertThat(result.at("/responses/slow/httpStatus").asInt()).isEqualTo(503);
        assertThat(result.at("/responses/slow/body").asText()).contains("may have already");
        assertThat(result.at("/responses/after/httpStatus").asInt()).isEqualTo(424);
        assertThat(result.at("/responses/after/body").asText()).contains("Not executed");
        assertThat(elapsedMillis).isLessThan(650);
        assertThat(responseStore).isEmpty();

        Thread.sleep(900);
        assertThat(TestSecurityController.lastEcho).isNull();
    }

    @Test
    void withoutTheOption_independentWorkContinues() throws Exception {
        JsonNode result = json(execute("false", SLOW, AFTER_SLOW, FAIL));

        assertThat(result.at("/responses/fail/httpStatus").asInt()).isEqualTo(404);
        assertThat(result.at("/responses/slow/httpStatus").asInt()).isEqualTo(200);
        assertThat(result.at("/responses/after/httpStatus").asInt()).isEqualTo(200);
        assertThat(TestSecurityController.lastEcho).isNotNull();
    }

    @Test
    void successfulBatch_isUnaffected() throws Exception {
        JsonNode result = json(execute("true", get("a", "/api/sec/secret"), get("b", "/api/sec/stored")));

        assertThat(result.get("hasErrors").asBoolean()).isFalse();
        assertThat(result.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        assertThat(result.at("/responses/b/httpStatus").asInt()).isEqualTo(200);
    }

    @Test
    void completedWork_isReportedAndNotRolledBack() throws Exception {
        String write = "{\"referenceId\":\"write\",\"method\":\"POST\",\"url\":\"/api/sec/echo\",\"body\":{\"x\":1}}";
        String failAfterWrite = "{\"referenceId\":\"fail\",\"method\":\"GET\",\"url\":\"/api/sec/missing?after=${write.x}\"}";

        JsonNode result = json(execute("true", write, failAfterWrite));

        assertThat(result.at("/responses/write/httpStatus").asInt()).isEqualTo(200);
        assertThat(result.at("/responses/fail/httpStatus").asInt()).isEqualTo(404);
        assertThat(TestSecurityController.lastEcho).containsEntry("x", 1);
    }
}

package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Verifies the JFR events and that they never contain request data. */
class CompositeJfrEventsIntegrationTest {

    private static final String BATCH = "io.github.nabilcarel.composite.Batch";
    private static final String SUB_REQUEST = "io.github.nabilcarel.composite.SubRequest";

    private static String get(String referenceId, String url) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"" + url + "\"}";
    }

    private static List<RecordedEvent> record(TestRestTemplate rest, HttpHeaders headers, String body)
            throws Exception {
        Path file = Files.createTempFile("composite-events", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(BATCH).withThreshold(java.time.Duration.ZERO);
            recording.enable(SUB_REQUEST).withThreshold(java.time.Duration.ZERO);
            recording.start();
            headers.setContentType(MediaType.APPLICATION_JSON);
            rest.postForEntity("/api/composite/execute", new HttpEntity<>(body, headers), String.class);
            Thread.sleep(250);
            recording.stop();
            recording.dump(file);
            return RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().startsWith("io.github.nabilcarel.composite."))
                .toList();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static List<RecordedEvent> ofType(List<RecordedEvent> events, String type) {
        return events.stream().filter(event -> event.getEventType().getName().equals(type)).toList();
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class Events {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestSecurityController.reset();
        }

        @Test
        void successfulBatch_recordsBatchAndSubRequests() throws Exception {
            List<RecordedEvent> events = record(rest, new HttpHeaders(), "{\"subRequests\":["
                + get("a", "/api/sec/users/secret-user-id?token=query-secret") + ","
                + get("b", "/api/sec/users/${a.id}") + "]}");

            RecordedEvent batch = ofType(events, BATCH).get(0);
            List<RecordedEvent> subRequests = ofType(events, SUB_REQUEST);

            assertThat(ofType(events, BATCH)).hasSize(1);
            assertThat(batch.getString("outcome")).isEqualTo("SUCCESS");
            assertThat(batch.getInt("subRequests")).isEqualTo(2);
            assertThat(batch.getInt("failedSubRequests")).isZero();
            assertThat(subRequests).hasSize(2).allSatisfy(event -> {
                assertThat(event.getString("batchId")).isEqualTo(batch.getString("batchId"));
                assertThat(event.getString("method")).isEqualTo("GET");
                assertThat(event.getString("endpoint")).isEqualTo("/api/sec/users/{id}");
                assertThat(event.getInt("status")).isEqualTo(200);
                assertThat(event.getString("outcome")).isEqualTo("COMPLETED");
                assertThat(event.getDuration().isNegative()).isFalse();
            });
        }

        @Test
        void events_neverContainUrlsHeadersOrBodies() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth("authorization-secret");

            List<RecordedEvent> events = record(rest, headers, "{\"subRequests\":["
                + get("a", "/api/sec/users/secret-user-id?token=query-secret") + ","
                + "{\"referenceId\":\"b\",\"method\":\"POST\",\"url\":\"/api/sec/echo\","
                + "\"body\":{\"value\":\"body-secret\"}}]}");

            assertThat(events).isNotEmpty();
            assertThat(events.toString())
                .doesNotContain("secret-user-id", "query-secret", "authorization-secret", "body-secret");
        }

        @Test
        void failedSubRequest_isRecordedAsPartial() throws Exception {
            List<RecordedEvent> events = record(rest, new HttpHeaders(), "{\"subRequests\":["
                + get("bad", "/api/sec/missing") + "," + get("good", "/api/sec/secret") + "]}");

            RecordedEvent batch = ofType(events, BATCH).get(0);
            RecordedEvent failed = ofType(events, SUB_REQUEST).stream()
                .filter(event -> "bad".equals(event.getString("referenceId"))).findFirst().orElseThrow();

            assertThat(batch.getString("outcome")).isEqualTo("PARTIAL");
            assertThat(batch.getInt("failedSubRequests")).isEqualTo(1);
            assertThat(failed.getInt("status")).isEqualTo(404);
            assertThat(failed.getString("outcome")).isEqualTo("FAILED");
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    // The sub-request timeout is longer than the batch deadline, so only the batch can time out.
    @TestPropertySource(properties = {"composite.request-timeout=500ms", "composite.sub-request-timeout=30s"})
    class Timeouts {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void timedOutBatch_recordsCancelledSubRequest() throws Exception {
            HttpHeaders warmUp = new HttpHeaders();
            warmUp.setContentType(MediaType.APPLICATION_JSON);
            rest.postForEntity("/api/composite/execute",
                new HttpEntity<>("{\"subRequests\":[" + get("warm", "/api/sec/secret") + "]}", warmUp), String.class);

            List<RecordedEvent> events = record(rest, new HttpHeaders(),
                "{\"subRequests\":[" + get("slow", "/api/sec/delay/1200") + "]}");

            assertThat(ofType(events, BATCH).get(0).getString("outcome")).isEqualTo("TIMEOUT");
            assertThat(ofType(events, SUB_REQUEST).get(0).getString("outcome")).isEqualTo("CANCELLED");
        }
    }
}

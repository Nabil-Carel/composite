package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves that sub-request handlers always return their permit, including handlers that go
 * async and fail later. Only two handlers may run at once, so one leaked permit per batch
 * would turn later sub-requests into {@code 503}.
 */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = "composite.loopback.max-connections=2")
class CompositeHandlerPermitIntegrationTest {

    private static final int BATCHES = 10;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void asyncHandlerThatFails_releasesItsPermit() throws Exception {
        assertEveryBatchReturns("async-fail", 500);
    }

    @Test
    void asyncHandlerThatSucceeds_releasesItsPermit() throws Exception {
        assertEveryBatchReturns("async-ok", 200);
    }

    @Test
    void handlerThatThrows_releasesItsPermit() throws Exception {
        assertEveryBatchReturns("throw", 500);
    }

    private void assertEveryBatchReturns(String endpoint, int status) throws Exception {
        String subRequest = "{\"referenceId\":\"%s\",\"method\":\"GET\",\"url\":\"/api/limits/" + endpoint + "\"}";
        String body = "{\"subRequests\":[" + subRequest.formatted("a") + "," + subRequest.formatted("b") + "]}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (int batch = 0; batch < BATCHES; batch++) {
            JsonNode response = new ObjectMapper().readTree(
                rest.postForEntity("/api/composite/execute", new HttpEntity<>(body, headers), String.class).getBody());

            assertThat(response.at("/responses/a/httpStatus").asInt()).as("batch %d, a", batch).isEqualTo(status);
            assertThat(response.at("/responses/b/httpStatus").asInt()).as("batch %d, b", batch).isEqualTo(status);
        }
    }
}

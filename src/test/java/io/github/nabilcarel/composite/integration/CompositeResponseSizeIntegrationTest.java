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

/** Verifies that the sub-response buffer limit is configurable. */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = "composite.loopback.max-response-size=512KB")
class CompositeResponseSizeIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void raisedLimit_allowsLargerResponse() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/json/large\"}]}";

        JsonNode response = new ObjectMapper().readTree(rest.postForEntity(
            "/api/composite/execute", new HttpEntity<>(body, headers), String.class).getBody());

        assertThat(response.get("hasErrors").asBoolean()).isFalse();
        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        assertThat(response.at("/responses/a/body/value").asText()).hasSize(300 * 1024);
    }
}

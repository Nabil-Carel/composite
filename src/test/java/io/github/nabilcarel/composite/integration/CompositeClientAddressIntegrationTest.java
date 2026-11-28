package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Verifies that sub-requests see the client the outer request came from. The test client
 * connects from 127.0.0.1, which Tomcat trusts as a proxy, so it acts as the proxy here.
 */
class CompositeClientAddressIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode client(TestRestTemplate rest) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "203.0.113.7");
        headers.set("X-Forwarded-Proto", "https");
        headers.set("X-Forwarded-Host", "shop.example.com");
        String body = "{\"subRequests\":[{\"referenceId\":\"c\",\"method\":\"GET\",\"url\":\"/api/json/client\"}]}";

        JsonNode response = JSON.readTree(rest.postForEntity("/api/composite/execute",
            new HttpEntity<>(body, headers), String.class).getBody());
        assertThat(response.at("/responses/c/httpStatus").asInt()).isEqualTo(200);
        return response.at("/responses/c/body");
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native"
    )
    class BehindAProxy {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void subRequest_seesTheClientOfTheOuterRequest() throws Exception {
            JsonNode client = client(rest);

            assertThat(client.get("remoteAddr").asText()).isEqualTo("203.0.113.7");
            assertThat(client.get("scheme").asText()).isEqualTo("https");
            assertThat(client.get("host").asText()).isEqualTo("shop.example.com");
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.forward-headers-strategy=native",
            "composite.security.forward-client-address=false"
        }
    )
    class ForwardingDisabled {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void subRequest_seesTheLoopbackAddress() throws Exception {
            JsonNode client = client(rest);

            assertThat(client.get("remoteAddr").asText()).isEqualTo("127.0.0.1");
            assertThat(client.get("scheme").asText()).isEqualTo("http");
        }
    }
}

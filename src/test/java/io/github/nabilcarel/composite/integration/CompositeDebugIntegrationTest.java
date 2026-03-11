package io.github.nabilcarel.composite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "composite.base-path=/api/composite",
    "composite.controller.enabled=true",
    "composite.debug-enabled=true"
})
class CompositeDebugIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testDebugEnabled_returnsDebugInfoWithDependencyGraph() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}/orders")
                    .referenceId("orders1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDebug()).isNotNull();

        // Verify dependency graph
        assertThat(response.getBody().getDebug().getDependencyGraph()).isNotNull();
        assertThat(response.getBody().getDebug().getDependencyGraph()).containsKey("user1");
        assertThat(response.getBody().getDebug().getDependencyGraph()).containsKey("orders1");
        assertThat(response.getBody().getDebug().getDependencyGraph().get("user1")).isEmpty();
        assertThat(response.getBody().getDebug().getDependencyGraph().get("orders1")).contains("user1");

        // Verify resolved requests
        assertThat(response.getBody().getDebug().getResolvedRequests()).isNotNull();
        assertThat(response.getBody().getDebug().getResolvedRequests()).containsKey("user1");
        assertThat(response.getBody().getDebug().getResolvedRequests()).containsKey("orders1");

        // Verify original vs resolved URLs for dependent request
        var orders1Debug = response.getBody().getDebug().getResolvedRequests().get("orders1");
        assertThat(orders1Debug.getOriginalUrl()).isEqualTo("/api/users/${user1.id}/orders");
        assertThat(orders1Debug.getResolvedUrl()).doesNotContain("${");
    }

    @Test
    void testDebugEnabled_withBodyReference_capturesOriginalAndResolvedBody() throws Exception {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("POST")
                    .url("/api/orders")
                    .referenceId("order1")
                    .body(objectMapper.readTree(
                        "{\"userId\": \"${user1.id}\", \"amount\": 100.0}"
                    ))
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDebug()).isNotNull();

        var order1Debug = response.getBody().getDebug().getResolvedRequests().get("order1");
        assertThat(order1Debug).isNotNull();
        assertThat(order1Debug.getOriginalBody()).isNotNull();
        assertThat(order1Debug.getOriginalBody().toString()).contains("${user1.id}");
        // Resolved body should have the placeholder replaced
        assertThat(order1Debug.getResolvedBody()).isNotNull();
        assertThat(order1Debug.getResolvedBody().toString()).doesNotContain("${");
    }

    @Test
    void testDebugEnabled_independentRequests_showsEmptyDependencies() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/products/prod1")
                    .referenceId("prod1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDebug()).isNotNull();
        assertThat(response.getBody().getDebug().getDependencyGraph().get("user1")).isEmpty();
        assertThat(response.getBody().getDebug().getDependencyGraph().get("prod1")).isEmpty();
    }

    @Test
    void testDebugInfo_doesNotContainHeaders() throws Exception {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .headers(Map.of("Authorization", "Bearer secret-token"))
                    .build()
            ))
            .build();

        ResponseEntity<String> response = executeCompositeRequestAsString(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Parse the raw JSON to verify no header-related fields in debug
        String json = response.getBody();
        assertThat(json).contains("debug");
        assertThat(json).contains("resolvedRequests");
        // The debug sub-request info should NOT contain any header fields
        var parsed = objectMapper.readTree(json);
        var user1Debug = parsed.at("/debug/resolvedRequests/user1");
        assertThat(user1Debug.has("originalHeaders")).isFalse();
        assertThat(user1Debug.has("resolvedHeaders")).isFalse();
        assertThat(user1Debug.has("headers")).isFalse();
    }

    private ResponseEntity<CompositeResponse> executeCompositeRequest(CompositeRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CompositeRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.exchange(
            "/api/composite/execute",
            HttpMethod.POST,
            entity,
            CompositeResponse.class
        );
    }

    private ResponseEntity<String> executeCompositeRequestAsString(CompositeRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CompositeRequest> entity = new HttpEntity<>(request, headers);

        return restTemplate.exchange(
            "/api/composite/execute",
            HttpMethod.POST,
            entity,
            String.class
        );
    }
}

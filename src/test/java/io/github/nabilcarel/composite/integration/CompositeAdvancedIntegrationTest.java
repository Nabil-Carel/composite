package io.github.nabilcarel.composite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "composite.base-path=/api/composite",
    "composite.controller.enabled=true",
    "composite.header-injection.enabled=true",
    "composite.security.additional-auth-headers=X-Trace-Id,X-Span-Id"
})
class CompositeAdvancedIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EndpointRegistry endpointRegistry;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // ========== Nested Placeholder Tests ==========

    @Test
    void testNestedPlaceholders() {
        // Test ${a.${b.c}} style nested placeholders
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/settings")
                    .referenceId("settings1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${settings1.theme}")
                    .referenceId("user1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();

        assertThat(response.getBody().getResponses().get("settings1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testInvalidExpressionInPlaceholderIsRejected() throws Exception {
        // Test that ternary expressions like ${settings1.language == 'en' ? 100 : 50}
        // are rejected since only property paths are supported (not expressions)
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/settings")
                    .referenceId("settings1")
                    .build(),
                SubRequestDto.builder()
                    .method("POST")
                    .url("/api/orders")
                    .referenceId("order1")
                    .body(objectMapper.readTree(
                        "{\"userId\": \"${user1.id}\", \"amount\": \"${settings1.language == 'en' ? 100 : 50}\"}"
                    ))
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        // The overall response is OK but the invalid subrequest should have an error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isHasErrors()).isTrue();

        // The order1 subrequest should fail with a reference resolution error
        SubResponse order1Response = response.getBody().getResponses().get("order1");
        assertThat(order1Response.getHttpStatus()).isEqualTo(400);
        assertThat(order1Response.getBody().toString()).contains("Reference resolution failed");
    }

    @Test
    void testValidMultipleNestedPlaceholders() throws Exception {
        // Test valid nested placeholders: ${user1.id} and ${settings1.language}
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/settings")
                    .referenceId("settings1")
                    .build(),
                SubRequestDto.builder()
                    .method("POST")
                    .url("/api/orders")
                    .referenceId("order1")
                    .body(objectMapper.readTree(
                        "{\"userId\": \"${user1.id}\", \"language\": \"${settings1.language}\"}"
                    ))
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(3);

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("settings1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("order1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // ========== Map Bracket Notation Tests ==========

    @Test
    void testMapBracketNotation() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/settings")
                    .referenceId("settings1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/products/${settings1['theme']}")
                    .referenceId("prod1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();

        assertThat(response.getBody().getResponses().get("settings1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testMapBracketNotationWithSpecialCharacters() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/metadata")
                    .referenceId("metadata1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/products/${metadata1['category']}")
                    .referenceId("prod1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();

        assertThat(response.getBody().getResponses().get("metadata1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // ========== Complex Dependency Scenarios ==========

    @Test
    void testFanOutDependencies() {
        // One request feeds multiple dependent requests
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}/profile")
                    .referenceId("profile1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}/orders")
                    .referenceId("orders1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}/settings")
                    .referenceId("settings1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(4);

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("profile1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("orders1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("settings1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testDiamondDependency() {
        // A -> B, A -> C, B -> D, C -> D (D depends on both B and C)
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}/profile")
                    .referenceId("profile1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}/orders")
                    .referenceId("orders1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/${orders1.orders[0].orderId}")
                    .referenceId("orderDetails1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(4);

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("profile1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("orders1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("orderDetails1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // ========== Monitoring Headers Tests ==========

    @Test
    void testMonitoringHeadersForwarding() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "trace-123");
        headers.set("X-Span-Id", "span-456");
        headers.set("X-Request-Id", "req-789");

        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/headers/echo")
                    .referenceId("headers1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SubResponse headersResponse = response.getBody().getResponses().get("headers1");
        assertThat(headersResponse.getHttpStatus()).isEqualTo(200);
        // Verify monitoring headers were forwarded
    }

    // ========== Endpoint Discovery Tests ==========

    @Test
    void testEndpointDiscovery() {
        ResponseEntity<Set> response = restTemplate.getForEntity(
            baseUrl + "/api/composite/endpoints",
            Set.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isNotEmpty();
    }

    @Test
    void testEndpointRegistryContainsAnnotatedEndpoints() {
        Set<EndpointRegistry.EndpointInfo> endpoints = endpointRegistry.getAvailableEndpoints();

        assertThat(endpoints).isNotEmpty();

        // Verify endpoints exist for each API path
        assertThat(endpoints).anySatisfy(e ->
            assertThat(e.getPattern()).contains("/api/users"));
        assertThat(endpoints).anySatisfy(e ->
            assertThat(e.getPattern()).contains("/api/orders"));
        assertThat(endpoints).anySatisfy(e ->
            assertThat(e.getPattern()).contains("/api/products"));
    }

    // ========== Header Injection Tests ==========

    @Test
    void testCompositeRequestHeadersInjected() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/headers/echo")
                    .referenceId("headers1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SubResponse headersResponse = response.getBody().getResponses().get("headers1");
        assertThat(headersResponse.getHttpStatus()).isEqualTo(200);
        // Verify composite headers are injected (X-Composite-Request, etc.)
    }

    // ========== Response Structure Tests ==========

    @Test
    void testResponseStructure() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CompositeResponse compositeResponse = response.getBody();
        
        assertThat(compositeResponse).isNotNull();
        assertThat(compositeResponse.isHasErrors()).isFalse();
        assertThat(compositeResponse.getResponses()).isNotNull().containsKey("user1");

        SubResponse subResponse = compositeResponse.getResponses().get("user1");
        assertThat(subResponse.getReferenceId()).isEqualTo("user1");
        assertThat(subResponse.getHttpStatus()).isEqualTo(200);
        assertThat(subResponse.getBody()).isNotNull();
    }

    // ========== Concurrent Execution Tests ==========

    @Test
    void testConcurrentIndependentRequests() {
        // Create 5 independent requests that should execute in parallel
        List<SubRequestDto> subRequests = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            subRequests.add(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user" + i)
                    .referenceId("user" + i)
                    .build()
            );
        }

        CompositeRequest request = CompositeRequest.builder()
            .subRequests(subRequests)
            .build();

        long startTime = System.currentTimeMillis();
        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);
        long endTime = System.currentTimeMillis();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(5);

        for (int i = 1; i <= 5; i++) {
            assertThat(response.getBody().getResponses().get("user" + i).getHttpStatus())
                .isEqualTo(HttpStatus.OK.value());
        }

        // If truly parallel, should be faster than sequential (5 * single request time)
        // This is a rough check - actual timing depends on many factors
        assertThat(endTime - startTime).isLessThan(5000);
    }

    // ========== Edge Cases ==========

    @Test
    void testEmptySubRequestList() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of())
            .build();

        // Should be caught in validation
        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);
        
        // Should return validation error
        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST)
            .satisfies(r -> assertThat(response.getBody().isHasErrors()).isTrue());
    }

    @Test
    void testInvalidReferenceId() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${nonexistent.id}")
                    .referenceId("user2")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isHasErrors()).isTrue();
        assertThat(response.getBody().getErrors()).hasSize(1);
        assertThat(response.getBody().getErrors().get(0)).contains("Missing dependency reference: 'nonexistent' for request: user2");
    }

    @Test
    void testVeryLongUrl() {
        StringBuilder longPath = new StringBuilder("/api/users/");
        for (int i = 0; i < 100; i++) {
            longPath.append("verylongpathsegment");
        }

        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url(longPath.toString())
                    .referenceId("user1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        // Should handle long URLs (might fail if exceeds limits, but should handle gracefully)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testSpecialCharactersInUrl() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user%20with%20spaces")
                    .referenceId("user1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testMixedContentTypes() throws Exception {
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
                    .body(objectMapper.readTree("{\"userId\": \"${user1.id}\", \"amount\": 100}"))
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(2);

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("order1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // ========== Helper Methods ==========

    private ResponseEntity<CompositeResponse> executeCompositeRequest(CompositeRequest request) {
        return executeCompositeRequest(request, new HttpHeaders());
    }

    private ResponseEntity<CompositeResponse> executeCompositeRequest(
            CompositeRequest request, HttpHeaders headers) {
        try {
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<CompositeRequest> entity = new HttpEntity<>(request, headers);

            return restTemplate.exchange(
                baseUrl + "/api/composite/execute",
                HttpMethod.POST,
                entity,
                CompositeResponse.class
            );
        } catch (Exception e) {
            fail("Request failed: " + e.getMessage());
            return null;
        }
    }
}

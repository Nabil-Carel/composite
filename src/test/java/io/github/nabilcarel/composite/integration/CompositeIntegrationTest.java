package io.github.nabilcarel.composite.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
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
    "composite.header-injection.enabled=true"
})
class CompositeIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== Happy Path Tests ==========

    @Test
    void testSingleIndependentRequest() {
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
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).isNotNull().hasSize(1);

        assertThat(response.getBody().getResponses()).isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("user1");
        SubResponse subResponse = response.getBody().getResponses().get("user1");
        assertThat(subResponse.getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(subResponse.getBody()).isNotNull();
    }

    @Test
    void testParallelIndependentRequests() {
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
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/order1")
                    .referenceId("order1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(3)
            .containsKeys("user1", "prod1", "order1");

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("order1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testSequentialDependentRequests() {
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
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(2);

        SubResponse userResponse = response.getBody().getResponses().get("user1");
        assertThat(userResponse.getHttpStatus()).isEqualTo(200);

        SubResponse ordersResponse = response.getBody().getResponses().get("orders1");
        assertThat(ordersResponse.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void testComplexDependencyChain() {
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

    // ========== Reference Resolution Tests ==========

    @Test
    void testDotNotationPropertyAccess() {
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
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).containsKeys("user1", "profile1");

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("profile1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testArrayIndexAccess() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/orders")
                    .referenceId("orders1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/${orders1.orders[0].orderId}")
                    .referenceId("order1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).containsKeys("orders1", "order1");

        assertThat(response.getBody().getResponses().get("orders1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("order1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testNestedPropertyAccess() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1/nested")
                    .referenceId("nested1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/products/${nested1.inner.deep.value}")
                    .referenceId("prod1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();

        assertThat(response.getBody().getResponses().get("nested1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testReferenceInRequestBody() throws Exception {
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
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).containsKeys("user1", "order1");

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("order1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testReferenceInHeaders() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/headers/custom")
                    .referenceId("headers1")
                    .headers(Map.of("X-Custom-Header", "${user1.id}"))
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).containsKeys("user1", "headers1");

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("headers1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // ========== HTTP Methods Tests ==========

    @Test
    void testPostRequest() throws Exception {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("POST")
                    .url("/api/users")
                    .referenceId("user1")
                    .body(objectMapper.readTree(
                        "{\"id\": \"newuser\", \"name\": \"New User\", \"email\": \"new@example.com\"}"
                    ))
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .extracting(CompositeResponse::getResponses)
                .isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("user1");
        SubResponse userResponse = response.getBody().getResponses().get("user1");
        assertThat(userResponse.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void testGetRequestWithQueryParams() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/products")
                    .referenceId("products1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response)
                .isNotNull()
                .extracting(ResponseEntity::getStatusCode)
                .isNotNull();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(response.getBody()).isNotNull();
        SubResponse productsResponse = response.getBody().getResponses().get("products1");
        assertThat(productsResponse.getHttpStatus()).isEqualTo(200);
    }

    // ========== Error Handling Tests ==========

    @Test
    void testInvalidEndpoint() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/nonexistent/endpoint")
                    .referenceId("invalid1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isTrue();
        assertThat(response.getBody().getErrors()).isNotEmpty()
            .anySatisfy(error -> assertThat(error).contains("Endpoint not available for composite execution"));
    }

    @Test
    void testDependentRequestOnFailedDependency() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/notfound/missing")
                    .referenceId("order1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/${order1.orderId}/items")
                    .referenceId("items1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .extracting(CompositeResponse::getResponses)
                .isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("order1");
        SubResponse orderResponse = response.getBody().getResponses().get("order1");

        if (orderResponse.getHttpStatus() >= 400) {
            SubResponse itemsResponse = response.getBody().getResponses().get("items1");
            assertThat(itemsResponse.getHttpStatus()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void testCircularDependencyValidation() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user2.id}")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/${user1.id}")
                    .referenceId("user2")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
            .satisfies(r -> assertThat(response.getBody().isHasErrors()).isTrue());
    }

    // ========== Header Forwarding Tests ==========

    @Test
    void testAuthorizationHeaderForwarding() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer test-token-123");

        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/headers/auth")
                    .referenceId("auth1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request, headers);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .extracting(CompositeResponse::getResponses)
                .isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("auth1");
        SubResponse authResponse = response.getBody().getResponses().get("auth1");
        assertThat(authResponse.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void testCustomHeaderForwarding() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "test-api-key-123");
        headers.set("X-Custom-Header", "custom-value");

        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/headers/custom")
                    .referenceId("headers1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request, headers);

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull()
                .extracting(CompositeResponse::getResponses)
                .isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SubResponse headersResponse = response.getBody().getResponses().get("headers1");
        assertThat(headersResponse.getHttpStatus()).isEqualTo(200);
    }

    // ========== Array/Collection Tests ==========

    @Test
    void testArrayResponse() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/arrays/simple")
                    .referenceId("array1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .extracting(CompositeResponse::getResponses)
                .isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("array1");
        SubResponse arrayResponse = response.getBody().getResponses().get("array1");
        assertThat(arrayResponse.getHttpStatus()).isEqualTo(200);
        assertThat(arrayResponse.getBody()).isNotNull();
    }

    @Test
    void testListResponse() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/arrays/objects")
                    .referenceId("list1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        SubResponse listResponse = response.getBody().getResponses().get("list1");
        assertThat(listResponse.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void testArrayElementAccess() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/arrays/objects")
                    .referenceId("list1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/products/${list1.items[0].id}")
                    .referenceId("prod1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();

        assertThat(response.getBody().getResponses().get("list1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testEmptyRequestBody() {
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
    }

    @Test
    void testNullResponseBody() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/notfound/missing")
                    .referenceId("order1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testLargeBatch() {
        List<SubRequestDto> subRequests = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
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

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(10);

        for (int i = 1; i <= 10; i++) {
            assertThat(response.getBody().getResponses().get("user" + i).getHttpStatus())
                .isEqualTo(HttpStatus.OK.value());
        }
    }

    @Test
    void testMixedSuccessAndFailure() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/notfound/missing")
                    .referenceId("order1")
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
        assertThat(response.getBody().isHasErrors()).isTrue();
        assertThat(response.getBody().getResponses()).hasSize(3);

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("order1").getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    // ========== Void/Empty Response Tests ==========

    @Test
    void testVoidReturnType_deleteEndpoint() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("DELETE")
                    .url("/api/orders/order123")
                    .referenceId("delete1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("delete1");

        SubResponse deleteResponse = response.getBody().getResponses().get("delete1");
        // 204 No Content for successful void endpoint
        assertThat(deleteResponse.getHttpStatus()).isIn(200, 204);
    }

    @Test
    void testEmptyBodyOn2xxResponse() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/order123/empty")
                    .referenceId("empty1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("empty1");

        SubResponse emptyResponse = response.getBody().getResponses().get("empty1");
        assertThat(emptyResponse.getHttpStatus()).isEqualTo(200);
    }

    @Test
    void testMixedVoidAndRegularResponses() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build(),
                SubRequestDto.builder()
                    .method("DELETE")
                    .url("/api/orders/order123")
                    .referenceId("delete1")
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
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(3);

        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody().getResponses().get("delete1").getHttpStatus()).isIn(HttpStatus.OK.value(), HttpStatus.NO_CONTENT.value());
        assertThat(response.getBody().getResponses().get("prod1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void testErrorResponseWithEmptyBody() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/orders/notfound/missing")
                    .referenceId("notfound1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponses()).containsKey("notfound1");

        SubResponse notFoundResponse = response.getBody().getResponses().get("notfound1");
        assertThat(notFoundResponse.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void testVoidEndpointInBatch() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("DELETE")
                    .url("/api/orders/order123")
                    .referenceId("delete1")
                    .build(),
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build()
            ))
            .build();

        ResponseEntity<CompositeResponse> response = executeCompositeRequest(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isHasErrors()).isFalse();
        assertThat(response.getBody().getResponses()).hasSize(2);

        assertThat(response.getBody().getResponses().get("delete1").getHttpStatus()).isIn(HttpStatus.OK.value(), HttpStatus.NO_CONTENT.value());
        assertThat(response.getBody().getResponses().get("user1").getHttpStatus()).isEqualTo(HttpStatus.OK.value());
    }

    private ResponseEntity<CompositeResponse> executeCompositeRequest(CompositeRequest request) {
        return executeCompositeRequest(request, new HttpHeaders());
    }

    private ResponseEntity<CompositeResponse> executeCompositeRequest(
            CompositeRequest request, HttpHeaders headers) {
        try {
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<CompositeRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<CompositeResponse> response = restTemplate.exchange(
                "/api/composite/execute",
                HttpMethod.POST,
                entity,
                CompositeResponse.class
            );

            if (response.getStatusCode() != HttpStatus.OK && response.getBody() != null) {
                System.err.println("Composite Request Failed. Status: " + response.getStatusCode());
                System.err.println("Response Body: " + response.getBody());
            }
            return response;
        } catch (Exception e) {
            fail("Request failed: " + e.getMessage());
            return null;
        }
    }
}

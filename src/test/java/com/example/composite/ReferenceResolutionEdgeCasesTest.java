package com.example.composite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.example.composite.config.EndpointRegistry;
import com.example.composite.exception.ReferenceResolutionException;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.service.CompositeService;

@ExtendWith(MockitoExtension.class)
class ReferenceResolutionEdgeCasesTest {
    @Mock
    private RestTemplate restTemplate;
    
    @Mock
    private EndpointRegistry endpointRegistry;
    
    @InjectMocks
    private CompositeService compositeService;

    private final String baseUrl = "http://localhost:8080"; // Extracted base URL

    @BeforeEach
    void setup() {
        when(endpointRegistry.isEndpointAvailable(anyString(), anyString()))
                .thenReturn(true);
        compositeService.setBaseUrl();
    }

    @Test
    void shouldHandleEscapedReferences() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("escaped")
            .url(baseUrl + "/api/data") // Prepend baseUrl
            .method("POST")
            .body(Map.of(
                "literal", "\\${not.a.reference}",
                "actual", "${prev.body.id}",
                "mixed", "Prefix\\${not.a.reference}${prev.body.id}"
            ))
            .dependencies(Set.of("prev"))
            .build();

        when(restTemplate.exchange(
            eq(baseUrl + "/api/data"), // Prepend baseUrl
            eq(HttpMethod.POST),
            argThat(req -> {
                Map<String, String> body = (Map<String, String>) req.getBody();
                return body.get("literal").equals("${not.a.reference}") &&
                       body.get("actual").equals("123") &&
                       body.get("mixed").equals("Prefix${not.a.reference}123");
            }),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok("success"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(
                    SubRequest.builder()
                        .referenceId("prev")
                        .url(baseUrl + "/api/prev") // Prepend baseUrl
                        .method("GET")
                        .build(),
                    request
                ))
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
    }

    @Test
    void shouldHandleNestedJsonReferences() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("nested")
            .url("/api/nested")
            .method("POST")
            .body(Map.of(
                "deep", Map.of(
                    "deeper", Map.of(
                        "deepest", "${user.body.profile.settings.preference}"
                    )
                ),
                "array", List.of(
                    Map.of("ref", "${user.body.id}"),
                    Map.of("nested", Map.of(
                        "ref", "${user.body.email}"
                    ))
                )
            ))
            .dependencies(Set.of("user"))
            .build();

        // Mock user response with deep nested structure
        when(restTemplate.exchange(
            eq("/api/user"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "id", "123",
            "email", "test@test.com",
            "profile", Map.of(
                "settings", Map.of(
                    "preference", "dark-mode"
                )
            )
        )));

        // Verify nested reference resolution
        when(restTemplate.exchange(
            eq("/api/nested"),
            eq(HttpMethod.POST),
            argThat(req -> {
                Map<String, Object> body = (Map<String, Object>) req.getBody();
                Map<String, Object> deep = (Map<String, Object>) body.get("deep");
                Map<String, Object> deeper = (Map<String, Object>) deep.get("deeper");
                return "dark-mode".equals(deeper.get("deepest")) &&
                       "123".equals(((Map)((List)body.get("array")).get(0)).get("ref")) &&
                       "test@test.com".equals(((Map)((Map)((List)body.get("array")).get(1)).get("nested")).get("ref"));
            }),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok("success"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(
                    SubRequest.builder()
                        .referenceId("user")
                        .url("/api/user")
                        .method("GET")
                        .build(),
                    request
                ))
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
    }

    @Test
    void shouldHandleArrayIndexReferences() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("arrayRef")
            .url("/api/array")
            .method("POST")
            .body(Map.of(
                "firstItem", "${list.body.items[0]}",
                "lastItem", "${list.body.items[-1]}",
                "nestedArray", "${list.body.nested[1].items[0]}"
            ))
            .dependencies(Set.of("list"))
            .build();

        // Mock list response with arrays
        when(restTemplate.exchange(
            eq("/api/list"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "items", List.of("first", "second", "last"),
            "nested", List.of(
                Map.of("items", List.of("nested1")),
                Map.of("items", List.of("nested2"))
            )
        )));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(
                    SubRequest.builder()
                        .referenceId("list")
                        .url("/api/list")
                        .method("GET")
                        .build(),
                    request
                ))
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
    }

    @Test
    void shouldHandleReferencesInHeaders() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("headers")
            .url("/api/headers")
            .method("GET")
            .headers(Map.of(
                "Authorization", "Bearer ${auth.body.token}",
                "X-User-Id", "${user.body.id}",
                "X-Correlation-Id", "${root.body.correlationId}"
            ))
            .dependencies(Set.of("auth", "user", "root"))
            .build();

        // Mock dependent responses
        mockDependentResponses();

        // Verify header resolution
        when(restTemplate.exchange(
            eq("/api/headers"),
            eq(HttpMethod.GET),
            argThat(req -> {
                HttpHeaders headers = req.getHeaders();
                return headers.getFirst("Authorization").equals("Bearer token123") &&
                       headers.getFirst("X-User-Id").equals("user123") &&
                       headers.getFirst("X-Correlation-Id").equals("corr123");
            }),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok("success"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(
                    SubRequest.builder()
                        .referenceId("root")
                        .url("/api/root")
                        .method("GET")
                        .build(),
                    SubRequest.builder()
                        .referenceId("auth")
                        .url("/api/auth")
                        .method("POST")
                        .build(),
                    SubRequest.builder()
                        .referenceId("user")
                        .url("/api/user")
                        .method("GET")
                        .build(),
                    request
                ))
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
    }

    @Test
    void shouldHandleNullValues() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("nullRef")
            .url("/api/null")
            .method("POST")
            .body(Map.of(
                "nullValue", "${prev.body.nullField}",
                "defaultValue", "${prev.body.nullField:defaultValue}"
            ))
            .dependencies(Set.of("prev"))
            .build();

        when(restTemplate.exchange(
            eq("/api/prev"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "nullField", null
        )));

        // When/Then
        assertThrows(ReferenceResolutionException.class, () ->
            compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(List.of(
                        SubRequest.builder()
                            .referenceId("prev")
                            .url("/api/prev")
                            .method("GET")
                            .build(),
                        request
                    ))
                    .build()
            )
        );
    }

    @Test
    void shouldHandleTypeConversions() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("convert")
            .url("/api/convert")
            .method("POST")
            .body(Map.of(
                "numToString", "${prev.body.number}",
                "boolToString", "${prev.body.boolean}",
                "objectToString", "${prev.body.object}"
            ))
            .dependencies(Set.of("prev"))
            .build();

        when(restTemplate.exchange(
            eq("/api/prev"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "number", 123,
            "boolean", true,
            "object", Map.of("key", "value")
        )));

        when(restTemplate.exchange(
            eq("/api/convert"),
            eq(HttpMethod.POST),
            argThat(req -> {
                Map<String, String> body = (Map<String, String>) req.getBody();
                return body.get("numToString").equals("123") &&
                       body.get("boolToString").equals("true") &&
                       body.get("objectToString").contains("key");
            }),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok("success"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(
                    SubRequest.builder()
                        .referenceId("prev")
                        .url("/api/prev")
                        .method("GET")
                        .build(),
                    request
                ))
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
    }

    private void mockDependentResponses() {
        when(restTemplate.exchange(
            eq("/api/root"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "correlationId", "corr123"
        )));

        when(restTemplate.exchange(
            eq("/api/auth"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "token", "token123"
        )));

        when(restTemplate.exchange(
            eq("/api/user"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "id", "user123"
        )));
    }
}

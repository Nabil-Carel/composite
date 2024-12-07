package com.example.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import jakarta.validation.Validator;

import com.example.composite.config.BaseUrlProvider;
import com.example.composite.config.EndpointRegistry;
import com.example.composite.exception.CompositeExecutionException;
import com.example.composite.exception.ValidationException;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;
import com.example.composite.service.CompositeService;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
class ErrorHandlingScenarioTests {
    @Mock
    private RestTemplate restTemplate;
    
    @Mock
    private EndpointRegistry endpointRegistry;

    @Mock
    private BaseUrlProvider urlProvider;
    
    @Autowired
    private TransactionTemplate transactionTemplate;
    
    @Autowired
    private Validator validator;
    
    private CompositeService compositeService;

    @BeforeEach
    void setup() {
        when(endpointRegistry.isEndpointAvailable(anyString(), anyString()))
            .thenReturn(true);
        compositeService = new CompositeService(
            restTemplate, 
            endpointRegistry, 
            transactionTemplate,
            validator,
            urlProvider
        );
        when(urlProvider.getBaseUrl()).thenReturn("http://localhost:8080");
        compositeService.setBaseUrl();
        
    }

    @Test
    void shouldHandleNetworkTimeouts() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("timeout")
            .url("/api/slow")
            .method("GET")
            .build();

        when(restTemplate.exchange(
            anyString(),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new ResourceAccessException("Read timed out"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false) // Continue on error
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        SubResponse errorResponse = response.getResponses().get("timeout");
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, errorResponse.getHttpStatus());
        assertTrue(((Map<?, ?>) errorResponse.getBody())
            .get("error").toString().contains("Read timed out"));
    }

    @Test
    void shouldHandleInvalidJsonResponse() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("invalidJson")
            .url("/api/malformed")
            .method("GET")
            .build();

        when(restTemplate.exchange(
            anyString(),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new RestClientException("Could not parse JSON response"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        SubResponse errorResponse = response.getResponses().get("invalidJson");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, errorResponse.getHttpStatus());
        assertNotNull(errorResponse.getBody());
    }

    @Test
    void shouldHandleEndpointThrottling() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("throttled")
            .url("/api/limited")
            .method("POST")
            .body("test")
            .build();

        when(restTemplate.exchange(
            anyString(),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new HttpClientErrorException(
            HttpStatus.TOO_MANY_REQUESTS, 
            "Rate limit exceeded",
            "{'retry-after': '60'}".getBytes(),
            StandardCharsets.UTF_8));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        SubResponse errorResponse = response.getResponses().get("throttled");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, errorResponse.getHttpStatus());
        assertTrue(((Map<?, ?>) errorResponse.getBody())
            .get("error").toString().contains("Rate limit exceeded"));
    }

    @Test
    void shouldHandleRedirects() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("redirect")
            .url("/api/old")
            .method("GET")
            .build();

        when(restTemplate.exchange(
            eq("/api/old"),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new HttpClientErrorException(
            HttpStatus.MOVED_PERMANENTLY, 
            "Moved Permanently",
            null,
            null,
            null));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        assertEquals(HttpStatus.MOVED_PERMANENTLY, 
            response.getResponses().get("redirect").getHttpStatus());
    }

    @Test
    void shouldHandleConnectionFailures() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("connection")
            .url("/api/unavailable")
            .method("GET")
            .build();

        when(restTemplate.exchange(
            anyString(),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new ResourceAccessException("Connection refused"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        assertEquals(HttpStatus.BAD_GATEWAY, 
            response.getResponses().get("connection").getHttpStatus());
    }

    @Test
    void shouldHandleServerErrors() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("server")
            .url("/api/error")
            .method("GET")
            .build();

        when(restTemplate.exchange(
            anyString(),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new HttpServerErrorException(
            HttpStatus.INTERNAL_SERVER_ERROR, 
            "Internal Server Error"));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, 
            response.getResponses().get("server").getHttpStatus());
    }

    @Test
    void shouldHandleMalformedUrls() {
        // Given
        SubRequest request = SubRequest.builder()
            .referenceId("malformed")
            .url("invalid:url")
            .method("GET")
            .build();

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        SubResponse errorResponse = response.getResponses().get("malformed");
        assertEquals(HttpStatus.BAD_REQUEST, errorResponse.getHttpStatus());
    }

    @Test
    void shouldHandleCircularDependencyError() {
        // Given
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("first")
                .url("/api/first/${second.body.id}")
                .method("GET")
                .dependencies(Set.of("second"))
                .build(),
            SubRequest.builder()
                .referenceId("second")
                .url("/api/second/${first.body.id}")
                .method("GET")
                .dependencies(Set.of("first"))
                .build()
        );

        // When/Then
        assertThrows(ValidationException.class, () ->
            compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(requests)
                    .build()
            )
        );
    }

    @Test
    void shouldHandleAllOrNoneFailure() {
        // Given
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("success")
                .url("/api/success")
                .method("POST")
                .body("success")
                .build(),
            SubRequest.builder()
                .referenceId("failure")
                .url("/api/fail")
                .method("POST")
                .body("fail")
                .build()
        );

        when(restTemplate.exchange(
            eq("/api/success"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok("Success"));

        when(restTemplate.exchange(
            eq("/api/fail"),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        // When/Then
        CompositeExecutionException exception = assertThrows(
            CompositeExecutionException.class,
            () -> compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(requests)
                    .allOrNone(true)
                    .build()
            )
        );

        assertTrue(exception.getMessage().contains("Request failed: failure"));
    }

    @Test
    void shouldHandleInvalidReferenceResolution() {
        // Given
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("first")
                .url("/api/first")
                .method("GET")
                .build(),
            SubRequest.builder()
                .referenceId("second")
                .url("/api/second/${first.body.nonexistent}")
                .method("GET")
                .dependencies(Set.of("first"))
                .build()
        );

        when(restTemplate.exchange(
            eq("/api/first"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("id", "123")));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(false)
                .build()
        );

        // Then
        assertTrue(response.isHasErrors());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, 
            response.getResponses().get("second").getHttpStatus());
    }
}

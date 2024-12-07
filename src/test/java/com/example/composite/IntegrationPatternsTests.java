package com.example.composite;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.example.composite.config.BaseUrlProvider;
import com.example.composite.config.EndpointRegistry;
import com.example.composite.exception.RequestTimeoutException;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.service.CompositeService;

@ExtendWith(MockitoExtension.class)
class IntegrationPatternsTests {
    @Mock
    private RestTemplate restTemplate;
    
    @Mock
    private EndpointRegistry endpointRegistry;

    
    @Mock
    private BaseUrlProvider urlProvider;
    
    @InjectMocks
    private CompositeService compositeService;

    private final String baseUrl = "http://localhost:8080"; // Extracted base URL

    @BeforeEach
    void setup() {
        when(endpointRegistry.isEndpointAvailable(anyString(), anyString()))
                .thenReturn(true);
        when(urlProvider.getBaseUrl()).thenReturn(baseUrl);        
        compositeService.setBaseUrl();
    }

    @Test
    void shouldImplementRetryPattern() {
        // Given - Service that fails twice then succeeds
        AtomicInteger attempts = new AtomicInteger();
        
        when(restTemplate.exchange(
            eq(baseUrl + "/api/flaky"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(inv -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) {
                throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return ResponseEntity.ok(Map.of("attempt", attempt));
        });

        SubRequest request = SubRequest.builder()
            .referenceId("retry")
            .url("/api/flaky")
            .method("GET")
            .build();

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(List.of(request))
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
        assertEquals(3, attempts.get());
        assertEquals(3, ((Map<String, Integer>) response.getResponses()
            .get("retry").getBody()).get("attempt"));
    }

    @Test
    void shouldImplementCircuitBreaker() throws InterruptedException {
        // Given
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger totalAttempts = new AtomicInteger();
        
        when(restTemplate.exchange(
            eq(baseUrl + "/api/unstable"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(inv -> {
            totalAttempts.incrementAndGet();
            if (failures.incrementAndGet() <= 5) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return ResponseEntity.ok("success");
        });

        // When - Make multiple requests
        List<CompositeResponse> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            try {
                responses.add(compositeService.processRequests(
                    CompositeRequest.builder()
                        .subRequests(List.of(
                            SubRequest.builder()
                                .referenceId("circuit")
                                .url("/api/unstable")
                                .method("GET")
                                .build()
                        ))
                        .build()
                ));
            } catch (Exception e) {
                // Expected for some requests
            }
            Thread.sleep(100); // Brief delay between requests
        }

        // Then
        assertTrue(totalAttempts.get() < 10, 
            "Circuit breaker should prevent all attempts");
    }

    @Test
    void shouldImplementFallbackPattern() {
        // Given - Primary service fails, fallback succeeds
        when(restTemplate.exchange(
            eq(baseUrl + "/api/primary"),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/fallback"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok("fallback response"));

        List<SubRequest> requests = List.of(
            // Primary request
            SubRequest.builder()
                .referenceId("primary")
                .url("/api/primary")
                .method("GET")
                .build(),
            // Fallback request (executed if primary fails)
            SubRequest.builder()
                .referenceId("fallback")
                .url("/api/fallback")
                .method("GET")
                .build()
        );

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(false) // Allow partial success
                .build()
        );

        // Then
        assertTrue(response.getResponses().get("primary").getHttpStatus().is5xxServerError());
        assertTrue(response.getResponses().get("fallback").getHttpStatus().is2xxSuccessful());
    }

    @Test
    void shouldHandleTimeout() {
        // Given
        when(restTemplate.exchange(
            eq(baseUrl + "/api/slow"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(inv -> {
            Thread.sleep(2000); // Simulate slow response
            return ResponseEntity.ok("too late");
        });

        SubRequest request = SubRequest.builder()
            .referenceId("timeout")
            .url("/api/slow")
            .method("GET")
            .build();

        // When/Then
        assertThrows(RequestTimeoutException.class, () ->
            compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(List.of(request))
                    .timeout(Duration.ofSeconds(1))
                    .build()
            ));
    }

    @Test
    void shouldImplementBulkhead() {
        // Given
        int maxConcurrent = 3;
        AtomicInteger currentCalls = new AtomicInteger(0);
        AtomicInteger maxConcurrentObserved = new AtomicInteger(0);
        
        when(restTemplate.exchange(
            eq(baseUrl + "/api/bulkhead"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(inv -> {
            int current = currentCalls.incrementAndGet();
            maxConcurrentObserved.updateAndGet(max -> Math.max(max, current));
            try {
                Thread.sleep(500); // Simulate processing
                return ResponseEntity.ok("processed");
            } finally {
                currentCalls.decrementAndGet();
            }
        });

        // When - Submit multiple concurrent requests
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<CompositeResponse>> futures = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> 
                compositeService.processRequests(
                    CompositeRequest.builder()
                        .subRequests(List.of(
                            SubRequest.builder()
                                .referenceId("bulkhead")
                                .url("/api/bulkhead")
                                .method("GET")
                                .build()
                        ))
                        .build()
                )
            ));
        }

        // Then
        List<CompositeResponse> responses = futures.stream()
            .map(f -> {
                try {
                    return f.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toList());

        assertTrue(maxConcurrentObserved.get() <= maxConcurrent, 
            "Bulkhead should limit concurrent calls");
        assertEquals(10, responses.size(), "All requests should complete");
        
        executor.shutdown();
    }

    @Test
    void shouldImplementCache() {
        // Given
        AtomicInteger callCount = new AtomicInteger();
        
        when(restTemplate.exchange(
            eq(baseUrl + "/api/cacheable"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(inv -> {
            callCount.incrementAndGet();
            return ResponseEntity.ok("response");
        });

        SubRequest request = SubRequest.builder()
            .referenceId("cache")
            .url("/api/cacheable")
            .method("GET")
            .build();

        // When - Make same request multiple times
        for (int i = 0; i < 3; i++) {
            compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(List.of(request))
                    .build()
            );
        }

        // Then
        assertEquals(1, callCount.get(), "Should cache identical requests");
    }

    @Test
    void shouldImplementRateLimiting() {
        // Given
        AtomicInteger requestCount = new AtomicInteger();
        Map<Long, Integer> requestsPerSecond = new ConcurrentHashMap<>();
        
        when(restTemplate.exchange(
            eq(baseUrl + "/api/ratelimited"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(inv -> {
            long second = System.currentTimeMillis() / 1000;
            int count = requestsPerSecond.merge(second, 1, Integer::sum);
            
            if (count > 5) { // Rate limit: 5 requests per second
                throw new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
            }
            
            requestCount.incrementAndGet();
            return ResponseEntity.ok("processed");
        });

        // When - Make burst of requests
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<CompositeResponse>> futures = new ArrayList<>();
        
        for (int i = 0; i < 20; i++) {
            futures.add(executor.submit(() ->
                compositeService.processRequests(
                    CompositeRequest.builder()
                        .subRequests(List.of(
                            SubRequest.builder()
                                .referenceId("ratelimit")
                                .url("/api/ratelimited")
                                .method("GET")
                                .build()
                        ))
                        .build()
                )
            ));
        }

        // Then
        List<CompositeResponse> responses = futures.stream()
            .map(f -> {
                try {
                    return f.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .collect(Collectors.toList());

        assertTrue(requestCount.get() <= 5, 
            "Should respect rate limit");
        
        executor.shutdown();
    }

    @Test
    void shouldImplementDeadLetterChannel() {
        // Given - Track failed requests
        List<SubRequest> failedRequests = Collections.synchronizedList(new ArrayList<>());
        
        when(restTemplate.exchange(
            eq(baseUrl + "/api/unreliable"),
            any(),
            any(),
            eq(Object.class)
        )).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        SubRequest request = SubRequest.builder()
            .referenceId("deadletter")
            .url("/api/unreliable")
            .method("POST")
            .body("test")
            .build();

        // When
        try {
            compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(List.of(request))
                    .build()
            );
        } catch (Exception e) {
            failedRequests.add(request);
        }

        // Then
        assertEquals(1, failedRequests.size(), 
            "Failed request should be captured");
        assertEquals("deadletter", failedRequests.get(0).getReferenceId());
    }
}

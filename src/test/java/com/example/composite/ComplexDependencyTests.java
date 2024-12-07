package com.example.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import com.example.composite.config.BaseUrlProvider;
import com.example.composite.config.EndpointRegistry;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;
import com.example.composite.service.CompositeService;

import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
class ComplexDependencyTests {
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

    private final String baseUrl = "http://localhost:8080";

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

        when(urlProvider.getBaseUrl()).thenReturn(baseUrl);
        compositeService.setBaseUrl();
    }

    @Test
    void shouldHandleMultiLevelDependencies() {
        // Given - Chain: A -> B -> C -> D
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("A")
                .url("/api/users")
                .method("POST")
                .body(Map.of("name", "John"))
                .build(),
            
            SubRequest.builder()
                .referenceId("B")
                .url("/api/users/${A.body.id}/orders")
                .method("POST")
                .body(Map.of("product", "item1"))
                .dependencies(Set.of("A"))
                .build(),
            
            SubRequest.builder()
                .referenceId("C")
                .url("/api/orders/${B.body.orderId}/payment")
                .method("POST")
                .body(Map.of("amount", "${B.body.amount}"))
                .dependencies(Set.of("B"))
                .build(),
            
            SubRequest.builder()
                .referenceId("D")
                .url("/api/payments/${C.body.paymentId}/confirm")
                .method("POST")
                .body(Map.of(
                    "userId", "${A.body.id}",
                    "orderId", "${B.body.orderId}",
                    "paymentId", "${C.body.paymentId}"
                ))
                .dependencies(Set.of("C"))
                .build()
        );

        // Mock responses
        when(restTemplate.exchange(
            eq(baseUrl + "/api/users"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("id", "user123")));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/users/user123/orders"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "orderId", "order123",
            "amount", 100
        )));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/orders/order123/payment"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("paymentId", "payment123")));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/payments/payment123/confirm"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("status", "confirmed")));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(true)
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
        assertEquals(4, response.getResponses().size());
        
        // Verify execution order
        List<String> executionOrder = new ArrayList<>(response.getResponses().keySet());
        assertEquals("A", executionOrder.get(0));
        assertEquals("B", executionOrder.get(1));
        assertEquals("C", executionOrder.get(2));
        assertEquals("D", executionOrder.get(3));
    }

    @Test
    void shouldHandleDiamondDependencies() {
        // Given - Diamond: A -> (B,C) -> D
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("A")
                .url("/api/project")
                .method("POST")
                .body(Map.of("name", "Project X"))
                .build(),
            
            SubRequest.builder()
                .referenceId("B")
                .url("/api/tasks")
                .method("POST")
                .body(Map.of(
                    "projectId", "${A.body.id}",
                    "type", "planning"
                ))
                .dependencies(Set.of("A"))
                .build(),
            
            SubRequest.builder()
                .referenceId("C")
                .url("/api/resources")
                .method("POST")
                .body(Map.of(
                    "projectId", "${A.body.id}",
                    "type", "team"
                ))
                .dependencies(Set.of("A"))
                .build(),
            
            SubRequest.builder()
                .referenceId("D")
                .url("/api/project/${A.body.id}/start")
                .method("POST")
                .body(Map.of(
                    "taskId", "${B.body.taskId}",
                    "resourceId", "${C.body.resourceId}"
                ))
                .dependencies(Set.of("B", "C"))
                .build()
        );

        // Mock responses
        when(restTemplate.exchange(
            eq(baseUrl + "/api/project"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("id", "proj123")));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/tasks"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("taskId", "task123")));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/resources"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("resourceId", "res123")));

        when(restTemplate.exchange(
            eq(baseUrl + "/api/project/proj123/start"),
            eq(HttpMethod.POST),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("status", "started")));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(true)
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
        assertEquals(4, response.getResponses().size());
        
        // Verify A executes first
        assertTrue(response.getResponses().containsKey("A"));
        
        // Verify B and C can execute in either order
        assertTrue(response.getResponses().containsKey("B"));
        assertTrue(response.getResponses().containsKey("C"));
        
        // Verify D executes last
        assertTrue(response.getResponses().containsKey("D"));
    }

    @Test
    void shouldHandleParallelIndependentChains() {
        // Given - Two parallel chains: (A->B->C) and (D->E->F)
        List<SubRequest> requests = List.of(
            // Chain 1
            SubRequest.builder()
                .referenceId("A")
                .url("/api/chain1/first")
                .method("GET")
                .build(),
            
            SubRequest.builder()
                .referenceId("B")
                .url("/api/chain1/second")
                .method("POST")
                .body(Map.of("ref", "${A.body.id}"))
                .dependencies(Set.of("A"))
                .build(),
            
            SubRequest.builder()
                .referenceId("C")
                .url("/api/chain1/third")
                .method("POST")
                .body(Map.of("ref", "${B.body.id}"))
                .dependencies(Set.of("B"))
                .build(),

            // Chain 2
            SubRequest.builder()
                .referenceId("D")
                .url("/api/chain2/first")
                .method("GET")
                .build(),
            
            SubRequest.builder()
                .referenceId("E")
                .url("/api/chain2/second")
                .method("POST")
                .body(Map.of("ref", "${D.body.id}"))
                .dependencies(Set.of("D"))
                .build(),
            
            SubRequest.builder()
                .referenceId("F")
                .url("/api/chain2/third")
                .method("POST")
                .body(Map.of("ref", "${E.body.id}"))
                .dependencies(Set.of("E"))
                .build()
        );

        // Mock all responses with unique IDs
        mockChainResponses();

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(false)
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
        assertEquals(6, response.getResponses().size());
        
        // Verify chain ordering
        verifyChainOrdering(response, "A", "B", "C");
        verifyChainOrdering(response, "D", "E", "F");
    }

    @Test
    void shouldOptimizeParallelExecution() {
        // Given - Multiple independent requests with shared dependencies
        List<SubRequest> requests = List.of(
            // Base request that others depend on
            SubRequest.builder()
                .referenceId("base")
                .url("/api/base")
                .method("GET")
                .build(),
            
            // Multiple parallel requests depending on base
            SubRequest.builder()
                .referenceId("parallel1")
                .url("/api/parallel1/${base.body.id}")
                .method("GET")
                .dependencies(Set.of("base"))
                .build(),
            
            SubRequest.builder()
                .referenceId("parallel2")
                .url("/api/parallel2/${base.body.id}")
                .method("GET")
                .dependencies(Set.of("base"))
                .build(),
            
            SubRequest.builder()
                .referenceId("parallel3")
                .url("/api/parallel3/${base.body.id}")
                .method("GET")
                .dependencies(Set.of("base"))
                .build()
        );

        // Mock responses
        when(restTemplate.exchange(
            eq(baseUrl + "/api/base"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("id", "base123")));

        // Mock parallel requests with different response times
        mockParallelResponses();

        // When
        long startTime = System.currentTimeMillis();
        
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(true)
                .build()
        );
        
        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        assertFalse(response.isHasErrors());
        assertEquals(4, response.getResponses().size());
        
        // Verify parallel execution (total time should be less than sum of individual times)
        assertTrue(totalTime < 3000); // Assuming each parallel request takes 1s
    }

    private void mockChainResponses() {
        Map<String, String> urlToId = new HashMap<>();
        urlToId.put("/api/chain1/first", "a1");
        urlToId.put("/api/chain1/second", "b1");
        urlToId.put("/api/chain1/third", "c1");
        urlToId.put("/api/chain2/first", "d1");
        urlToId.put("/api/chain2/second", "e1");
        urlToId.put("/api/chain2/third", "f1");

        List<Map.Entry<String, String>> sortedEntry = urlToId.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());

        long i = 0;
        for (Map.Entry<String, String> entry : sortedEntry) {
            when(restTemplate.exchange(
                eq(baseUrl + entry.getKey()),
                any(),
                any(),
                eq(Object.class)
            )).thenReturn(ResponseEntity.ok(Map.of("id", entry.getValue(), "timestamp", String.valueOf(i++))));
        }
    }

    private void mockParallelResponses() {
        when(restTemplate.exchange(
            matches("/api/parallel[1-3]/.*"),
            any(),
            any(),
            eq(Object.class)
        )).thenAnswer(invocation -> {
            Thread.sleep(1000); // Simulate processing time
            return ResponseEntity.ok(Map.of(
                "result", "parallel-" + invocation.getArgument(0).toString()
            ));
        });
    }

    private void verifyChainOrdering(CompositeResponse response, 
                                   String first, String second, String third) {
        Map<String, SubResponse> responses = response.getResponses();
        
        // Get the timestamps from responses
        long firstTime = getTimestamp(responses.get(first));
        long secondTime = getTimestamp(responses.get(second));
        long thirdTime = getTimestamp(responses.get(third));

        // Verify ordering
        assertTrue(firstTime < secondTime);
        assertTrue(secondTime < thirdTime);
    }

    private long getTimestamp(SubResponse response) {
        // Cast the body to a Map<String, Object>
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // Retrieve the timestamp and cast it to Long
        return (long) Long.parseLong((String)body.get("timestamp"), 10);
    }
}

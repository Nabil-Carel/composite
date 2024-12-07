package com.example.composite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import com.example.composite.config.BaseUrlProvider;
import com.example.composite.config.EndpointRegistry;
import com.example.composite.config.EndpointRegistry.EndpointInfo;
import com.example.composite.config.EndpointRegistry.EndpointPattern;
import com.example.composite.exception.CircularDependencyException;
import com.example.composite.exception.ValidationException;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;
import com.example.composite.service.CompositeService;

import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
class AdvancedDependencyTests {
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
        // Create a mock for availableEndpoints
        Map<EndpointPattern, EndpointInfo> availableEndpoints = createAvailableEndpoints();

        // Mock the behavior of the endpointRegistry to return the available endpoints
        when(endpointRegistry.getAvailableEndpoints()).thenReturn(new HashSet<>(availableEndpoints.values()));
        
        // Mock the isEndpointAvailable method if needed
        availableEndpoints.forEach((pattern, info) -> {
            when(endpointRegistry.isEndpointAvailable(pattern.getMethod(), info.getPattern())).thenReturn(true);

        });

        when(urlProvider.getBaseUrl()).thenReturn(baseUrl);

        compositeService = new CompositeService(
            restTemplate, 
            endpointRegistry, 
            transactionTemplate,
            validator,
            urlProvider
        );
        compositeService.setBaseUrl();
    }

    private Map<EndpointPattern, EndpointInfo> createAvailableEndpoints() {
        Map<EndpointPattern, EndpointInfo> endpoints = new HashMap<>();

        endpoints.put(new EndpointPattern("POST", "/api/project/create"), 
            EndpointInfo.builder().pattern("/api/project/create").method("POST").description("Create project").build());

        endpoints.put(new EndpointPattern("POST", "/api/team/create"), 
            EndpointInfo.builder().pattern("/api/team/create").method("POST").description("Create team").build());

        endpoints.put(new EndpointPattern("POST", "/api/team/${team.body.id}/members"), 
            EndpointInfo.builder().pattern("/api/team/${team.body.id}/members").method("POST").description("Add members to team").build());

        endpoints.put(new EndpointPattern("POST", "/api/resources/allocate"), 
            EndpointInfo.builder().pattern("/api/resources/allocate").method("POST").description("Allocate resources").build());

        endpoints.put(new EndpointPattern("POST", "/api/resources/${resources.body.id}/budget"), 
            EndpointInfo.builder().pattern("/api/resources/${resources.body.id}/budget").method("POST").description("Set budget for resources").build());

        endpoints.put(new EndpointPattern("POST", "/api/schedule/create"), 
            EndpointInfo.builder().pattern("/api/schedule/create").method("POST").description("Create schedule").build());

        endpoints.put(new EndpointPattern("GET", "/api/schedule/${schedule.body.id}/milestones"), 
            EndpointInfo.builder().pattern("/api/schedule/${schedule.body.id}/milestones").method("POST").description("Add milestones to schedule").build());

        return endpoints;
    }

    @Test
    void shouldHandleBranchingDependencies() {
        // Given - One request triggers multiple parallel chains
        List<SubRequest> requests = List.of(
            // Root request
            SubRequest.builder()
                .referenceId("root")
                .url("/api/project/create")
                .method("POST")
                .body(Map.of("name", "Project X"))
                .build(),

            // Branch 1: Team setup
            SubRequest.builder()
                .referenceId("team")
                .url("/api/team/create")
                .method("POST")
                .body(Map.of("projectId", "${root.body.id}"))
                .dependencies(Set.of("root"))
                .build(),
            
            SubRequest.builder()
                .referenceId("members")
                .url("/api/team/${team.body.id}/members")
                .method("POST")
                .body(Map.of("count", 5))
                .dependencies(Set.of("team"))
                .build(),

            // Branch 2: Resource setup
            SubRequest.builder()
                .referenceId("resources")
                .url("/api/resources/allocate")
                .method("POST")
                .body(Map.of("projectId", "${root.body.id}"))
                .dependencies(Set.of("root"))
                .build(),
            
            SubRequest.builder()
                .referenceId("budget")
                .url("/api/resources/${resources.body.id}/budget")
                .method("POST")
                .body(Map.of("amount", 10000))
                .dependencies(Set.of("resources"))
                .build(),

            // Branch 3: Schedule setup
            SubRequest.builder()
                .referenceId("schedule")
                .url("/api/schedule/create")
                .method("POST")
                .body(Map.of("projectId", "${root.body.id}"))
                .dependencies(Set.of("root"))
                .build(),
            
            SubRequest.builder()
                .referenceId("milestones")
                .url("/api/schedule/${schedule.body.id}/milestones")
                .method("GET")
                .dependencies(Set.of("schedule"))
                .build()
        );

        // Mock responses
        mockBranchResponses();

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(true)
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
        assertEquals(7, response.getResponses().size());
        
        // Verify branch execution
        verifyBranchExecution(response);
    }

    @Test
    void shouldHandleConditionalDependencies() {
        // Given
        List<SubRequest> requests = List.of(
            // Initial request
            SubRequest.builder()
                .referenceId("check")
                .url("/api/status/check")
                .method("GET")
                .build(),

            // Execute only if status is "needs_setup"
            SubRequest.builder()
                .referenceId("setup")
                .url("/api/system/setup")
                .method("POST")
                .body(Map.of(
                    "condition", "${check.body.status}",
                    "runIf", "needs_setup"
                ))
                .dependencies(Set.of("check"))
                .build(),

            // Execute only if setup was needed
            SubRequest.builder()
                .referenceId("verify")
                .url("/api/system/verify")
                .method("GET")
                .dependencies(Set.of("setup"))
                .build()
        );

        // Mock conditional responses
        when(restTemplate.exchange(
            eq("/api/status/check"),
            eq(HttpMethod.GET),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("status", "needs_setup")));

        when(restTemplate.exchange(
            eq("/api/system/setup"),
            eq(HttpMethod.POST),
            argThat(request -> {
                Map<String, String> body = (Map<String, String>) request.getBody();
                return "needs_setup".equals(body.get("condition"));
            }),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("result", "setup_completed")));

        when(restTemplate.exchange(
            eq("/api/system/verify"),
            eq(HttpMethod.GET),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("status", "verified")));

        // When
        CompositeResponse response = compositeService.processRequests(
            CompositeRequest.builder()
                .subRequests(requests)
                .allOrNone(true)
                .build()
        );

        // Then
        assertFalse(response.isHasErrors());
        assertTrue(response.getResponses().containsKey("setup"));
        assertTrue(response.getResponses().containsKey("verify"));
    }

    @Test
    void shouldDetectComplexCircularDependencies() {
        // Given - Complex circular dependency: A -> B -> C -> D -> B
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("A")
                .url("/api/a")
                .method("GET")
                .build(),

            SubRequest.builder()
                .referenceId("B")
                .url("/api/b/${A.body.id}")
                .method("GET")
                .dependencies(Set.of("A"))
                .build(),

            SubRequest.builder()
                .referenceId("C")
                .url("/api/c/${B.body.id}")
                .method("GET")
                .dependencies(Set.of("B"))
                .build(),

            SubRequest.builder()
                .referenceId("D")
                .url("/api/d/${C.body.id}")
                .method("GET")
                .dependencies(Set.of("C", "B")) // Creates circular dependency
                .build()
        );

        // When/Then
        CircularDependencyException exception = assertThrows(
            CircularDependencyException.class,
            () -> compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(requests)
                    .build()
            )
        );

        assertTrue(exception.getMessage().contains("Circular dependency detected"));
    }

    @Test
    void shouldValidateMaximumDependencyDepth() {
        // Given - Chain exceeding maximum depth
        List<SubRequest> requests = new ArrayList<>();
        String prevId = "start";
        
        // Create a chain of 20 dependent requests
        for (int i = 0; i < 20; i++) {
            String currentId = "req" + i;
            requests.add(
                SubRequest.builder()
                    .referenceId(currentId)
                    .url("/api/chain/" + i)
                    .method("GET")
                    .dependencies(i == 0 ? Set.of() : Set.of(prevId))
                    .build()
            );
            prevId = currentId;
        }

        // When/Then
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(requests)
                    .build()
            )
        );

        assertTrue(exception.getMessage().contains("Maximum dependency depth exceeded"));
    }

    @Test
    void shouldValidateDependencyResolution() {
        // Given
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("first")
                .url("/api/data")
                .method("GET")
                .build(),

            SubRequest.builder()
                .referenceId("second")
                .url("/api/process/${first.body.nested.deeplyNested.nonexistent}")
                .method("GET")
                .dependencies(Set.of("first"))
                .build()
        );

        when(restTemplate.exchange(
            eq("/api/data"),
            eq(HttpMethod.GET),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of("simple", "value")));

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

    @Test
    void shouldHandleMissingDependencies() {
        // Given
        List<SubRequest> requests = List.of(
            SubRequest.builder()
                .referenceId("existing")
                .url("/api/existing")
                .method("GET")
                .build(),

            SubRequest.builder()
                .referenceId("dependent")
                .url("/api/dependent")
                .method("GET")
                .dependencies(Set.of("existing", "nonexistent"))
                .build()
        );

        // When/Then
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> compositeService.processRequests(
                CompositeRequest.builder()
                    .subRequests(requests)
                    .build()
            )
        );

        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    private void mockBranchResponses() {
        // Mock root response
        when(restTemplate.exchange(
            eq(baseUrl + "/api/root"),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "id", "root",
            "timestamp", System.currentTimeMillis()
        )));

        // Mock branch responses
        mockBranchResponse(baseUrl + "/api/project/create", "project-123");
        mockBranchResponse(baseUrl + "/api/team/create", "team-123");
        mockBranchResponse(baseUrl + "/api/team/team-123/members", "members-123");
        mockBranchResponse(baseUrl + "/api/resources/allocate", "res-123");
        mockBranchResponse(baseUrl + "/api/resources/res-123/budget", "budget-123");
        mockBranchResponse(baseUrl + "/api/schedule/create", "schedule-123");
        mockBranchResponse(baseUrl + "/api/schedule/schedule-123/milestones", "milestones-123");
    }

    private void mockBranchResponse(String url, String id) {
        when(restTemplate.exchange(
            eq(url),
            any(),
            any(),
            eq(Object.class)
        )).thenReturn(ResponseEntity.ok(Map.of(
            "id", id,
            "timestamp", System.currentTimeMillis()
        )));
    }

    private void verifyBranchExecution(CompositeResponse response) {
        Map<String, SubResponse> responses = response.getResponses();
        
        // Verify root executes first
        long rootTime = getTimestamp(responses.get("root"));
        
        // Verify branch starts execute after root
        assertTrue(getTimestamp(responses.get("team")) > rootTime);
        assertTrue(getTimestamp(responses.get("resources")) > rootTime);
        assertTrue(getTimestamp(responses.get("schedule")) > rootTime);
        
        // Verify branch completions
        assertTrue(getTimestamp(responses.get("members")) > 
                  getTimestamp(responses.get("team")));
        assertTrue(getTimestamp(responses.get("budget")) > 
                  getTimestamp(responses.get("resources")));
        assertTrue(getTimestamp(responses.get("milestones")) > 
                  getTimestamp(responses.get("schedule")));
    }

    private long getTimestamp(SubResponse response) {
        return ((Map<String, Long>) response.getBody()).get("timestamp");
    }
}

//package com.example.composite.service;
//
//import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.Mockito.when;
//
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.Set;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import com.example.composite.config.EndpointRegistry;
//import com.example.composite.config.EndpointRegistry.EndpointInfo;
//import com.example.composite.exception.CircularDependencyException;
//import com.example.composite.exception.ValidationException;
//import com.example.composite.model.context.ExecutionContext;
//import com.example.composite.model.request.CompositeRequest;
//import com.example.composite.model.request.SubRequestDto;
//import com.example.composite.model.response.SubResponse;
//
//import jakarta.validation.Validation;
//import jakarta.validation.Validator;
//
//@ExtendWith(MockitoExtension.class)
//class CompositeRequestValidatorTest {
//
//        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
//
//        @Mock
//        private EndpointRegistry endpointRegistry;
//
//        private CompositeRequestValidator requestValidator;
//
//        @BeforeEach
//        void setUp() {
//                requestValidator = new CompositeRequestValidator(validator, endpointRegistry);
//        }
//
//        @Test
//        void shouldValidateUniqueReferenceIds() {
//                // Given
//                List<SubRequestDto> requests = List.of(
//                                SubRequestDto.builder()
//                                                .referenceId("same")
//                                                .url("/api/first")
//                                                .method("GET")
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("same")
//                                                .url("/api/second")
//                                                .method("GET")
//                                                .build());
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                // When/Then
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(request));
//                assertTrue(exception.getMessage().contains("Duplicate reference ID"));
//        }
//
//        @Test
//        void shouldDetectCircularDependencies() {
//                // Given
//                List<SubRequestDto> requests = List.of(
//                                SubRequestDto.builder()
//                                                .referenceId("A")
//                                                .url("/api/a")
//                                                .method("GET")
//                                                .dependencies(Set.of("C"))
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("B")
//                                                .url("/api/b")
//                                                .method("GET")
//                                                .dependencies(Set.of("A"))
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("C")
//                                                .url("/api/c")
//                                                .method("GET")
//                                                .dependencies(Set.of("B"))
//                                                .build());
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                // When/Then
//                assertThrows(CircularDependencyException.class,
//                                () -> requestValidator.validateRequest(request));
//        }
//
//        @Test
//        void shouldValidateMaximumDependencyDepth() {
//                // Given
//                List<SubRequestDto> requests = new ArrayList<>();
//                String prevId = "start";
//
//                // Create a chain of 20 dependent requests
//                for (int i = 0; i < 20; i++) {
//                        String currentId = "req" + i;
//                        requests.add(
//                                        SubRequestDto.builder()
//                                                        .referenceId(currentId)
//                                                        .url("/api/chain/" + i)
//                                                        .method("GET")
//                                                        .dependencies(i == 0 ? Set.of() : Set.of(prevId))
//                                                        .build());
//                        prevId = currentId;
//                }
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                // When/Then
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(request));
//
//                assertTrue(exception.getMessage().contains("Maximum dependency depth exceeded"));
//        }
//
//        @Test
//        void shouldValidateEndpointAccess() {
//                // Given
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url("/api/test")
//                                .method("POST")
//                                .build();
//
//                when(endpointRegistry.getEndpointInformations("POST", "/api/test"))
//                                .thenReturn(Optional.empty());
//
//                // When/Then
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateEndpointAccess(request));
//                assertTrue(exception.getMessage().contains("Endpoint not available"));
//        }
//
//        @Test
//        void shouldValidateHttpMethodAndBody() {
//                // Test POST without body
//                SubRequestDto postRequest = SubRequestDto.builder()
//                                .referenceId("post")
//                                .url("/api/test")
//                                .method("POST")
//                                .build();
//
//                when(endpointRegistry.getEndpointInformations("POST", "/api/test"))
//                                .thenReturn(Optional.of(EndpointInfo.builder().build()));
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateEndpointAccess(postRequest));
//                assertTrue(exception.getMessage().contains("body is required"));
//
//                // Test GET with body
//                SubRequestDto getRequest = SubRequestDto.builder()
//                                .referenceId("get")
//                                .url("/api/test")
//                                .method("GET")
//                                .body(new Object())
//                                .build();
//
//                when(endpointRegistry.getEndpointInformations("GET", "/api/test"))
//                                .thenReturn(Optional.of(EndpointInfo.builder().build()));
//
//                exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateEndpointAccess(getRequest));
//                assertTrue(exception.getMessage().contains("body is not allowed"));
//        }
//
//        @Test
//        void shouldValidateReferences() {
//                // Given
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("dependent")
//                                .url("/api/${prev.body.id}")
//                                .method("GET")
//                                .dependencies(Set.of("prev"))
//                                .build();
//
//                ExecutionContext context = new ExecutionContext();
//                context.getResponseMap().put("prev",
//                                SubResponse.builder()
//                                                .body(Map.of("id", "123"))
//                                                .build());
//
//                // When/Then
//                assertDoesNotThrow(() -> requestValidator.validateReferences(request, context));
//
//                // Test missing dependency
//                context.getResponseMap().clear();
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateReferences(request, context));
//                assertTrue(exception.getMessage().contains("Missing dependencies"));
//        }
//
//        @Test
//        void shouldValidateUrlFormat() {
//
//                // Test valid URLs
//                assertDoesNotThrow(() -> requestValidator.validateResolvedUrlFormat("/api/test"));
//                assertDoesNotThrow(() -> requestValidator.validateResolvedUrlFormat("http://external.api/test"));
//
//                // Test invalid URLs
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateResolvedUrlFormat("invalid\\url"));
//                assertTrue(exception.getMessage().contains("Invalid URL format"));
//
//                exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateResolvedUrlFormat("api/no-leading-slash"));
//                assertTrue(exception.getMessage().contains("must be absolute or start with /"));
//        }
//
//        @Test
//        void shouldValidateEmptyRequests() {
//                // Given
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(Collections.emptyList())
//                                .build();
//
//                // When/Then
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(request));
//                assertTrue(exception.getMessage().contains("At least one sub-request is required"));
//        }
//
//        @Test
//        void shouldValidateDependencyExists() {
//                // Given
//                List<SubRequestDto> requests = List.of(
//                                SubRequestDto.builder()
//                                                .referenceId("dependent")
//                                                .url("/api/test")
//                                                .method("GET")
//                                                .dependencies(Set.of("nonexistent"))
//                                                .build());
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                // When/Then
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(request));
//                assertTrue(exception.getMessage().contains("Missing dependency reference"));
//        }
//
//        @Test
//        void shouldValidateReferenceFormat() {
//                // Given
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url("/api/${invalid..syntax}")
//                                .method("GET")
//                                .build();
//
//                ExecutionContext context = new ExecutionContext();
//
//                // When/Then
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateReferences(request, context));
//                assertTrue(exception.getMessage().contains("Invalid reference format"));
//        }
//
//        @Test
//        void shouldHandleNullBody() {
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url("/api/test")
//                                .method("POST")
//                                .body(null)
//                                .build();
//
//                when(endpointRegistry.getEndpointInformations("POST", "/api/test"))
//                                .thenReturn(Optional.of(EndpointInfo.builder().build()));
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateEndpointAccess(request));
//                assertTrue(exception.getMessage().contains("body is required"));
//        }
//
//        @Test
//        void shouldValidateHttpMethod() {
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url("/api/test")
//                                .method("INVALID")
//                                .build();
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateEndpointAccess(request));
//                assertTrue(exception.getMessage().contains("Invalid HTTP method"));
//        }
//
//        @Test
//        void shouldValidateEmptyReferenceId() {
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("")
//                                .url("/api/test")
//                                .method("GET")
//                                .build();
//
//                CompositeRequest compositeRequest = CompositeRequest.builder().subRequests(List.of(request)).build();
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(compositeRequest));
//                assertTrue(exception.getMessage().contains("Reference ID must be alphanumeric"));
//        }
//
//        @Test
//        void shouldValidateNullUrl() {
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url(null)
//                                .method("GET")
//                                .build();
//
//                CompositeRequest compositeRequest = CompositeRequest.builder().subRequests(List.of(request)).build();
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(compositeRequest));
//                assertTrue(exception.getMessage().contains("URL is required"));
//        }
//
//        @Test
//        void shouldValidateSelfReferentialDependency() {
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("A")
//                                .url("/api/test")
//                                .method("GET")
//                                .dependencies(Set.of("A"))
//                                .build();
//
//                CompositeRequest compositeRequest = CompositeRequest.builder()
//                                .subRequests(List.of(request))
//                                .build();
//
//                CircularDependencyException exception = assertThrows(CircularDependencyException.class,
//                                () -> requestValidator.validateRequest(compositeRequest));
//                assertTrue(exception.getMessage().contains("Circular dependency detected in path"));
//        }
//
//        @Test
//        void shouldValidateHeaderReferences() {
//                Map<String, String> headers = Map.of("Authorization", "${invalid..header.ref}");
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url("/api/test")
//                                .method("GET")
//                                .headers(headers)
//                                .build();
//
//                ExecutionContext context = new ExecutionContext();
//
//                when(endpointRegistry.getEndpointInformations("GET", "/api/test"))
//                                .thenReturn(Optional.of(EndpointInfo.builder().build()));
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateReferences(request, context));
//                assertTrue(exception.getMessage().contains("Invalid header reference format"));
//        }
//
//        @Test
//        void shouldValidateMaximumRequestCount() {
//                List<SubRequestDto> requests = new ArrayList<>();
//                // Create 101 requests (assuming max is 100)
//                for (int i = 0; i < 101; i++) {
//                        requests.add(SubRequestDto.builder()
//                                        .referenceId("req" + i)
//                                        .url("/api/test")
//                                        .method("GET")
//                                        .build());
//                }
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(request));
//                assertTrue(exception.getMessage().contains("Maximum number of requests exceeded"));
//        }
//
//        @Test
//        void shouldValidateNestedReferences() {
//                SubRequestDto request = SubRequestDto.builder()
//                                .referenceId("test")
//                                .url("/api/${first.body.${second.body.id}}")
//                                .method("GET")
//                                .dependencies(Set.of("first", "second"))
//                                .build();
//
//                ExecutionContext context = new ExecutionContext();
//                context.getResponseMap().put("first", SubResponse.builder()
//                                .body(Map.of("123", "value"))
//                                .build());
//                context.getResponseMap().put("second", SubResponse.builder()
//                                .body(Map.of("id", "123"))
//                                .build());
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateReferences(request, context));
//                assertTrue(exception.getMessage().contains("Nested references are not supported"));
//        }
//
//        @Test
//        void shouldValidateConcurrentDependencies() {
//                List<SubRequestDto> requests = List.of(
//                                SubRequestDto.builder()
//                                                .referenceId("A")
//                                                .url("/api/a")
//                                                .method("GET")
//                                                .dependencies(Set.of("B"))
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("B")
//                                                .url("/api/b")
//                                                .method("GET")
//                                                .build());
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                ValidationException exception = assertThrows(ValidationException.class,
//                                () -> requestValidator.validateRequest(request));
//                assertTrue(exception.getMessage().contains("Concurrent requests cannot have dependencies"));
//        }
//
//        @Test
//        void shouldDetectComplexCyclicDependencies() {
//                List<SubRequestDto> requests = List.of(
//                                SubRequestDto.builder()
//                                                .referenceId("A")
//                                                .url("/api/a")
//                                                .method("GET")
//                                                .dependencies(Set.of("B", "C"))
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("B")
//                                                .url("/api/b")
//                                                .method("GET")
//                                                .dependencies(Set.of("D"))
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("C")
//                                                .url("/api/c")
//                                                .method("GET")
//                                                .dependencies(Set.of("D"))
//                                                .build(),
//                                SubRequestDto.builder()
//                                                .referenceId("D")
//                                                .url("/api/d")
//                                                .method("GET")
//                                                .dependencies(Set.of("A"))
//                                                .build());
//
//                CompositeRequest request = CompositeRequest.builder()
//                                .subRequests(requests)
//                                .build();
//
//                assertThrows(CircularDependencyException.class,
//                                () -> requestValidator.validateRequest(request));
//        }
//}
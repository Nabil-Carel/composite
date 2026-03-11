package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeRequestServiceImplTest {

    @Mock
    private EndpointRegistry endpointRegistry;
    @Mock
    private CompositeRequestValidator compositeRequestValidator;
    @Mock
    private ReferenceResolverService referenceResolver;
    @Mock
    private AuthenticationForwardingService authForwardingService;
    @Mock
    private WebClient webClient;
    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private WebClient.RequestBodySpec requestBodySpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private ClientResponse clientResponse;
    @Mock
    private ResponseTracker responseTracker;
    @Mock
    private HttpServletRequest servletRequest;

    private ObjectMapper objectMapper;
    private CompositeProperties properties;
    private ConcurrentMap<String, ResponseTracker> responseStore;
    private CompositeRequestServiceImpl service;

    private static final String REQUEST_ID = "test-request-id";
    private static final String REFERENCE_ID = "test-ref";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new CompositeProperties();
        properties.setRequestTimeout(Duration.ofSeconds(30));
        responseStore = new ConcurrentHashMap<>();
        responseStore.put(REQUEST_ID, responseTracker);

        service = new CompositeRequestServiceImpl(
            endpointRegistry,
            objectMapper,
            responseStore,
            compositeRequestValidator,
            referenceResolver,
            properties,
            authForwardingService,
            webClient
        );
    }

    // ========== Endpoint Validation Tests ==========

    @Test
    void forwardSubrequest_whenEndpointNotRegistered_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/unknown", "GET");

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.empty());

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().toString()).contains("Invalid endpoint");
    }

    @Test
    void forwardSubrequest_whenResolvedUrlNotRegistered_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/users/${user.id}", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", String.class);

        when(endpointRegistry.getEndpointInformations("GET", "/api/users/${user.id}"))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/malicious/path");
        when(endpointRegistry.getEndpointInformations("GET", "/api/malicious/path"))
            .thenReturn(Optional.empty());

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().toString()).contains("does not match a registered endpoint");
    }

    // ========== Reference Resolution Tests ==========

    @Test
    void forwardSubrequest_whenReferenceResolutionFails_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/users/${invalid.ref}", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", String.class);

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenThrow(new RuntimeException("Reference not found"));

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().toString()).contains("Reference resolution failed");
    }

    // ========== URL Format Validation Tests ==========

    @Test
    void forwardSubrequest_whenUrlFormatInvalid_returnsErrorResponse() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", String.class);

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn("Invalid URL format");

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    // ========== Successful Request Tests ==========

    @Test
    void forwardSubrequest_whenSuccessful_addsResponseToTracker() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);
        Map<String, String> responseBody = Map.of("id", "123", "name", "Test");

        setupSuccessfulWebClientMock(responseBody, Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(responseBody);
    }

    @Test
    void forwardSubrequest_withVoidReturnType_addsResponseWithNullBody() {
        SubRequest subRequest = createSubRequest("/api/orders/123", "DELETE");
        EndpointInfo endpointInfo = createEndpointInfo("/api/orders/{id}", Void.class);

        setupVoidWebClientMock();
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/orders/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    // ========== Error Response Tests ==========

    @Test
    void forwardSubrequest_when4xxError_addsErrorResponseToTracker() {
        SubRequest subRequest = createSubRequest("/api/users/notfound", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        setupErrorWebClientMock(404, "Not Found");
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/notfound");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void forwardSubrequest_when5xxError_addsErrorResponseToTracker() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        setupErrorWebClientMock(500, "Internal Server Error");
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(500);
    }

    @Test
    void forwardSubrequest_whenErrorWithEmptyBody_addsResponseWithEmptyString() {
        SubRequest subRequest = createSubRequest("/api/users/notfound", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        setupErrorWebClientMockWithEmptyBody(404);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/notfound");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(404);
        assertThat(response.getBody()).isEqualTo("");
    }

    // ========== WebClient Error Handling Tests ==========

    @Test
    void forwardSubrequest_whenWebClientThrowsException_addsServiceUnavailableResponse() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        setupWebClientExceptionMock(new RuntimeException("Connection refused"));
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        ArgumentCaptor<SubResponse> responseCaptor = ArgumentCaptor.forClass(SubResponse.class);
        verify(responseTracker).addResponse(eq(REFERENCE_ID), responseCaptor.capture());

        SubResponse response = responseCaptor.getValue();
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getBody().toString()).contains("Error executing subrequest");
    }

    // ========== Header Injection Tests ==========

    @Test
    void forwardSubrequest_whenHeaderInjectionEnabled_addsCompositeHeaders() {
        SubRequest subRequest = createSubRequest("/api/users/123", "GET");
        EndpointInfo endpointInfo = createEndpointInfo("/api/users/{id}", Map.class);

        properties.getHeaderInjection().setEnabled(true);
        properties.getHeaderInjection().setRequestHeader("X-Composite-Request");
        properties.getHeaderInjection().setRequestIdHeader("X-Composite-Request-Id");
        properties.getHeaderInjection().setSubRequestIdHeader("X-Composite-Sub-Request-Id");

        setupSuccessfulWebClientMock(Map.of("id", "123"), Map.class);
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users/123");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        verify(responseTracker).addResponse(eq(REFERENCE_ID), any(SubResponse.class));
    }

    // ========== Request Body Tests ==========

    @Test
    void forwardSubrequest_withRequestBody_sendsBodyInRequest() throws Exception {
        JsonNode body = objectMapper.readTree("{\"name\": \"Test\"}");
        SubRequest subRequest = createSubRequestWithBody("/api/users", "POST", body);
        EndpointInfo endpointInfo = createEndpointInfo("/api/users", Map.class);

        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        when(clientResponse.bodyToMono(eq(Map.class))).thenReturn(Mono.just(Map.of("id", "new-id")));
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(Optional.of(endpointInfo));
        when(referenceResolver.resolveUrl(any(), anyString()))
            .thenReturn("/api/users");
        when(compositeRequestValidator.validateResolvedUrlFormat(anyString()))
            .thenReturn(null);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        // When there's a body, exchangeToMono is called on requestHeadersSpec, not requestBodySpec
        when(requestHeadersSpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });

        service.forwardSubrequest(subRequest, REQUEST_ID, servletRequest).block();

        verify(responseTracker).addResponse(eq(REFERENCE_ID), any(SubResponse.class));
    }

    // ========== Helper Methods ==========

    private SubRequest createSubRequest(String url, String method) {
        SubRequestDto dto = SubRequestDto.builder()
            .url(url)
            .method(method)
            .referenceId(REFERENCE_ID)
            .build();
        SubRequest subRequest = new SubRequest(dto);
        subRequest.setHeaders(new HashMap<>());
        return subRequest;
    }

    private SubRequest createSubRequestWithBody(String url, String method, JsonNode body) {
        SubRequestDto dto = SubRequestDto.builder()
            .url(url)
            .method(method)
            .referenceId(REFERENCE_ID)
            .body(body)
            .build();
        SubRequest subRequest = new SubRequest(dto);
        subRequest.setHeaders(new HashMap<>());
        return subRequest;
    }

    private EndpointInfo createEndpointInfo(String pattern, Class<?> returnClass) {
        return EndpointInfo.builder()
            .pattern(pattern)
            .method("GET")
            .returnClass(returnClass)
            .build();
    }

    private <T> void setupSuccessfulWebClientMock(T responseBody, Class<T> responseClass) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.OK);
        when(clientResponse.bodyToMono(eq(responseClass))).thenReturn(Mono.just(responseBody));

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupVoidWebClientMock() {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.NO_CONTENT);
        when(clientResponse.releaseBody()).thenReturn(Mono.empty());

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupErrorWebClientMock(int statusCode, String errorBody) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.valueOf(statusCode));
        when(clientResponse.bodyToMono(eq(String.class))).thenReturn(Mono.just(errorBody));

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupErrorWebClientMockWithEmptyBody(int statusCode) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(clientResponse.statusCode()).thenReturn(HttpStatus.valueOf(statusCode));
        when(clientResponse.bodyToMono(eq(String.class))).thenReturn(Mono.empty());

        when(requestBodySpec.exchangeToMono(any())).thenAnswer(invocation -> {
            java.util.function.Function<ClientResponse, Mono<?>> handler = invocation.getArgument(0);
            return handler.apply(clientResponse);
        });
    }

    private void setupWebClientExceptionMock(Exception exception) {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);

        when(requestBodySpec.exchangeToMono(any())).thenReturn(Mono.error(exception));
    }
}

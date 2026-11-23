package io.github.nabilcarel.composite.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.ResponseTrackerImpl;
import io.github.nabilcarel.composite.model.SubRequestCoordinatorImpl;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeDebugInfo;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class CompositeRequestServiceImpl implements CompositeRequestService{

    private static final boolean SPRING_SECURITY_PRESENT = ClassUtils.isPresent(
            "org.springframework.security.web.context.RequestAttributeSecurityContextRepository",
            CompositeRequestServiceImpl.class.getClassLoader());

    /** Keeps numbers exact and rejects content after the first JSON value. */
    private static final UnaryOperator<ObjectReader> JSON_RESPONSE_OPTIONS = reader -> reader
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);

    private final EndpointRegistry endpointRegistry;
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;
    private final CompositeRequestValidator compositeRequestValidator;
    private final ReferenceResolverService referenceResolver;
    private final CompositeProperties properties;
    private final AuthenticationForwardingService authForwardingService;
    @Qualifier("compositeWebClient")
    private final WebClient webClient;

    public Mono<Void> forwardSubrequest(
            SubRequest subRequest,
            String requestId,
            SubRequestContext context
    ) {

        Optional<EndpointInfo> endpointInfo = endpointRegistry.getEndpointInformations(
                subRequest.getMethod().toUpperCase(), subRequest.getUrl()
        );

        if (endpointInfo.isEmpty()) {
            log.error("Invalid endpoint received: {}", subRequest.getUrl());
            addErrorToTracker(requestId, subRequest.getReferenceId(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Invalid endpoint received: " + subRequest.getUrl()
                            + ". Check the method and path, and that the handler has @CompositeEndpoint.");
            return Mono.empty();
        }

        // Snapshot original values before resolution for debug info
        String originalUrl = subRequest.getUrl();
        Object originalBody = null;
        if (properties.isDebugEnabled() && subRequest.getBody() != null) {
            originalBody = subRequest.getBody().deepCopy();
        }

        String resolvedUrl;
        try {
            resolvedUrl = referenceResolver.resolveUrl(subRequest, requestId);
            referenceResolver.resolveHeaders(subRequest, requestId);
            referenceResolver.resolveBody(subRequest, requestId);
        } catch (Exception e) {
            log.error("Reference resolution failed for {}: {}", subRequest.getReferenceId(), e.getMessage(), e);
            addErrorToTracker(requestId, subRequest.getReferenceId(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Reference resolution failed: " + resolutionFailureMessage(e));
            return Mono.empty();
        }

        if (properties.isDebugEnabled()) {
            CompositeDebugInfo debugInfo = context.debugInfo();
            if (debugInfo != null) {
                CompositeDebugInfo.SubRequestDebugInfo subDebug = CompositeDebugInfo.SubRequestDebugInfo.builder()
                        .originalUrl(originalUrl)
                        .resolvedUrl(resolvedUrl)
                        .originalBody(originalBody)
                        .resolvedBody(subRequest.getBody())
                        .build();
                debugInfo.getResolvedRequests().put(subRequest.getReferenceId(), subDebug);
            }
        }

        // Re-validate resolved URL against registered endpoints to prevent path traversal/SSRF
        Optional<EndpointInfo> resolvedEndpointInfo = endpointRegistry.getEndpointInformations(
                subRequest.getMethod().toUpperCase(), resolvedUrl
        );

        if (resolvedEndpointInfo.isEmpty()) {
            log.error("Resolved URL does not match registered endpoint: {} (original: {})",
                    resolvedUrl, subRequest.getUrl());
            addErrorToTracker(requestId, subRequest.getReferenceId(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Resolved URL does not match a registered endpoint: " + resolvedUrl
                            + ". A referenced value routed this sub-request to an unavailable path.");
            return Mono.empty();
        }

        String error = compositeRequestValidator.validateResolvedUrlFormat(resolvedUrl);

        if (error != null) {
            log.error("Invalid URL format: {}", error);
            addErrorToTracker(requestId, subRequest.getReferenceId(),
                    HttpStatus.BAD_REQUEST.value(), error);
            return Mono.empty();
        }

        WebClient.RequestBodySpec requestBodySpec = webClient.method(HttpMethod.valueOf(subRequest.getMethod().toUpperCase()))
                .uri(resolvedUrl)
                .headers(httpHeaders -> {
                    httpHeaders.addAll(context.forwardedHeaders());
                    if (subRequest.getResolvedHeaders() != null) {
                        subRequest.getResolvedHeaders().forEach(httpHeaders::set);
                    }
                    httpHeaders.set(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");

                    if(properties.getHeaderInjection().isEnabled()){
                        httpHeaders.add(properties.getHeaderInjection().getRequestHeader(), "true");
                        httpHeaders.add(properties.getHeaderInjection().getRequestIdHeader(), requestId);
                        httpHeaders.add(properties.getHeaderInjection().getSubRequestIdHeader(), subRequest.getReferenceId());
                    }
                });

        WebClient.RequestHeadersSpec<?> requestSpec = requestBodySpec;

        if (supportsRequestBody(subRequest.getMethod()) && subRequest.getBody() != null && !subRequest.getBody().isEmpty()) {
            requestSpec = requestBodySpec.bodyValue(subRequest.getBody());
        }

        // Apply per-subrequest timeout if configured, otherwise use default
        Duration timeout = properties.getSubRequestTimeout() != null
            ? properties.getSubRequestTimeout()
            : properties.getRequestTimeout();

        return requestSpec
                .exchangeToMono(response -> toSubResponse(response, subRequest.getReferenceId()))
                .timeout(timeout)
                .doOnSuccess(subResponse -> {
                    ResponseTracker tracker = responseStore.get(requestId);
                    if (tracker != null) {
                        tracker.addResponse(subRequest.getReferenceId(), subResponse);
                    } else {
                        // Tracker missing - this is a critical error, request will hang
                        log.error("ResponseTracker not found for ID: {} - request will not complete", requestId);
                    }
                })
                .onErrorResume(throwable -> {
                    log.error("Error forwarding subrequest {}: {}", subRequest.getReferenceId(), throwable.getMessage(), throwable);
                    addErrorToTracker(requestId, subRequest.getReferenceId(),
                            HttpStatus.SERVICE_UNAVAILABLE.value(),
                            "Error executing subrequest: " + transportFailureMessage(throwable));
                    return Mono.empty();
                })
                .then();
    }

    /** Only library validation messages are safe to return to the client. */
    private static String resolutionFailureMessage(Exception failure) {
        return failure instanceof ReferenceResolutionException || failure instanceof IllegalArgumentException
                ? failure.getMessage()
                : "the reference could not be read.";
    }

    /** Client exception text can contain internal hosts and ports, so it is never returned. */
    private static String transportFailureMessage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return "the endpoint did not respond in time.";
            }
            if (cause instanceof DataBufferLimitException) {
                return "the response is larger than the allowed size.";
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return "the endpoint could not be reached.";
    }

    private boolean supportsRequestBody(String method) {
        return !method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD");
    }

    private Mono<SubResponse> toSubResponse(ClientResponse response, String referenceId) {
        HttpStatusCode status = response.statusCode();
        SubResponse emptyResponse = SubResponse.builder()
                .referenceId(referenceId)
                .httpStatus(status.value())
                .build();

        if (status.is2xxSuccessful()) {
            return response.bodyToMono(String.class)
                    .map(text -> successResponse(response, referenceId, text))
                    .defaultIfEmpty(emptyResponse);
        }

        if (status.isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(errorBody -> {
                        log.error("Error from subrequest: {} - {}", status, errorBody);
                        return SubResponse.builder()
                                .referenceId(referenceId)
                                .httpStatus(status.value())
                                .body(errorBody)
                                .build();
                    });
        }

        // 1xx informational or 3xx redirect
        log.warn("Received non-standard response status: {}", status);
        return response.releaseBody().thenReturn(emptyResponse);
    }

    private SubResponse successResponse(ClientResponse response, String referenceId, String text) {
        SubResponse.SubResponseBuilder builder = SubResponse.builder()
                .referenceId(referenceId)
                .httpStatus(response.statusCode().value());

        if (text.isBlank()) {
            return builder.build();
        }

        if (!isJson(response.headers().contentType().orElse(null))) {
            return builder.body(text).build();
        }

        try {
            return builder.body(JSON_RESPONSE_OPTIONS.apply(objectMapper.reader()).readTree(text)).build();
        } catch (JsonProcessingException e) {
            log.warn("Sub-request {} returned invalid JSON", referenceId);
            return builder
                    .httpStatus(HttpStatus.BAD_GATEWAY.value())
                    .body("Response body is not valid JSON, although its Content-Type says it is. Fix the endpoint response.")
                    .build();
        }
    }

    private static boolean isJson(MediaType contentType) {
        return contentType != null
                && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || (contentType.getSubtype() != null && contentType.getSubtype().endsWith("+json")));
    }

    private void addErrorToTracker(String requestId, String referenceId, int status, String message) {
        ResponseTracker tracker = responseStore.get(requestId);
        if (tracker != null) {
            SubResponse errorResponse = SubResponse.builder()
                    .httpStatus(status)
                    .referenceId(referenceId)
                    .body(message)
                    .build();
            tracker.addResponse(referenceId, errorResponse);
        } else {
            log.error("ResponseTracker not found for requestId: {} - cannot record error for {}", requestId, referenceId);
        }
    }

    @Override
    public CompletableFuture<ResponseEntity<CompositeResponse>> execute(
            CompositeRequest compositeRequest, HttpServletRequest request, HttpServletResponse response) {
        if (SPRING_SECURITY_PRESENT) {
            AsyncSecurityContextSupport.preserve(request, response);
        }

        List<String> errors = compositeRequestValidator.validateRequest(compositeRequest);

        if (!errors.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(
                    CompositeResponse.builder().hasErrors(true).errors(errors).build()));
        }

        String requestId = UUID.randomUUID().toString();
        Map<String, SubRequest> requestMap = compositeRequest.getSubRequests().stream()
                .collect(Collectors.toMap(SubRequestDto::getReferenceId, SubRequest::new));
        Map<String, Set<String>> dependencyMap = requestMap.values().stream()
                .collect(Collectors.toMap(SubRequest::getReferenceId, SubRequest::getDependencies));

        CompositeDebugInfo debugInfo = properties.isDebugEnabled()
                ? CompositeDebugInfo.builder()
                        .dependencyGraph(dependencyMap)
                        .resolvedRequests(new ConcurrentHashMap<>())
                        .build()
                : null;

        HttpHeaders forwardedHeaders = new HttpHeaders();
        authForwardingService.forwardAuthentication(request, forwardedHeaders);
        SubRequestContext context = new SubRequestContext(
                HttpHeaders.readOnlyHttpHeaders(forwardedHeaders), debugInfo);

        ResponseTracker tracker = new ResponseTrackerImpl(requestMap.size());
        responseStore.put(requestId, tracker);
        CompositeBatchContext batchContext = new CompositeBatchContextImpl(
                tracker, new SubRequestCoordinatorImpl(dependencyMap), requestMap, this, requestId, context,
                properties.getMaxConcurrentSubRequests(), compositeRequest.isStopOnFailure());

        Runnable cleanup = () -> {
            batchContext.cancel();
            responseStore.remove(requestId);
        };

        CompletableFuture<ResponseEntity<CompositeResponse>> result = tracker.getFuture()
                .orTimeout(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .handle((compositeResponse, failure) -> {
                    cleanup.run();
                    return toResponseEntity(compositeResponse, failure, tracker, debugInfo);
                });

        // A cancelled stage skips the handler above, so cancellation cleans up directly.
        result.whenComplete((ignored, failure) -> {
            if (failure instanceof CancellationException) {
                cleanup.run();
                tracker.cancel(failure);
            }
        });

        try {
            batchContext.startInitialRequests();
        } catch (RuntimeException e) {
            tracker.cancel(e);
        }

        return result;
    }

    private ResponseEntity<CompositeResponse> toResponseEntity(CompositeResponse response, Throwable failure,
                                                               ResponseTracker tracker, CompositeDebugInfo debugInfo) {
        if (failure == null) {
            response.setDebug(debugInfo);
            return ResponseEntity.ok(response);
        }

        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        boolean timedOut = cause instanceof TimeoutException;

        if (cause instanceof CancellationException) {
            log.debug("Composite execution cancelled");
        } else {
            log.error("Composite execution failed: {}", cause.getMessage(), cause);
        }

        CompositeResponse errorResponse = CompositeResponse.builder()
                .responses(new HashMap<>(tracker.getSubResponseMap()))
                .hasErrors(true)
                .errors(List.of(timedOut
                        ? "Composite request timed out before every sub-request finished. "
                                + "Completed results are included, and running handlers may still finish."
                        : "Composite execution failed because of an internal error. Check the server logs."))
                .debug(debugInfo)
                .build();

        return ResponseEntity
                .status(timedOut ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse);
    }

    public Set<EndpointInfo> getAvailableEndpoints() {
        return endpointRegistry.getAvailableEndpoints();
    }
}

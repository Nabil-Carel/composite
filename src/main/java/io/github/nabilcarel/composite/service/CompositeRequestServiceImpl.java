package io.github.nabilcarel.composite.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import io.github.nabilcarel.composite.internal.BatchExecution;
import io.github.nabilcarel.composite.internal.DefaultCancellationSignal;
import io.github.nabilcarel.composite.internal.DispatchMarker;
import io.github.nabilcarel.composite.internal.ResponseTooLargeException;
import io.github.nabilcarel.composite.internal.SubRequestForwarder;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeDebugInfo;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.observability.CompositeBatchEvent;
import io.github.nabilcarel.composite.observability.CompositeSubRequestEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;

@RequiredArgsConstructor
@Slf4j
public class CompositeRequestServiceImpl implements CompositeRequestService, SubRequestForwarder {

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
    private final ActiveBatches activeBatches;
    private final CompositeRequestValidator compositeRequestValidator;
    private final ReferenceResolverService referenceResolver;
    private final CompositeProperties properties;
    private final AuthenticationForwardingService authForwardingService;
    private final SubRequestDispatcher dispatcher;
    private final DispatchMarker dispatchMarker;
    private volatile ObjectReader jsonResponseReader;

    @Override
    public Mono<Void> forwardSubrequest(
            SubRequest subRequest,
            BatchExecution batch
    ) {
        String requestId = batch.id();
        SubRequestContext context = batch.context();

        Optional<EndpointInfo> endpointInfo = endpointRegistry.getEndpointInformations(
                subRequest.getMethod().toUpperCase(), subRequest.getUrl()
        );

        if (endpointInfo.isEmpty()) {
            log.error("Invalid endpoint received: {}", subRequest.getUrl());
            recordError(batch, subRequest.getReferenceId(),
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
            resolvedUrl = referenceResolver.resolveUrl(subRequest, batch.responses());
            referenceResolver.resolveHeaders(subRequest, batch.responses());
            referenceResolver.resolveBody(subRequest, batch.responses());
        } catch (Exception e) {
            log.error("Reference resolution failed for {}: {}", subRequest.getReferenceId(), e.getMessage(), e);
            recordError(batch, subRequest.getReferenceId(),
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
            recordError(batch, subRequest.getReferenceId(),
                    HttpStatus.BAD_REQUEST.value(),
                    "Resolved URL does not match a registered endpoint: " + resolvedUrl
                            + ". A referenced value routed this sub-request to an unavailable path.");
            return Mono.empty();
        }

        String error = compositeRequestValidator.validateResolvedUrlFormat(resolvedUrl);

        if (error != null) {
            log.error("Invalid URL format: {}", error);
            recordError(batch, subRequest.getReferenceId(),
                    HttpStatus.BAD_REQUEST.value(), error);
            return Mono.empty();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(context.forwardedHeaders());
        if (subRequest.getResolvedHeaders() != null) {
            subRequest.getResolvedHeaders().forEach(headers::set);
        }
        headers.set(CompositeDispatchInterceptor.DISPATCH_HEADER, dispatchMarker.value());

        if (properties.getHeaderInjection().isEnabled()) {
            headers.add(properties.getHeaderInjection().getRequestHeader(), "true");
            headers.add(properties.getHeaderInjection().getRequestIdHeader(), requestId);
            headers.add(properties.getHeaderInjection().getSubRequestIdHeader(), subRequest.getReferenceId());
        }

        JsonNode body = supportsRequestBody(subRequest.getMethod())
                && subRequest.getBody() != null
                && !subRequest.getBody().isNull() && !subRequest.getBody().isMissingNode()
                ? subRequest.getBody()
                : null;
        PreparedSubRequest prepared = new PreparedSubRequest(subRequest.getReferenceId(),
                HttpMethod.valueOf(subRequest.getMethod().toUpperCase()), resolvedUrl,
                HttpHeaders.readOnlyHttpHeaders(headers), body);

        // Apply per-subrequest timeout if configured, otherwise use default
        Duration timeout = properties.getSubRequestTimeout() != null
            ? properties.getSubRequestTimeout()
            : properties.getRequestTimeout();

        CompositeSubRequestEvent event = new CompositeSubRequestEvent();
        AtomicInteger recordedStatus = new AtomicInteger();
        String endpoint = resolvedEndpointInfo.get().getPattern();

        return dispatch(prepared)
                // A refused connection means the request was never sent, so it is safe to retry.
                .retryWhen(CONNECT_RETRY)
                .map(result -> toSubResponse(result, subRequest.getReferenceId()))
                .timeout(timeout)
                .doOnSubscribe(subscription -> event.begin())
                .doOnSuccess(subResponse -> {
                    if (subResponse != null) {
                        recordedStatus.set(subResponse.getHttpStatus());
                    }
                    batch.addResponse(subRequest.getReferenceId(), subResponse);
                })
                .onErrorResume(throwable -> {
                    log.error("Error forwarding subrequest {}: {}", subRequest.getReferenceId(), throwable.getMessage(), throwable);
                    recordedStatus.set(HttpStatus.SERVICE_UNAVAILABLE.value());
                    recordError(batch, subRequest.getReferenceId(),
                            HttpStatus.SERVICE_UNAVAILABLE.value(),
                            "Error executing subrequest: " + transportFailureMessage(throwable));
                    return Mono.empty();
                })
                // Finishing the batch disposes the last call too, so only a call without a result was cancelled.
                .doFinally(signal -> event.finish(requestId, subRequest.getReferenceId(),
                        subRequest.getMethod().toUpperCase(), endpoint, recordedStatus.get(),
                        signal == SignalType.CANCEL && recordedStatus.get() == 0))
                .then();
    }

    /**
     * Retries connections the server refused, for example while a burst of new loopback
     * connections fills Tomcat's accept queue. The sub-request timeout still bounds the total.
     */
    private static final Retry CONNECT_RETRY = Retry.backoff(3, Duration.ofMillis(10))
            .maxBackoff(Duration.ofMillis(100))
            .filter(CompositeRequestServiceImpl::isConnectFailure)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());

    private static boolean isConnectFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
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
                    || (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException))
                    // Compared by name because Netty is optional.
                    || cause.getClass().getName().equals("io.netty.handler.timeout.ReadTimeoutException")
                    || cause.getClass().getName().equals("io.netty.handler.timeout.TimeoutException")) {
                return "the endpoint did not respond in time.";
            }
            if (cause instanceof DataBufferLimitException || cause instanceof ResponseTooLargeException) {
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

    /** Adapts the public dispatcher contract to the internal scheduler. */
    private Mono<DispatchResult> dispatch(PreparedSubRequest request) {
        return Mono.create(sink -> {
            DefaultCancellationSignal cancellation = new DefaultCancellationSignal();
            sink.onCancel(cancellation::cancel);

            try {
                dispatcher.dispatch(request, cancellation).whenComplete((result, failure) -> {
                    if (failure != null) {
                        sink.error(failure);
                    } else {
                        sink.success(result);
                    }
                });
            } catch (RuntimeException e) {
                sink.error(e);
            }
        });
    }

    private SubResponse toSubResponse(DispatchResult result, String referenceId) {
        HttpStatusCode status = HttpStatusCode.valueOf(result.status());
        var builder = SubResponse.builder()
                .referenceId(referenceId)
                .httpStatus(status.value());

        if (status.is2xxSuccessful()) {
            return successResponse(result, referenceId);
        }

        if (status.isError()) {
            String errorBody = result.body() == null ? "" : result.body();
            log.error("Error from subrequest: {} - {}", status, errorBody);
            return builder.body(errorBody).build();
        }

        // 1xx informational or 3xx redirect
        log.warn("Received non-standard response status: {}", status);
        return builder.build();
    }

    private SubResponse successResponse(DispatchResult result, String referenceId) {
        var builder = SubResponse.builder()
                .referenceId(referenceId)
                .httpStatus(result.status());
        String text = result.body();

        if (text == null || text.isBlank()) {
            return builder.build();
        }

        if (!isJson(result.contentType())) {
            return builder.body(text).build();
        }

        try {
            return builder.body(jsonResponseReader().readTree(text)).build();
        } catch (JsonProcessingException e) {
            log.warn("Sub-request {} returned invalid JSON", referenceId);
            return builder
                    .httpStatus(HttpStatus.BAD_GATEWAY.value())
                    .body("Response body is not valid JSON, although its Content-Type says it is. Fix the endpoint response.")
                    .build();
        }
    }

    /** Readers are immutable and thread-safe, so one instance serves every response. */
    private ObjectReader jsonResponseReader() {
        ObjectReader reader = jsonResponseReader;
        if (reader == null) {
            reader = JSON_RESPONSE_OPTIONS.apply(objectMapper.reader());
            jsonResponseReader = reader;
        }
        return reader;
    }

    private static boolean isJson(MediaType contentType) {
        return contentType != null
                && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || (contentType.getSubtype() != null && contentType.getSubtype().endsWith("+json")));
    }

    private static void recordError(BatchExecution batch, String referenceId, int status, String message) {
        batch.addResponse(referenceId, SubResponse.builder()
                .httpStatus(status)
                .referenceId(referenceId)
                .body(message)
                .build());
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

        CompositeBatchEvent batchEvent = new CompositeBatchEvent();
        batchEvent.begin();

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

        BatchExecution batch = new BatchExecution(requestId, requestMap, this, context,
                properties.getMaxConcurrentSubRequests(), compositeRequest.isStopOnFailure());
        activeBatches.increment();

        AtomicBoolean finished = new AtomicBoolean();
        Consumer<String> finish = outcome -> {
            if (finished.compareAndSet(false, true)) {
                batch.cancel();
                activeBatches.decrement();
                int failed = (int) batch.responses().values().stream()
                        .filter(subResponse -> subResponse.getHttpStatus() < 200 || subResponse.getHttpStatus() >= 300)
                        .count();
                batchEvent.finish(requestId, requestMap.size(), failed, compositeRequest.isStopOnFailure(), outcome);
            }
        };

        CompletableFuture<ResponseEntity<CompositeResponse>> result = batch.future()
                .orTimeout(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .handle((compositeResponse, failure) -> {
                    finish.accept(batchOutcome(compositeResponse, failure));
                    return toResponseEntity(compositeResponse, failure, batch.responses(), debugInfo);
                });

        // A cancelled stage skips the handler above, so cancellation cleans up directly.
        result.whenComplete((ignored, failure) -> {
            if (failure instanceof CancellationException) {
                finish.accept("CANCELLED");
                batch.fail(failure);
            }
        });

        try {
            batch.start();
        } catch (RuntimeException e) {
            batch.fail(e);
        }

        return result;
    }

    private static String batchOutcome(CompositeResponse response, Throwable failure) {
        if (failure == null) {
            return response.isHasErrors() ? "PARTIAL" : "SUCCESS";
        }

        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        if (cause instanceof TimeoutException) {
            return "TIMEOUT";
        }
        return cause instanceof CancellationException ? "CANCELLED" : "ERROR";
    }

    private ResponseEntity<CompositeResponse> toResponseEntity(CompositeResponse response, Throwable failure,
                                                               Map<String, SubResponse> responses, CompositeDebugInfo debugInfo) {
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
                .responses(new HashMap<>(responses))
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

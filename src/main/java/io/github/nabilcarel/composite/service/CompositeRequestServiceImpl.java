package io.github.nabilcarel.composite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class CompositeRequestServiceImpl implements CompositeRequestService{
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
            HttpServletRequest servletRequest
    ) {

        Optional<EndpointInfo> endpointInfo = endpointRegistry.getEndpointInformations(
                subRequest.getMethod().toUpperCase(), subRequest.getUrl()
        );

        if (endpointInfo.isEmpty()) {
            SubResponse errorResponse = SubResponse.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST.value())
                    .referenceId(subRequest.getReferenceId())
                    .body("Invalid endpoint received: " + subRequest.getUrl())
                    .build();

            responseStore
                    .get(requestId)
                    .addResponse(subRequest.getReferenceId(), errorResponse);
            log.error("Invalid endpoint received: {}", subRequest.getUrl());
            return Mono.empty();
        }

        String resolvedUrl = referenceResolver.resolveUrl(subRequest, requestId);
        referenceResolver.resolveHeaders(subRequest, requestId);
        referenceResolver.resolveBody(subRequest, requestId);
        String error = compositeRequestValidator.validateResolvedUrlFormat(resolvedUrl);

        if (subRequest.getBody() != null) {
            log.error("---------------------------------------------------------------");
            log.error("Subrequest body: {}", subRequest.getBody().toString());
            log.error("---------------------------------------------------------------");
        }

        if (error != null) {
            SubResponse errorResponse = SubResponse.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST.value())
                    .referenceId(subRequest.getReferenceId())
                    .body(error)
                    .build();

            responseStore
                    .get(requestId)
                    .addResponse(subRequest.getReferenceId(), errorResponse);
            log.error("Invalid URL format: {}", error);
            return Mono.empty();
        }

        WebClient.RequestBodySpec requestBodySpec = webClient.method(HttpMethod.valueOf(subRequest.getMethod()))
                .uri(resolvedUrl)
                .headers(httpHeaders -> {
                    authForwardingService.forwardAuthentication(servletRequest, httpHeaders);
                    subRequest.getHeaders().forEach(httpHeaders::add);

                    if(properties.getHeaderInjection().isEnabled()){
                        subRequest.getHeaders().put(properties.getHeaderInjection().getRequestHeader(), requestId);
                        subRequest.getHeaders().put(properties.getHeaderInjection().getSubRequestIdHeader(), subRequest.getReferenceId());
                        subRequest.getHeaders().put(properties.getHeaderInjection().getRequestHeader(), "true");
                    }
                });

        WebClient.RequestHeadersSpec<?> requestSpec = requestBodySpec;

        if (supportsRequestBody(subRequest.getMethod()) && subRequest.getBody() != null && !subRequest.getBody().isEmpty()) {
            requestSpec = requestBodySpec.bodyValue(subRequest.getBody());
        }

        return requestSpec
                .exchangeToMono(response -> {
                    return toBody(response, endpointInfo.get().getReturnClass()).map(body -> {
                        SubResponse.SubResponseBuilder subResponseBuilder = SubResponse.builder()
                                .referenceId(subRequest.getReferenceId())
                                .httpStatus(response.statusCode().value());
                        if (body != null) {
                            subResponseBuilder.body(body);
                        }
                        return subResponseBuilder.build();
                    });
                })
                .doOnSuccess(subResponse -> {
                    responseStore.get(requestId).addResponse(subRequest.getReferenceId(), subResponse);
                })
                .doOnError(throwable -> {
                    log.error("Error forwarding subrequest: {}", throwable.getMessage(), throwable);
                })
                .then();
    }

    private boolean supportsRequestBody(String method) {
        return !method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD");
    }

    private Mono<Object> toBody(ClientResponse response, Class<?> bodyClass) {
        HttpStatusCode status = response.statusCode();
        if (status.is2xxSuccessful()) {
            if (bodyClass != null && bodyClass != Void.class) {
                return response.bodyToMono(bodyClass);
            } else {
                return Mono.empty();
            }
        } else if (status.isError()) {
            return response.bodyToMono(String.class).map(errorBody -> {
                log.error("Error from subrequest: {} - {}", status, errorBody);
                return errorBody;
            });
        } else {
            return Mono.empty();
        }
    }

    public CompletableFuture<ResponseEntity<CompositeResponse>> execute(
            HttpServletRequest request, HttpServletResponse response) {
        String requestId = (String) request.getAttribute("requestId");
        boolean hasErrors = (Boolean) request.getAttribute("hasErrors");

        if (!hasErrors) {
            ResponseTracker responseTracker = responseStore.get(requestId);

            return responseTracker.getFuture()
                    .orTimeout(properties.getRequestTimeout().getSeconds(), TimeUnit.SECONDS)
                    .thenApply(compositeResponse -> {
                        responseStore.remove(requestId);
                        request.removeAttribute("composite");
                        response.reset();
                        return ResponseEntity.ok(compositeResponse);
                    })
                    .exceptionally(ex -> {
                        log.error("Execution failed: {}", ex.getMessage());
                        responseStore.remove(requestId);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
                    });
        } else {
            List<String> errors = (List<String>) request.getAttribute("errors");
            CompositeResponse compositeResponse = CompositeResponse.builder()
                    .hasErrors(true)
                    .errors(errors)
                    .build();
            CompletableFuture<CompositeResponse> future = new CompletableFuture<>();
            future.complete(compositeResponse);
            return future.thenApply(compResponse ->
                    ResponseEntity.badRequest().body(compResponse)
            );
        }
    }

    public Set<EndpointInfo> getAvailableEndpoints() {
        return endpointRegistry.getAvailableEndpoints();
    }
}

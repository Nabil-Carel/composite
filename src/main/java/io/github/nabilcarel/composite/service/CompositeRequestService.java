package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface CompositeRequestService {
    Mono<Void> forwardSubrequest(
            SubRequest subRequest,
            String requestId
    );

    CompletableFuture<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request, HttpServletResponse response);

    Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints();
}

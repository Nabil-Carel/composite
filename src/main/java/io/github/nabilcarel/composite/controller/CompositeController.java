package io.github.nabilcarel.composite.controller;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@RestController
@Lazy
@RequestMapping("${composite.base-path:/api/composite}")
@ConditionalOnProperty(name = "composite.controller.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CompositeController {
    @Lazy
    private final CompositeRequestService requestService;

    @PostMapping("/execute")
    public CompletableFuture<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request,
                                                                        HttpServletResponse response,
                                                                        @RequestBody CompositeRequest requestBody /*for swagger*/) {
       return requestService.execute(request, response);
    }

    @GetMapping("/endpoints")
    public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
        return requestService.getAvailableEndpoints();
    }
}

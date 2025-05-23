package com.example.composite.controller;

import com.example.composite.annotation.CompositeEndpoint;
import com.example.composite.config.EndpointRegistry;
import com.example.composite.model.ResponseTracker;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/composite")
@RequiredArgsConstructor
@Slf4j
public class CompositeController {
    private final ConcurrentMap<String, ResponseTracker> responseStore;
    private final EndpointRegistry endpointRegistry;

    @PostMapping("/execute")
    public CompletableFuture<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request,
                                                                        HttpServletResponse response) {
        String requestId = (String) request.getAttribute("requestId");
        boolean hasErrors = (Boolean) request.getAttribute("hasErrors");
        CompletableFuture<CompositeResponse> compositeResponseFuture = new CompletableFuture<>();

        if (!hasErrors) {
            ResponseTracker responseTracker = responseStore.get(requestId);

            if (responseTracker.getResponseCount().get() == 0) {
                CompositeResponse compositeResponse = CompositeResponse.builder()
                        .responses(responseTracker.getSubResponseMap()).build();
                request.removeAttribute("composite");
                compositeResponseFuture.complete(compositeResponse);
            } else {
                // The requests are still being processed
                responseTracker.getResponseCount()
                        .addValueChangeListener(((oldValue, newValue) -> {
                            if (newValue == 0) {
                                CompositeResponse compositeResponse = CompositeResponse.builder()
                                        .responses(responseTracker.getSubResponseMap()).build();
                                request.removeAttribute("composite");
                                compositeResponseFuture.complete(compositeResponse);
                            }
                        }));
            }

            return compositeResponseFuture
                    .orTimeout(30, TimeUnit.SECONDS)
                    .thenApply(compositeResponse -> {
                        responseTracker.cleanup();
                        response.reset();
                        responseStore.remove(requestId);
                        return ResponseEntity.ok(compositeResponse);
                    })
                    .exceptionally(ex -> {
                        log.error("Execution failed: {}", ex.getMessage());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
                    });
        }
        else {
            List<String> errors = (List<String>) request.getAttribute("errors");
            CompositeResponse compositeResponse = CompositeResponse.builder()
                    .hasErrors(true)
                    .errors(errors)
                    .build();
            compositeResponseFuture.complete(compositeResponse);
            return compositeResponseFuture.thenApply(compResponse ->
                ResponseEntity.badRequest().body(compResponse)
            );

        }
    }

    @GetMapping("/endpoints")
    public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
        return endpointRegistry.getAvailableEndpoints();
    }

    @GetMapping("/test")
    @CompositeEndpoint(value = SubResponse.class)
    public ResponseEntity<SubResponse> getTest() {
        return ResponseEntity.ok(SubResponse.builder().body("test").referenceId("test").build());
    }

    @PostMapping("/testPost")
    @CompositeEndpoint(value = SubResponse.class)
    public ResponseEntity<SubResponse> getTestPost() {
        SubResponse subResponse = SubResponse.builder().body("testPost").referenceId("testPost").build();
        return ResponseEntity.ok(subResponse);
    }

    @GetMapping("/helloWorld")
    @CompositeEndpoint(value = String.class)
    public ResponseEntity<String> getHello() {
        return ResponseEntity.ok("helloWorld");
    }

    @GetMapping("/num")
    @CompositeEndpoint(value = Integer.class)
    public ResponseEntity<Integer> getNum() {
        return ResponseEntity.ok(0);
    }
}

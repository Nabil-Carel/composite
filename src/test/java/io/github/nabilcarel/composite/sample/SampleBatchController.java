package io.github.nabilcarel.composite.sample;

import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A host-provided replacement for the built-in controller. */
@RestController
@ConditionalOnProperty(name = "composite.controller.enabled", havingValue = "false")
public class SampleBatchController {

    private final CompositeRequestService composite;

    public SampleBatchController(CompositeRequestService composite) {
        this.composite = composite;
    }

    @PostMapping(value = "/partner/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public CompletableFuture<ResponseEntity<CompositeResponse>> batch(@RequestBody CompositeRequest body,
                                                                      HttpServletRequest request,
                                                                      HttpServletResponse response) {
        return composite.execute(body, request, response);
    }
}

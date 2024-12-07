package com.example.composite.model.request;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class CompositeRequest {
    @NotEmpty(message = "At least one sub-request is required")
    private List<@Valid SubRequest> subRequests;
    @Builder.Default
    private boolean allOrNone = false;
    private Duration timeout;
}

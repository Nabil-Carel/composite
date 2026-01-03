package io.github.nabilcarel.composite.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeRequest {
    @NotEmpty(message = "At least one sub-request is required")
    private List<@Valid SubRequestDto> subRequests;

    @Builder.Default
    private boolean allOrNone = false;
}

package io.github.nabilcarel.composite.model.response;

import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositeResponse {
    private Map<String, SubResponse> responses;
    private boolean hasErrors;
    private List<String> errors;
}

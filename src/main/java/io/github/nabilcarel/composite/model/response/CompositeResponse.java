package io.github.nabilcarel.composite.model.response;

import java.util.List;
import java.util.Map;
import lombok.*;

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

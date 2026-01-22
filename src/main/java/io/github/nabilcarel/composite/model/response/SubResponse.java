package io.github.nabilcarel.composite.model.response;

import java.util.Map;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubResponse {
    private String referenceId;
    private int httpStatus;
    private Object body;
    private Map<String, String> headers;
}

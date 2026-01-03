package io.github.nabilcarel.composite.model.request;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.USE_DEFAULTS)
public class SubRequestDto {
    @NotBlank(message = "URL is required")
    // @Pattern(regexp = "^(http|https)://.*|^/.*",
    // message = "URL must be absolute or start with /")
    private String url;

    @NotBlank(message = "HTTP method is required")
    @Pattern(regexp = "^(GET|POST|PUT|DELETE|PATCH)$", message = "Invalid HTTP method")
    private String method;

    @NotBlank(message = "Reference ID is required")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{1,50}$", message = "Reference ID must be alphanumeric with dashes/underscores, max 50 chars")
    private String referenceId;

    private JsonNode body;

    @Builder.Default
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, String> headers = new HashMap<>();

    /*@Builder.Default
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Set<String> dependencies = new HashSet<>();*/

    @AssertTrue(message = "Body is required for POST/PUT/PATCH requests")
    private boolean isBodyValid() {
        return !Arrays.asList("POST", "PUT", "PATCH").contains(method.toUpperCase())
                || body != null;
    }
}

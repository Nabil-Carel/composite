package io.github.nabilcarel.composite.model.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Data Transfer Object representing a single HTTP sub-request within a
 * {@link CompositeRequest}.
 *
 * <p>Each {@code SubRequestDto} describes one HTTP call that the composite runtime should
 * execute on behalf of the client. Sub-requests are linked to one another through the
 * {@link #referenceId}: any sub-request may embed {@code ${referenceId.propertyPath}}
 * placeholders in its {@link #url}, {@link #headers}, or {@link #body} to splice the
 * response of a previously resolved sub-request into its own parameters.
 *
 * <h2>Placeholder syntax</h2>
 * <ul>
 *   <li>{@code ${user.id}} &mdash; the {@code id} field of the response body identified by
 *       reference {@code user}</li>
 *   <li>{@code ${items[0].name}} &mdash; the {@code name} field of the first element of an
 *       array response identified by reference {@code items}</li>
 *   <li>{@code ${config['db.host']}} &mdash; bracket notation for keys that contain dots</li>
 * </ul>
 *
 * <h2>Validation constraints</h2>
 * <ul>
 *   <li>{@link #url} must be non-blank.</li>
 *   <li>{@link #method} must be one of {@code GET}, {@code POST}, {@code PUT},
 *       {@code DELETE}, or {@code PATCH}.</li>
 *   <li>{@link #referenceId} must be alphanumeric (plus {@code -} / {@code _}), max 50
 *       characters, and unique within the enclosing {@link CompositeRequest}.</li>
 *   <li>A request body is required for {@code POST}, {@code PUT}, and {@code PATCH}
 *       methods.</li>
 * </ul>
 *
 * @see CompositeRequest
 * @see io.github.nabilcarel.composite.model.request.SubRequest
 * @since 0.0.1
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.USE_DEFAULTS)
public class SubRequestDto {

    /**
     * The target URL of this sub-request.
     *
     * <p>May be a relative path (e.g. {@code /api/users/42}) or an absolute URI. Placeholder
     * expressions of the form {@code ${referenceId.property}} are resolved at runtime against
     * completed sub-responses before the request is dispatched.
     */
    @NotBlank(message = "URL is required")
    // @Pattern(regexp = "^(http|https)://.*|^/.*",
    // message = "URL must be absolute or start with /")
    private String url;

    /**
     * The HTTP method for this sub-request.
     *
     * <p>Accepted values are {@code GET}, {@code POST}, {@code PUT}, {@code DELETE},
     * and {@code PATCH}.
     */
    @NotBlank(message = "HTTP method is required")
    @Pattern(regexp = "^(GET|POST|PUT|DELETE|PATCH)$", message = "Invalid HTTP method")
    private String method;

    /**
     * A unique identifier for this sub-request within the enclosing {@link CompositeRequest}.
     *
     * <p>Other sub-requests reference the response of this sub-request using this ID inside
     * {@code ${referenceId.propertyPath}} placeholder expressions. Must match the pattern
     * {@code [a-zA-Z0-9_-]{1,50}}.
     */
    @NotBlank(message = "Reference ID is required")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{1,50}$", message = "Reference ID must be alphanumeric with dashes/underscores, max 50 chars")
    private String referenceId;

    /**
     * The optional JSON request body.
     *
     * <p>Required for {@code POST}, {@code PUT}, and {@code PATCH} methods. String values
     * within the body may contain {@code ${referenceId.property}} placeholder expressions
     * that are resolved against prior sub-responses at runtime.
     */
    private JsonNode body;

    /**
     * Additional HTTP headers to include with this sub-request.
     *
     * <p>Header values may contain {@code ${referenceId.property}} placeholder expressions.
     * Defaults to an empty map when not specified or when {@code null} is supplied in the
     * JSON payload.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, String> headers = new HashMap<>();

    /**
     * Bean Validation constraint that ensures a request body is present for methods that
     * require one ({@code POST}, {@code PUT}, {@code PATCH}).
     *
     * @return {@code true} if the body constraint is satisfied; {@code false} otherwise
     */
    @AssertTrue(message = "Body is required for POST/PUT/PATCH requests")
    private boolean isBodyValid() {
        boolean hasBody = body != null && !body.isNull() && !body.isMissingNode();
        return !Arrays.asList("POST", "PUT", "PATCH").contains(method.toUpperCase())
                || hasBody;
    }
}

package io.github.nabilcarel.composite.model.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nabilcarel.composite.model.ArrayElementReference;
import io.github.nabilcarel.composite.model.NodeReference;
import io.github.nabilcarel.composite.model.ObjectFieldReference;
import io.github.nabilcarel.composite.util.ReferenceTemplate;
import java.util.*;
import lombok.*;
import lombok.experimental.Delegate;

/**
 * Runtime representation of a single sub-request, enriching a {@link SubRequestDto} with
 * resolved values and dependency metadata computed during the execution phase.
 *
 * <p>{@code SubRequest} wraps a {@link SubRequestDto} via Lombok's {@code @Delegate}, exposing
 * all DTO accessors directly while adding mutable state that is populated as the composite
 * pipeline progresses:
 * <ul>
 *   <li>{@link #resolvedUrl} &mdash; the URL after {@code ${...}} placeholders have been
 *       substituted with values from prior sub-responses.</li>
 *   <li>{@link #resolvedHeaders} &mdash; headers after placeholder substitution.</li>
 *   <li>{@link #dependencies} &mdash; the set of {@code referenceId}s that this sub-request
 *       depends on, lazily extracted from URL, headers, and body placeholders on first
 *       access.</li>
 *   <li>{@link #nodeReferences} &mdash; structural pointers into the body JSON tree for the
 *       field positions that contain placeholder expressions, enabling in-place substitution
 *       without re-parsing the body.</li>
 * </ul>
 *
 * <p>Dependency extraction supports both dot notation ({@code ${user.id}}) and bracket
 * notation ({@code ${users[0].name}}) across URL path, query parameters, header values, and
 * nested body nodes.
 *
 * @see SubRequestDto
 * @see io.github.nabilcarel.composite.service.ReferenceResolverService
 * @since 0.0.1
 */
@RequiredArgsConstructor
@Getter
@Setter
public class SubRequest {

    /** The immutable DTO from which this runtime wrapper was created. */
    @Delegate(types = SubRequestDto.class)
    private final SubRequestDto subRequestDto;

    /** The URL with all {@code ${...}} placeholders resolved; {@code null} before resolution. */
    private String resolvedUrl;

    /**
     * The header map with all {@code ${...}} placeholder values resolved; {@code null}
     * before resolution.
     */
    private Map<String, String> resolvedHeaders;

    /**
     * The set of {@code referenceId}s that this sub-request depends on.
     *
     * <p>Populated lazily on the first call to {@link #getDependencies()} by scanning the
     * URL, headers, and body for {@code ${referenceId...}} placeholder expressions.
     */
    private Set<String> dependencies = new HashSet<>();

    /** Tracks extraction separately because a sub-request can legitimately have no dependencies. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean dependenciesExtracted;

    /**
     * Structural pointers into the body {@link com.fasterxml.jackson.databind.JsonNode} tree
     * that contain placeholder expressions, used by the reference resolver to perform
     * in-place substitution.
     */
    private List<NodeReference> nodeReferences = new ArrayList<>();

    /**
     * Returns the set of {@code referenceId}s that this sub-request depends on.
     *
     * <p>Dependencies are derived automatically by scanning for {@code ${referenceId...}}
     * placeholder expressions in the URL, headers, and body of the enclosing
     * {@link SubRequestDto}. The set is computed once and cached.
     *
     * @return an unmodifiable view of the dependency reference IDs; never {@code null}
     */
    public Set<String> getDependencies() {
        if (!dependenciesExtracted) {
            extractDependenciesFromUrl();
            extractDependenciesFromHeaders();
            extractDependenciesFromBody(subRequestDto.getBody());
            dependenciesExtracted = true;
        }

        return dependencies;
    }

    /**
     * Replaces the dependency set and skips placeholder extraction.
     *
     * @param dependencies the reference IDs this sub-request depends on
     */
    public void setDependencies(Set<String> dependencies) {
        this.dependencies = dependencies;
        this.dependenciesExtracted = true;
    }

    private void extractDependenciesFromUrl() {
        addDependencies(subRequestDto.getUrl());
    }

    private void extractDependenciesFromHeaders() {
        for (String headerValue : subRequestDto.getHeaders().values()) {
            addDependencies(headerValue);
        }
    }

    private void extractDependenciesFromBody(JsonNode body) {
        if (body == null) {
            return;
        }
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(body);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isTextual()) {
                addDependencies(node.textValue());
            } else if (node.isContainerNode()) {
                node.forEach(pending::push);
            }
        }
    }

    /**
     * Adds the sub-requests referenced by one text, parsed exactly as the resolver parses it.
     * Text the resolver would reject adds nothing, because validation rejects the request.
     */
    private void addDependencies(String text) {
        if (text == null || !text.contains("${")) {
            return;
        }
        try {
            for (ReferenceTemplate.Token token : ReferenceTemplate.parse(text).tokens()) {
                if (token.reference()) {
                    dependencies.add(rootReferenceId(token.text()));
                }
            }
        } catch (IllegalArgumentException e) {
            // Reported by validation.
        }
    }

    /** The reference ID ends at the first '.' or '[': {@code user.id} and {@code users[0]} name it. */
    private static String rootReferenceId(String expression) {
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '.' || c == '[') {
                return expression.substring(0, i);
            }
        }
        return expression;
    }

    /**
     * Populates {@link #nodeReferences} by scanning the body JSON tree for placeholder
     * expressions. Called explicitly by the reference resolver before body resolution —
     * kept separate from {@link #getDependencies()} so that dependency extraction
     * (an orchestration concern) does not carry resolver artefacts as a side-effect.
     */
    public void initNodeReferences() {
        nodeReferences.clear();
        scanBodyForNodeReferences(subRequestDto.getBody());
    }

    private void scanBodyForNodeReferences(JsonNode node) {
        if (node == null) return;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode child = entry.getValue();

                if (child.isTextual()) {
                    if (child.textValue().contains("${")) {
                        nodeReferences.add(new ObjectFieldReference(obj, entry.getKey()));
                    }
                } else if (child.isObject() || child.isArray()) {
                    scanBodyForNodeReferences(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);

                if (child.isTextual()) {
                    if (child.textValue().contains("${")) {
                        nodeReferences.add(new ArrayElementReference(arr, i));
                    }
                }

                scanBodyForNodeReferences(child);
            }
        }
    }

}
    
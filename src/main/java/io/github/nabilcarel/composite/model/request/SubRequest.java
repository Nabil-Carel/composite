package io.github.nabilcarel.composite.model.request;

import static io.github.nabilcarel.composite.util.Patterns.DEPENDENCY_SPLIT_PATTERN;
import static io.github.nabilcarel.composite.util.Patterns.PLACEHOLDER_PATTERN;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nabilcarel.composite.model.ArrayElementReference;
import io.github.nabilcarel.composite.model.NodeReference;
import io.github.nabilcarel.composite.model.ObjectFieldReference;
import java.util.*;
import java.util.regex.Matcher;
import lombok.*;
import lombok.experimental.Delegate;

@RequiredArgsConstructor
@Getter
@Setter
public class SubRequest {
    @Delegate(types = SubRequestDto.class)
    private final SubRequestDto subRequestDto;
    private String resolvedUrl;
    private Map<String, String> resolvedHeaders;
    private Set<String> dependencies = new HashSet<>();
    private List<NodeReference> nodeReferences = new ArrayList<>();

    public Set<String> getDependencies() {
        if(dependencies.isEmpty()) {
            extractDependenciesFromUrl();
            extractDependenciesFromHeaders();
            extractDependenciesFromBody(subRequestDto.getBody());
        }

        return dependencies;
    }

    private void extractDependenciesFromUrl() {
        Matcher matcher = PLACEHOLDER_PATTERN
                .matcher(subRequestDto.getUrl());

        while (matcher.find()) {
            String reference = matcher.group(1);
            // Extract root reference ID before any brackets or dots
            // Handle both dot notation (user.id) and bracket notation (users[0].id)
            String refId = extractRootReferenceId(reference);
            dependencies.add(refId);
        }
    }

    private String extractRootReferenceId(String reference) {
        // Find the first occurrence of '.' or '[' to determine where the reference ID ends
        int dotIndex = reference.indexOf('.');
        int bracketIndex = reference.indexOf('[');
        
        if (bracketIndex != -1 && (dotIndex == -1 || bracketIndex < dotIndex)) {
            // Bracket notation comes first: users[0] -> users
            return reference.substring(0, bracketIndex);
        } else if (dotIndex != -1) {
            // Dot notation: user.id -> user
            return reference.substring(0, dotIndex);
        } else {
            // No separator, entire reference is the ID
            return reference;
        }
    }

    private void extractDependenciesFromHeaders() {
        for (String headerValue : subRequestDto.getHeaders().values()) {
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(headerValue);

            while (matcher.find()) {
                String reference = matcher.group(1);
                String refId = extractRootReferenceId(reference);
                dependencies.add(refId);
            }
        }
    }

    private void extractDependenciesFromBody(JsonNode node) {
        if (node == null) return;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode child = entry.getValue();

                if (child.isTextual()) {
                    Matcher matcher = PLACEHOLDER_PATTERN.matcher(child.asText());

                    while (matcher.find()) {
                        String reference = matcher.group(1);
                        String refId = extractRootReferenceId(reference);
                        dependencies.add(refId);

                        // store reference to replace later
                        nodeReferences.add(new ObjectFieldReference(obj, entry.getKey()));
                    }
                }
                else if (child.isObject() || child.isArray()) {
                    extractDependenciesFromBody(child); // recurse deeper
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);

                if (child.isTextual()) {
                    Matcher matcher = PLACEHOLDER_PATTERN.matcher(child.asText());

                    while (matcher.find()) {
                        String reference = matcher.group(1);
                        String refId = extractRootReferenceId(reference);
                        dependencies.add(refId);

                        // store reference to replace later
                        nodeReferences.add(new ArrayElementReference(arr, i));
                    }
                }

                extractDependenciesFromBody(child); // recurse deeper
            }
        }
    }

}
    
package com.example.composite.model.request;

import com.example.composite.model.NodeReference;
import com.example.composite.model.ObjectFieldReference;
import com.example.composite.model.ArrayElementReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.*;
import lombok.experimental.Delegate;


import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    public Set<String> getDependencies() {
        if(dependencies.isEmpty()) {
            extractDependenciesFromUrl();
            extractDependenciesFromHeaders();
            extractDependenciesFromBody(subRequestDto.getBody());
        }

        return dependencies;
    }

    private void extractDependenciesFromUrl() {
        Matcher matcher = REFERENCE_PATTERN
                .matcher(subRequestDto.getUrl());

        while (matcher.find()) {
            String reference = matcher.group(1);
            String refId = reference.split("\\.")[0];
            dependencies.add(refId);
        }
    }

    private void extractDependenciesFromHeaders() {
        for (String headerValue : subRequestDto.getHeaders().values()) {
            Matcher matcher = REFERENCE_PATTERN.matcher(headerValue);

            while (matcher.find()) {
                String reference = matcher.group(1);
                String refId = reference.split("\\.")[0];
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
                    Matcher matcher = REFERENCE_PATTERN.matcher(child.asText());

                    while (matcher.find()) {
                        String reference = matcher.group(1);
                        String refId = reference.split("\\.")[0];
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
                    Matcher matcher = REFERENCE_PATTERN.matcher(child.asText());

                    while (matcher.find()) {
                        String reference = matcher.group(1);
                        String refId = reference.split("\\.")[0];
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
    
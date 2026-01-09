package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import io.github.nabilcarel.composite.exception.UnresolvedReferenceException;
import io.github.nabilcarel.composite.model.NodeReference;
import io.github.nabilcarel.composite.model.PlaceholderResolution;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.nabilcarel.composite.model.response.SubResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReferenceResolverServiceImpl implements ReferenceResolverService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    //TODO: Make configurable
    private static final int MAX_RESOLUTION_ITERATIONS = 10;

    private final ConcurrentMap<String, ResponseTracker> responseStore;
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper mapper;

    public String resolveUrl(SubRequest subRequest, String batchId) {
        String url = resolveNestedPlaceholders(subRequest.getUrl(), batchId);
        subRequest.setResolvedUrl(url);
        return url;
    }

    public void resolveHeaders(SubRequest subRequest, String batchId) {
        if(subRequest.getHeaders() == null || subRequest.getHeaders().isEmpty()) {
            return;
        }

        subRequest.setResolvedHeaders(subRequest.getHeaders().entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> resolveNestedPlaceholders(entry.getValue(), batchId)
                )
        ));
    }

    public void resolveBody(SubRequest subRequest, String batchId) {
        if (subRequest.getBody() == null || subRequest.getNodeReferences().isEmpty()) {
            return;
        }

        for (NodeReference nodeRef : subRequest.getNodeReferences()) {
            String text = nodeRef.getValue().asText();
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);

            if(!matcher.find()) {
                continue; // Nothing to replace
            }

            matcher.reset();

            // Entire value matches the placeholder pattern
            if(matcher.matches()) {
                String resolvedText = resolveNestedPlaceholders(text, batchId);

                // Check if fully resolved (no more ${} patterns)
                if (!PLACEHOLDER_PATTERN.matcher(resolvedText).find()) {
                    // Fully resolved - preserve original type
                    Object resolvedObject = parseResolvedValue(resolvedText, resolvedText, batchId);
                    JsonNode node = mapper.valueToTree(resolvedObject);
                    nodeRef.overrideValue(node);
                } else {
                    // Still has unresolved placeholders
                    throw new ReferenceResolutionException("Could not fully resolve placeholder", text);
                }
            }
            else {
                // Multiple placeholders or mixed content: "Hello ${user.name}, order #${order.id}!"
                String resolvedText = resolveNestedPlaceholders(text, batchId);

                // Check if all placeholders were resolved
                if (PLACEHOLDER_PATTERN.matcher(resolvedText).find()) {
                    throw new IllegalArgumentException("Could not fully resolve all placeholders in: " + text);
                }

                nodeRef.overrideValue(new TextNode(resolvedText));
            }
        }
    }

    /**
     * Resolves nested placeholders iteratively until no more ${} patterns exist
     */
    private String resolveNestedPlaceholders(String input, String batchId) {
        String result = input;
        String previous;
        int iteration = 0;

        do {
            previous = result;
            result = resolveSinglePass(result, batchId);
            iteration++;
        } while (!result.equals(previous) && iteration < MAX_RESOLUTION_ITERATIONS);

        if (iteration >= MAX_RESOLUTION_ITERATIONS) {
            throw new IllegalArgumentException("Maximum resolution iterations exceeded. Possible circular reference in: " + input);
        }

        return result;
    }

    /**
     * Single pass resolution of all ${} placeholders in the input
     */
    private String resolveSinglePass(String input, String batchId) {
        // Find innermost placeholders first (those without nested ${})
        Pattern innermostPattern = Pattern.compile("\\$\\{([^${}]+)\\}");
        Matcher matcher = innermostPattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            try {
                PlaceholderResolution placeholder = resolvePlaceholder(matcher, batchId);
                String replacement = getResolvedValue(placeholder.getRoot(), placeholder.getObjectId(),
                        placeholder.getPropertyPath());
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            } catch (UnresolvedReferenceException e) {
                // Leave unresolved placeholders as-is for next iteration
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * For single placeholder that occupies entire value, try to preserve original type
     */
    private Object parseResolvedValue(String resolvedText, String originalExpression, String batchId) {
        try {
            PlaceholderResolution placeholder = parseExpression(originalExpression, batchId);
            return getResolvedObject(placeholder.getRoot(), placeholder.getObjectId(),
                    placeholder.getPropertyPath());
        } catch (Exception e) {
            // Fallback to string value
            return resolvedText;
        }
    }

    /**
     * Resolves the value of a property path on an object identified by objectId.
     */
    private String getResolvedValue(Object root, String objectId, String propertyPath) {
        Object value = getResolvedObject(root, objectId, propertyPath);
        return value != null ? value.toString() : "";
    }

    private Object getResolvedObject(Object root, String objectId, String propertyPath) {
        if (root == null) {
            throw new IllegalArgumentException("No object found for id: " + objectId);
        }

        if (root instanceof Map) {
            return handleMapAccess((Map<?, ?>) root, propertyPath);
        } else if (root instanceof Collection) {
            return handleCollectionAccess((Collection<?>) root, propertyPath);
        } else if (root.getClass().isArray()) {
            return handleArrayAccess(root, propertyPath);
        } else {

            BeanWrapperImpl wrapper = new BeanWrapperImpl(root);
            Object value;

            try {
                // BeanWrapper handles both dot notation (user.name) and bracket notation (user[name], users[0])
                value = wrapper.getPropertyValue(propertyPath);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to resolve property path '" + propertyPath +
                        "' on object '" + objectId + "'", e);
            }

            return value;
        }
    }

    private PlaceholderResolution resolvePlaceholder(@NonNull Matcher matcher, @NonNull String batchId) {
        String expression = matcher.group(1);
        return parseExpression(expression, batchId);
    }

    /**
     * Parses expressions to handle both dot and bracket notation:
     * - user.name -> objectId="user", propertyPath="name"
     * - user['name'] -> objectId="user", propertyPath="['name']"
     * - users[0].name -> objectId="users", propertyPath="[0].name"
     * - config['database.host'] -> objectId="config", propertyPath="['database.host']"
     */
    private PlaceholderResolution parseExpression(String expression, String batchId) {
        // If expression still contains ${}, it's not fully resolved yet
        if (PLACEHOLDER_PATTERN.matcher(expression).find()) {
            throw new UnresolvedReferenceException(
                    "Expression contains unresolved placeholders: " + expression
            );
        }

        // Find the root object name (everything before first . or [)
        String objectId;
        String propertyPath;

        int dotIndex = expression.indexOf('.');
        int bracketIndex = expression.indexOf('[');

        // Determine where the object name ends
        int separatorIndex = -1;
        if (dotIndex != -1 && bracketIndex != -1) {
            separatorIndex = Math.min(dotIndex, bracketIndex);
        } else if (dotIndex != -1) {
            separatorIndex = dotIndex;
        } else if (bracketIndex != -1) {
            separatorIndex = bracketIndex;
        }

        if (separatorIndex == -1) {
            // No property path, just the object itself
            objectId = expression;
            propertyPath = null;
        } else {
            objectId = expression.substring(0, separatorIndex);
            propertyPath = expression.substring(separatorIndex);

            // If starts with dot, remove it (BeanWrapper expects "name" not ".name")
            if (propertyPath.startsWith(".")) {
                propertyPath = propertyPath.substring(1);
            }
        }

        ResponseTracker tracker = responseStore.get(batchId);

        if(tracker == null) {
            throw new ReferenceResolutionException(
                    "No responses found for batch ID: " + batchId,
                    objectId
            );
        }

        Map<String, SubResponse> subResponseMap = tracker.getSubResponseMap();

        if (subResponseMap == null) {
            throw new IllegalArgumentException("Response map not initialized for batch ID: " + batchId);
        }

        SubResponse subResponse = subResponseMap.get(objectId);

        if (subResponse == null) {
            throw new ReferenceResolutionException(
                    "No response found for reference ID: " + objectId,
                    objectId,
                    String.join(", ", subResponseMap.keySet())
            );
        }

        Object root = subResponse.getBody();

        if (root == null) {
            throw new ReferenceResolutionException(
                    "No response body found for reference ID: " + objectId,
                    objectId,
                    String.join(", ", responseStore.get(batchId).getSubResponseMap().keySet())
            );
        }

        if (propertyPath != null && propertyPath.startsWith("[") && propertyPath.endsWith("]")) {
            // Just remove the brackets: [database.host] -> database.host
            propertyPath = propertyPath.substring(1, propertyPath.length() - 1);
        }

        return new PlaceholderResolution(objectId, propertyPath, root);
    }

    private Object handleMapAccess(Map<?, ?> map, String propertyPath) {
        // Simple case: direct key access
        if (!propertyPath.contains(".")) {
            return map.get(propertyPath);
        }

        // Nested path: user.address.city
        String[] parts = propertyPath.split("\\.", 2);
        Object value = map.get(parts[0]);

        if (value == null) {
            return null;
        }

        // Recursively resolve the rest of the path
        return getResolvedObject(value, parts[0], parts[1]);
    }

    private Object handleCollectionAccess(Collection<?> collection, String propertyPath) {
        // Collections need index access: "0", "1", etc.
        try {
            int index = Integer.parseInt(propertyPath);

            if (collection instanceof List) {
                return ((List<?>) collection).get(index);
            } else {
                // For Set/other collections, iterate to index
                int i = 0;
                for (Object item : collection) {
                    if (i == index) return item;
                    i++;
                }
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Collections require numeric index, got: " + propertyPath);
        }
    }

    private Object handleArrayAccess(Object array, String propertyPath) {
        try {
            int index = Integer.parseInt(propertyPath);
            return java.lang.reflect.Array.get(array, index);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Arrays require numeric index, got: " + propertyPath);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Array index out of bounds: " + propertyPath);
        }
    }
}
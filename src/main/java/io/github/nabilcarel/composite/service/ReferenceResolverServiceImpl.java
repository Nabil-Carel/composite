package io.github.nabilcarel.composite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import io.github.nabilcarel.composite.model.NodeReference;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.util.ReferencePath;
import io.github.nabilcarel.composite.util.ReferenceTemplate;
import io.github.nabilcarel.composite.util.SubRequestTarget;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Default {@link ReferenceResolverService} implementation that resolves
 * {@code ${referenceId.propertyPath}} placeholder expressions against the JSON responses
 * stored in the shared {@link ResponseTracker} map.
 *
 * <p>Paths are navigated by {@link ReferencePath}, so they use the JSON field names a client
 * sees. A placeholder that is an entire body value keeps its JSON type. A placeholder inside
 * a URL, a header or larger text must refer to a string, number or boolean.
 *
 * <p>Client templates are compiled once and substituted once. Inserted values are never
 * scanned again, so stored response data cannot act as a placeholder. URL values are
 * encoded for their position by {@link SubRequestTarget}.
 *
 * <p>Header values are sanitised after resolution to strip control characters, preventing
 * HTTP header injection.
 *
 * @see ReferenceResolverService
 * @since 0.0.1
 */
@Service
@RequiredArgsConstructor
public class ReferenceResolverServiceImpl implements ReferenceResolverService {

    private final ConcurrentMap<String, ResponseTracker> responseStore;
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper mapper;
    private final CompositeProperties properties;

    /** Tracks how much referenced data has been inserted into one part of a sub-request. */
    private static final class ResolutionBudget {
        private long remaining;

        private ResolutionBudget(long limit) {
            this.remaining = limit;
        }

        private void charge(long size, String expression) {
            remaining -= size;
            if (remaining < 0) {
                throw new ReferenceResolutionException(
                        "Referenced data exceeds composite.max-resolved-size", expression);
            }
        }

        private void charge(JsonNode value, String expression) {
            Deque<JsonNode> pending = new ArrayDeque<>();
            pending.push(value);

            while (!pending.isEmpty()) {
                JsonNode node = pending.pop();
                if (node.isObject()) {
                    node.fields().forEachRemaining(field -> {
                        charge(field.getKey().length(), expression);
                        pending.push(field.getValue());
                    });
                } else if (node.isArray()) {
                    node.forEach(pending::push);
                } else {
                    charge(node.isTextual() ? node.textValue().length() : node.asText().length(), expression);
                }
            }
        }
    }

    private ResolutionBudget newBudget() {
        return new ResolutionBudget(properties.getMaxResolvedSize().toBytes());
    }

    public String resolveUrl(SubRequest subRequest, String batchId) {
        ResolutionBudget budget = newBudget();
        String url = SubRequestTarget.parse(subRequest.getUrl())
                .render(expression -> resolveToText(expression, batchId, budget));
        subRequest.setResolvedUrl(url);
        return url;
    }

    public void resolveHeaders(SubRequest subRequest, String batchId) {
        if(subRequest.getHeaders() == null || subRequest.getHeaders().isEmpty()) {
            return;
        }

        ResolutionBudget budget = newBudget();
        subRequest.setResolvedHeaders(subRequest.getHeaders().entrySet().stream().collect(
                java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> sanitizeHeaderValue(ReferenceTemplate.parse(entry.getValue())
                                .render(expression -> resolveToText(expression, batchId, budget)))
                )
        ));
    }

    /**
     * Sanitizes header values to prevent header injection attacks.
     * Strips control characters, including carriage return and line feed.
     */
    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\p{Cntrl}", "");
    }

    public void resolveBody(SubRequest subRequest, String batchId) {
        if (subRequest.getBody() == null) {
            return;
        }

        subRequest.initNodeReferences();

        if (subRequest.getNodeReferences().isEmpty()) {
            return;
        }

        ResolutionBudget budget = newBudget();

        for (NodeReference nodeRef : subRequest.getNodeReferences()) {
            String text = nodeRef.getValue().asText();
            ReferenceTemplate template = ReferenceTemplate.parse(text);

            if (!template.hasReferences()) {
                continue; // Nothing to replace
            }

            if (template.isSingleReference()) {
                // Entire value is one reference, so it keeps its original type
                String expression = template.tokens().get(0).text();
                JsonNode value = resolveNonNull(expression, batchId);
                budget.charge(value, expression);
                nodeRef.overrideValue(value.deepCopy());
            } else {
                // Mixed content: "Hello ${user.name}, order #${order.id}!"
                nodeRef.overrideValue(new TextNode(
                        template.render(expression -> resolveToText(expression, batchId, budget))));
            }
        }
    }

    private JsonNode resolveNonNull(String expression, String batchId) {
        JsonNode value = resolve(expression, batchId);
        if (value.isNull()) {
            throw new ReferenceResolutionException("Resolved value is null for: " + expression, expression);
        }
        return value;
    }

    private String resolveToText(String expression, String batchId, ResolutionBudget budget) {
        JsonNode value = resolveNonNull(expression, batchId);

        if (!value.isValueNode()) {
            throw new ReferenceResolutionException(
                    "Reference must be a string, number or boolean here: " + expression, expression);
        }

        // Charge plain decimals by their rendered length, which an exponent can make very large.
        if (value.isBigDecimal()) {
            BigDecimal decimal = value.decimalValue();
            budget.charge(Math.abs((long) decimal.scale()) + decimal.precision() + 2L, expression);
            return decimal.toPlainString();
        }

        String text = value.asText();
        budget.charge(text.length(), expression);
        return text;
    }

    private JsonNode resolve(String expression, String batchId) {
        ReferencePath path = ReferencePath.parse(expression);
        String referenceId = path.referenceId();
        ResponseTracker tracker = responseStore.get(batchId);

        if (tracker == null) {
            throw new ReferenceResolutionException("No responses found for batch ID: " + batchId, referenceId);
        }

        Map<String, SubResponse> responses = tracker.getSubResponseMap();
        if (responses == null) {
            throw new IllegalArgumentException("Response map not initialized for batch ID: " + batchId);
        }

        SubResponse subResponse = responses.get(referenceId);
        if (subResponse == null) {
            throw new ReferenceResolutionException(
                    "No response found for reference ID: " + referenceId,
                    referenceId,
                    String.join(", ", responses.keySet()));
        }

        Object body = subResponse.getBody();
        if (body == null) {
            throw new ReferenceResolutionException(
                    "No response body found for reference ID: " + referenceId,
                    referenceId,
                    String.join(", ", responses.keySet()));
        }

        JsonNode root = body instanceof JsonNode json ? json : mapper.valueToTree(body);
        return path.navigate(root);
    }
}

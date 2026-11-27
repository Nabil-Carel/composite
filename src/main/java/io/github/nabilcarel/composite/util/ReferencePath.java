package io.github.nabilcarel.composite.util;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed {@code ${referenceId.path}} expression that navigates a JSON response.
 *
 * <p>Supported steps are dot keys ({@code user.name}), array indexes ({@code items[0]}), and
 * bracket keys ({@code config['db.host']}, {@code config["db.host"]} or {@code user[name]}).
 * Paths operate on the JSON a client would receive, so they use JSON field names and cannot
 * reach fields that the endpoint did not serialize.
 *
 * @since 0.0.1
 */
public final class ReferencePath {

    private final String referenceId;
    private final List<Step> steps;

    private ReferencePath(String referenceId, List<Step> steps) {
        this.referenceId = referenceId;
        this.steps = List.copyOf(steps);
    }

    /**
     * Parses an expression.
     *
     * @param expression the text inside {@code ${...}}
     * @return the parsed path
     * @throws IllegalArgumentException if the expression is malformed
     */
    public static ReferencePath parse(String expression) {
        int position = nextSeparator(expression, 0);
        if (position == 0) {
            throw malformed(expression);
        }

        String referenceId = expression.substring(0, position);
        List<Step> steps = new ArrayList<>();

        while (position < expression.length()) {
            char separator = expression.charAt(position);

            if (separator == '.') {
                int end = nextSeparator(expression, position + 1);
                if (end == position + 1) {
                    throw malformed(expression);
                }
                steps.add(Step.key(expression.substring(position + 1, end)));
                position = end;
            } else if (separator == '[') {
                position = parseBracket(expression, position, steps);
            } else {
                throw malformed(expression);
            }
        }

        return new ReferencePath(referenceId, steps);
    }

    private static int parseBracket(String expression, int start, List<Step> steps) {
        int contentStart = start + 1;
        if (contentStart >= expression.length()) {
            throw malformed(expression);
        }

        char first = expression.charAt(contentStart);
        if (first == '\'' || first == '"') {
            int end = expression.indexOf(first + "]", contentStart + 1);
            if (end < 0) {
                throw malformed(expression);
            }
            steps.add(Step.key(expression.substring(contentStart + 1, end)));
            return end + 2;
        }

        int end = expression.indexOf(']', contentStart);
        if (end <= contentStart) {
            throw malformed(expression);
        }

        String content = expression.substring(contentStart, end);
        if (content.chars().allMatch(Character::isDigit)) {
            try {
                steps.add(Step.index(content));
            } catch (NumberFormatException e) {
                throw malformed(expression);
            }
        } else {
            steps.add(Step.key(content));
        }
        return end + 1;
    }

    private static int nextSeparator(String expression, int from) {
        int position = from;
        while (position < expression.length()
                && expression.charAt(position) != '.'
                && expression.charAt(position) != '[') {
            position++;
        }
        return position;
    }

    private static IllegalArgumentException malformed(String expression) {
        return new IllegalArgumentException("Malformed reference: " + expression
                + ". Use a form like ${a.id}, ${a.items[0].name} or ${a['key.with.dots']}.");
    }

    /**
     * Returns the sub-request this path reads from.
     *
     * @return the reference ID
     */
    public String referenceId() {
        return referenceId;
    }

    /**
     * Navigates the given response.
     *
     * @param root the response body
     * @return the referenced value
     * @throws ReferenceResolutionException if a step does not exist
     */
    public JsonNode navigate(JsonNode root) {
        JsonNode current = root;

        for (Step step : steps) {
            JsonNode next = null;

            if (current.isObject()) {
                next = current.get(step.key());
            } else if (current.isArray() && step.index() >= 0) {
                next = current.get(step.index());
            }

            if (next == null) {
                throw new ReferenceResolutionException(
                        "Property '" + step.key() + "' not found in response '" + referenceId
                                + "'. References use the JSON field names in that response.",
                        referenceId);
            }
            current = next;
        }

        return current;
    }

    /** One navigation step. {@code index} is {@code -1} for steps that are only a key. */
    private record Step(String key, int index) {

        static Step key(String key) {
            return new Step(key, -1);
        }

        static Step index(String digits) {
            return new Step(digits, Integer.parseInt(digits));
        }
    }
}

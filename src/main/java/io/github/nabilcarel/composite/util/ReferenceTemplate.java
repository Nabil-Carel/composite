package io.github.nabilcarel.composite.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A client-supplied string compiled into literal text and {@code ${...}} references.
 *
 * <p>The template is parsed exactly once, from the client's original text. Rendering walks
 * those tokens and never scans an inserted value, so data returned by a sub-request can
 * never be interpreted as a reference. Nested references such as {@code ${${a.b}.c}} are
 * rejected for the same reason.
 *
 * @since 0.0.1
 */
public final class ReferenceTemplate {

    private final List<Token> tokens;

    private ReferenceTemplate(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    /**
     * Compiles the given text.
     *
     * @param text the client-supplied text
     * @return the compiled template
     * @throws IllegalArgumentException if the text contains a nested reference
     */
    public static ReferenceTemplate parse(String text) {
        List<Token> tokens = new ArrayList<>();
        int position = 0;

        while (position < text.length()) {
            int start = text.indexOf("${", position);
            int end = start < 0 ? -1 : text.indexOf('}', start + 2);

            if (end < 0) {
                tokens.add(Token.literal(text.substring(position)));
                break;
            }

            String expression = text.substring(start + 2, end);

            if (expression.indexOf('$') >= 0 || expression.indexOf('{') >= 0) {
                throw new IllegalArgumentException("Nested references are not supported: " + text
                        + ". Use a single reference such as ${a.id}.");
            }

            if (expression.isEmpty()) {
                tokens.add(Token.literal(text.substring(position, end + 1)));
            } else {
                if (start > position) {
                    tokens.add(Token.literal(text.substring(position, start)));
                }
                tokens.add(Token.reference(expression));
            }

            position = end + 1;
        }

        return new ReferenceTemplate(tokens);
    }

    /**
     * Returns the compiled tokens in order.
     *
     * @return an immutable token list
     */
    public List<Token> tokens() {
        return tokens;
    }

    /**
     * Returns whether the template contains at least one reference.
     *
     * @return {@code true} if a reference is present
     */
    public boolean hasReferences() {
        return tokens.stream().anyMatch(Token::reference);
    }

    /**
     * Returns whether the whole template is one reference, which allows the referenced
     * value to keep its original JSON type.
     *
     * @return {@code true} if the template is exactly one reference
     */
    public boolean isSingleReference() {
        return tokens.size() == 1 && tokens.get(0).reference();
    }

    /**
     * Substitutes each reference once.
     *
     * @param values resolves a reference expression to its text
     * @return the rendered text
     */
    public String render(Function<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Token token : tokens) {
            result.append(token.reference() ? values.apply(token.text()) : token.text());
        }
        return result.toString();
    }

    /**
     * One compiled piece of a template.
     *
     * @param text the literal text, or the expression inside {@code ${...}}
     * @param reference whether this token is a reference
     */
    public record Token(String text, boolean reference) {

        static Token literal(String text) {
            return new Token(text, false);
        }

        static Token reference(String expression) {
            return new Token(expression, true);
        }
    }
}

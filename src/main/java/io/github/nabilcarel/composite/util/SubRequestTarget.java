package io.github.nabilcarel.composite.util;

import io.github.nabilcarel.composite.util.ReferenceTemplate.Token;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

/**
 * A sub-request URL parsed into application-relative path segments and a query.
 *
 * <p>A target can only describe a path on this application: schemes, authorities,
 * backslashes, fragments, path parameters, dot segments and encoded separators are
 * rejected. Reference values are percent-encoded for their position when rendered, so a
 * value cannot add a path segment, a query string or a query parameter.
 *
 * @since 0.0.1
 */
public final class SubRequestTarget {

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Set<String> FORBIDDEN_PATH_ESCAPES =
            Set.of("%2f", "%5c", "%2e", "%3b", "%3f", "%23", "%00");
    private static final String ROUTE_PLACEHOLDER = "composite-reference";

    private final List<List<Token>> segments;
    private final List<Token> query;

    private SubRequestTarget(List<List<Token>> segments, List<Token> query) {
        this.segments = segments;
        this.query = query;
    }

    /**
     * Parses a client-supplied URL template.
     *
     * @param url the URL, which must start with a single {@code /}
     * @return the parsed target
     * @throws IllegalArgumentException if the URL is not a safe application-relative target
     */
    public static SubRequestTarget parse(String url) {
        if (url == null || !url.startsWith("/") || url.startsWith("//")) {
            throw invalid(url);
        }

        List<List<Token>> segments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        List<Token> query = null;
        StringBuilder literal = new StringBuilder();

        for (Token token : ReferenceTemplate.parse(url).tokens()) {
            if (token.reference()) {
                flush(literal, query != null ? query : current);
                (query != null ? query : current).add(token);
                continue;
            }

            if (token.text().contains("${")) {
                throw invalid(url);
            }

            for (char character : token.text().toCharArray()) {
                if (Character.isISOControl(character) || character == '\\' || character == '#') {
                    throw invalid(url);
                }
                if (query != null) {
                    literal.append(character);
                } else if (character == '?') {
                    flush(literal, current);
                    segments.add(current);
                    current = null;
                    query = new ArrayList<>();
                } else if (character == '/') {
                    flush(literal, current);
                    segments.add(current);
                    current = new ArrayList<>();
                } else if (character == ';') {
                    throw invalid(url);
                } else {
                    literal.append(character);
                }
            }
        }

        if (query != null) {
            flush(literal, query);
        } else {
            flush(literal, current);
            segments.add(current);
        }

        // The leading slash produces an empty first entry.
        List<List<Token>> pathSegments = new ArrayList<>(segments.subList(1, segments.size()));
        validatePath(pathSegments, url);
        if (query != null) {
            validateEscapes(query, url, false);
        }
        return new SubRequestTarget(pathSegments, query);
    }

    /**
     * Returns the encoded path to use for route matching. References are replaced by a
     * neutral segment so a URL template can be checked before its values are known.
     *
     * @param url a URL template or a rendered URL
     * @return the path without its query, or empty if the URL is not a safe target
     */
    public static Optional<String> routablePath(String url) {
        if (url == null) {
            return Optional.empty();
        }

        try {
            String rendered = ReferenceTemplate.parse(url).render(expression -> ROUTE_PLACEHOLDER);
            if (rendered.indexOf('#') >= 0 || rendered.indexOf('\\') >= 0
                    || rendered.chars().anyMatch(Character::isISOControl)) {
                return Optional.empty();
            }

            int queryStart = rendered.indexOf('?');
            String path = queryStart < 0 ? rendered : rendered.substring(0, queryStart);
            return isSafeEncodedPath(path) ? Optional.of(path) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Renders the final encoded URL, substituting each reference once.
     *
     * @param values resolves a reference expression to its text
     * @return an encoded, application-relative URL
     * @throws IllegalArgumentException if a value would change the path structure
     */
    public String render(Function<String, String> values) {
        StringBuilder url = new StringBuilder();

        for (List<Token> segment : segments) {
            url.append('/').append(renderSegment(segment, values));
        }

        if (query != null) {
            url.append('?');
            for (Token token : query) {
                url.append(token.reference()
                        ? UriUtils.encode(values.apply(token.text()), StandardCharsets.UTF_8)
                        : encodeLiteral(token.text(),
                                text -> UriUtils.encodeQuery(text, StandardCharsets.UTF_8)));
            }
        }

        return url.toString();
    }

    private static String renderSegment(List<Token> segment, Function<String, String> values) {
        StringBuilder encoded = new StringBuilder();
        StringBuilder decoded = new StringBuilder();
        boolean hasReference = false;

        for (Token token : segment) {
            if (token.reference()) {
                hasReference = true;
                String value = values.apply(token.text());
                if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                        || value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(
                            "Value of ${" + token.text() + "} cannot be used in a URL path because it contains "
                                    + "'/', '\\' or a control character.");
                }
                encoded.append(UriUtils.encode(value, StandardCharsets.UTF_8));
                decoded.append(value);
            } else {
                encoded.append(encodeLiteral(token.text(),
                        text -> UriUtils.encodePathSegment(text, StandardCharsets.UTF_8)));
                decoded.append(UriUtils.decode(token.text(), StandardCharsets.UTF_8));
            }
        }

        if (isDotSegment(decoded.toString()) || (hasReference && decoded.length() == 0)) {
            throw new IllegalArgumentException("A referenced value produces an empty, '.' or '..' URL path segment.");
        }
        return encoded.toString();
    }

    private static void validatePath(List<List<Token>> segments, String url) {
        for (int i = 0; i < segments.size(); i++) {
            List<Token> segment = segments.get(i);
            if (segment.isEmpty() && i < segments.size() - 1) {
                throw invalid(url);
            }
            validateEscapes(segment, url, true);
            if (segment.size() == 1 && !segment.get(0).reference()
                    && isDotSegment(segment.get(0).text())) {
                throw invalid(url);
            }
        }
    }

    private static void validateEscapes(List<Token> tokens, String url, boolean path) {
        for (Token token : tokens) {
            if (token.reference()) {
                continue;
            }

            String withoutEscapes = PERCENT_ESCAPE.matcher(token.text()).replaceAll("");
            if (withoutEscapes.indexOf('%') >= 0) {
                throw invalid(url);
            }

            if (path) {
                Matcher matcher = PERCENT_ESCAPE.matcher(token.text());
                while (matcher.find()) {
                    if (FORBIDDEN_PATH_ESCAPES.contains(matcher.group().toLowerCase(Locale.ROOT))) {
                        throw invalid(url);
                    }
                }
            }
        }
    }

    private static boolean isSafeEncodedPath(String path) {
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf(';') >= 0) {
            return false;
        }

        String[] segments = path.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty() && i < segments.length - 1) {
                return false;
            }
            String decoded = UriUtils.decode(segments[i], StandardCharsets.UTF_8);
            if (isDotSegment(decoded) || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
                    || decoded.chars().anyMatch(Character::isISOControl)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDotSegment(String segment) {
        return ".".equals(segment) || "..".equals(segment);
    }

    /** Encodes literal text while keeping escapes the client already wrote. */
    private static String encodeLiteral(String text, UnaryOperator<String> encoder) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = PERCENT_ESCAPE.matcher(text);
        int position = 0;

        while (matcher.find()) {
            result.append(encoder.apply(text.substring(position, matcher.start())));
            result.append(matcher.group());
            position = matcher.end();
        }

        return result.append(encoder.apply(text.substring(position))).toString();
    }

    private static void flush(StringBuilder literal, List<Token> target) {
        if (literal.length() > 0) {
            target.add(new Token(literal.toString(), false));
            literal.setLength(0);
        }
    }

    private static IllegalArgumentException invalid(String url) {
        return new IllegalArgumentException("URL must be an application-relative path: " + url);
    }
}

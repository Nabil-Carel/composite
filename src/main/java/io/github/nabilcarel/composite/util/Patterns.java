package io.github.nabilcarel.composite.util;

import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Compiled {@link Pattern} constants shared across the composite library.
 *
 * <p>Centralising the regular expressions here avoids redundant compilation and makes
 * the matching rules easy to review in one place.
 *
 * @since 0.0.1
 */
@UtilityClass
public class Patterns {

    /**
     * Matches a {@code ${...}} placeholder expression.
     *
     * <p>Capture group 1 contains the expression inside the braces.
     * Example matches: {@code ${user.id}}, {@code ${items[0].name}}.
     */
    public static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Matches a single array/list index access in bracket notation.
     *
     * <p>Capture group 1 contains the numeric index as a string.
     * Example match: {@code [0]} in the path {@code [0].name}.
     */
    public static final Pattern INDEX_PATTERN = Pattern.compile("\\[(\\d+)\\]");

    /**
     * Splits a placeholder expression at the first {@code .} or {@code [} separator to
     * separate the root reference ID from the property path.
     *
     * <p>Example: splitting {@code user.address.city} yields {@code user} and
     * {@code address.city}.
     */
    public static final Pattern DEPENDENCY_SPLIT_PATTERN = Pattern.compile("[.\\[]");
}

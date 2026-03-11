package io.github.nabilcarel.composite.model;

/**
 * Parsed form of a {@code ${referenceId.propertyPath}} placeholder expression, together
 * with the resolved root object fetched from the
 * {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker}.
 *
 * @param objectId     the {@code referenceId} portion of the expression (before the first
 *                     {@code .} or {@code [})
 * @param propertyPath the navigation path within the root object, or {@code null} when the
 *                     placeholder refers to the root object itself
 * @param root         the deserialized response body identified by {@code objectId}
 * @since 0.0.1
 */
public record PlaceholderResolution(String objectId, String propertyPath, Object root) {}

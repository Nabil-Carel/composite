package io.github.nabilcarel.composite.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A structural pointer to a mutable position within a
 * {@link com.fasterxml.jackson.databind.JsonNode JsonNode} tree.
 *
 * <p>Instances are created during dependency extraction in
 * {@link io.github.nabilcarel.composite.model.request.SubRequest SubRequest} for each
 * body position that contains a {@code ${...}} placeholder expression. The reference
 * resolver then calls {@link #overrideValue(JsonNode)} to perform an in-place substitution
 * after the referenced value has been resolved, avoiding a full re-parse of the body.
 *
 * <p>Two concrete implementations are provided:
 * <ul>
 *   <li>{@link ObjectFieldReference} — points to a named field of a JSON object.</li>
 *   <li>{@link ArrayElementReference} — points to an element at a specific index of a JSON
 *       array.</li>
 * </ul>
 *
 * @see ObjectFieldReference
 * @see ArrayElementReference
 * @see io.github.nabilcarel.composite.service.ReferenceResolverService
 * @since 0.0.1
 */
public interface NodeReference {

    /**
     * Returns the current {@link JsonNode} at this position in the JSON tree.
     *
     * @return the current node; never {@code null}
     */
    JsonNode getValue();

    /**
     * Replaces the node at this position with {@code newValue}, mutating the enclosing
     * JSON tree in-place.
     *
     * @param newValue the resolved {@link JsonNode} to write at this position; must not be
     *                 {@code null}
     */
    void overrideValue(JsonNode newValue);
}

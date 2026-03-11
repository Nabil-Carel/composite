package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.model.request.SubRequest;

/**
 * Resolves {@code ${referenceId.propertyPath}} placeholder expressions within a
 * {@link SubRequest}'s URL, headers, and body by looking up completed sub-responses
 * from the shared response store.
 *
 * <p>Placeholder syntax supports:
 * <ul>
 *   <li><strong>Dot notation</strong>: {@code ${user.address.city}} — navigates nested
 *       object fields using Spring's {@link org.springframework.beans.BeanWrapperImpl
 *       BeanWrapper}.</li>
 *   <li><strong>Bracket index notation</strong>: {@code ${items[0].name}} — accesses an
 *       element by numeric index in arrays, {@link java.util.List Lists}, and other
 *       {@link java.util.Collection Collections}.</li>
 *   <li><strong>Bracket key notation</strong>: {@code ${config['db.host']}} — accesses a
 *       {@link java.util.Map Map} entry whose key contains characters that would otherwise
 *       be treated as path separators.</li>
 *   <li><strong>Nested placeholders</strong>: {@code ${${ref}.id}} — resolved iteratively
 *       up to
 *       up to {@code composite.max-resolution-iterations} passes.</li>
 * </ul>
 *
 * <p>When a placeholder refers to a value that is the sole content of a body field, the
 * original Java type of the resolved value is preserved (e.g. {@link Integer} or
 * {@link java.util.List}). When placeholders are embedded in a larger string they are
 * always coerced to their {@link Object#toString()} representation.
 *
 * @see ReferenceResolverServiceImpl
 * @see io.github.nabilcarel.composite.util.Patterns
 * @since 0.0.1
 */
public interface ReferenceResolverService {

    /**
     * Resolves all {@code ${...}} placeholder expressions in the sub-request URL, encodes
     * the result as a valid URI string, and stores it in {@link SubRequest}.
     *
     * @param subRequest the sub-request whose URL is to be resolved; must not be
     *                   {@code null}
     * @param batchId    the UUID of the parent composite request, used to look up completed
     *                   sub-responses; must not be {@code null}
     * @return the fully resolved and URI-encoded URL string
     * @throws io.github.nabilcarel.composite.exception.ReferenceResolutionException if a
     *         referenced response or property cannot be found
     */
    String resolveUrl(SubRequest subRequest, String batchId);

    /**
     * Resolves all {@code ${...}} placeholder expressions in the sub-request body,
     * performing in-place substitution on the
     * {@link com.fasterxml.jackson.databind.JsonNode} tree.
     *
     * <p>Resolved values replace their corresponding
     * {@link io.github.nabilcarel.composite.model.NodeReference NodeReference} nodes.
     * When a field contains only a single placeholder, the replacement preserves the
     * original Java type; when the placeholder is embedded in a larger string it is
     * converted to its string representation.
     *
     * @param subRequest the sub-request whose body is to be resolved; must not be
     *                   {@code null}
     * @param batchId    the UUID of the parent composite request; must not be {@code null}
     * @throws io.github.nabilcarel.composite.exception.ReferenceResolutionException if a
     *         referenced response or property cannot be found
     */
    void resolveBody(SubRequest subRequest, String batchId);

    /**
     * Resolves all {@code ${...}} placeholder expressions in the sub-request headers and
     * stores the sanitized results in {@link SubRequest}.
     *
     * <p>Resolved header values are sanitized to strip CRLF characters, preventing header
     * injection attacks.
     *
     * @param subRequest the sub-request whose headers are to be resolved; must not be
     *                   {@code null}
     * @param batchId    the UUID of the parent composite request; must not be {@code null}
     * @throws io.github.nabilcarel.composite.exception.ReferenceResolutionException if a
     *         referenced response or property cannot be found
     */
    void resolveHeaders(SubRequest subRequest, String batchId);
}

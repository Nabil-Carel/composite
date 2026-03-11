/**
 * Response model classes for the Composite library.
 *
 * <p>The public-facing response API consists of:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.model.response.CompositeResponse} — the
 *       aggregated response returned by the execute endpoint.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.response.SubResponse} — the outcome of
 *       a single sub-request, keyed by {@code referenceId} in the composite response map.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.response.CompositeDebugInfo} — optional
 *       debug payload included when {@code composite.debug-enabled=true}.</li>
 * </ul>
 *
 * <p>Internal infrastructure:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.model.response.SubResponseWrapper} — captures
 *       a sub-request response body into an in-memory buffer without committing the real
 *       HTTP response.</li>
 * </ul>
 */
package io.github.nabilcarel.composite.model.response;

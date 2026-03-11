/**
 * Request model classes for the Composite library.
 *
 * <p>The public-facing request API consists of:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.model.request.CompositeRequest} — the top-level
 *       request payload submitted to the execute endpoint.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.request.SubRequestDto} — describes a
 *       single sub-request within the composite batch.</li>
 * </ul>
 *
 * <p>Internal runtime types:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.model.request.SubRequest} — enriches a
 *       {@link io.github.nabilcarel.composite.model.request.SubRequestDto} with lazily-extracted
 *       dependency information and resolved values.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.request.CompositeRequestWrapper} — a
 *       replayable {@link jakarta.servlet.http.HttpServletRequestWrapper} that caches the
 *       request body.</li>
 * </ul>
 */
package io.github.nabilcarel.composite.model.request;

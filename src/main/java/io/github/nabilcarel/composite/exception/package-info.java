/**
 * Exception hierarchy for the Composite library.
 *
 * <p>Exceptions are organised by the phase in which they can occur:
 * <ul>
 *   <li><strong>Validation</strong>:
 *       {@link io.github.nabilcarel.composite.exception.ValidationException},
 *       {@link io.github.nabilcarel.composite.exception.CircularDependencyException}</li>
 *   <li><strong>Reference resolution</strong>:
 *       {@link io.github.nabilcarel.composite.exception.ReferenceResolutionException},
 *       {@link io.github.nabilcarel.composite.exception.UnresolvedReferenceException}
 *       (internal control-flow only)</li>
 *   <li><strong>Execution</strong>:
 *       {@link io.github.nabilcarel.composite.exception.CompositeExecutionException},
 *       {@link io.github.nabilcarel.composite.exception.RequestTimeoutException},
 *       {@link io.github.nabilcarel.composite.exception.ResponseParsingException}</li>
 * </ul>
 *
 * <p>Most exceptions are caught within the composite pipeline and translated into error
 * entries in the {@link io.github.nabilcarel.composite.model.response.CompositeResponse}
 * rather than propagated as HTTP error responses.
 */
package io.github.nabilcarel.composite.exception;

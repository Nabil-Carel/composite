/**
 * Annotations for the Composite library.
 *
 * <p>The primary annotation in this package is
 * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint @CompositeEndpoint},
 * which must be placed on any Spring MVC controller method that should be reachable via the
 * composite execution endpoint. The annotation doubles as an explicit security allowlist —
 * only annotated endpoints are eligible for composite execution.
 *
 * @see io.github.nabilcarel.composite.config.EndpointRegistry
 */
package io.github.nabilcarel.composite.annotation;

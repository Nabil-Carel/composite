/**
 * Configuration classes and configuration-property bindings for the Composite library.
 *
 * <p>This package contains:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.config.CompositeProperties} — bound to the
 *       {@code composite.*} configuration namespace.</li>
 *   <li>{@link io.github.nabilcarel.composite.config.CompositeLoopbackProperties} — bound to
 *       the {@code composite.loopback.*} namespace for the loopback WebClient.</li>
 *   <li>{@link io.github.nabilcarel.composite.config.EndpointRegistry} — discovers and
 *       maintains the allowlist of
 *       {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint @CompositeEndpoint}-annotated
 *       methods.</li>
 *   <li>{@link io.github.nabilcarel.composite.config.CompositeDispatchInterceptor} — verifies
 *       the handler Spring selected for each loopback sub-request.</li>
 * </ul>
 *
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration
 */
package io.github.nabilcarel.composite.config;

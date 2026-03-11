/**
 * Servlet filter that drives the composite request execution pipeline.
 *
 * <p>The {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter} is the
 * heart of the composite execution model. It intercepts incoming composite requests, builds
 * the dependency graph, fires the initial wave of dependency-free sub-requests, and sets up
 * the callbacks that chain dependent sub-requests as their dependencies complete.
 *
 * @see io.github.nabilcarel.composite.config.filter.CompositeRequestFilter
 */
package io.github.nabilcarel.composite.config.filter;

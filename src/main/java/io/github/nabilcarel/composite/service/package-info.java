/**
 * Service interfaces and implementations for the Composite library.
 *
 * <p>The service layer is structured around the following contracts:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.service.CompositeRequestService} — executes
 *       individual sub-requests and assembles the final composite response.</li>
 *   <li>{@link io.github.nabilcarel.composite.service.CompositeRequestValidator} — validates
 *       a composite request before execution, including dependency graph analysis.</li>
 *   <li>{@link io.github.nabilcarel.composite.service.ReferenceResolverService} — resolves
 *       {@code ${referenceId.propertyPath}} placeholder expressions in sub-request URLs,
 *       headers, and bodies.</li>
 *   <li>{@link io.github.nabilcarel.composite.service.AuthenticationForwardingService} —
 *       propagates security headers from the outer request to each sub-request.</li>
 *   <li>{@link io.github.nabilcarel.composite.service.CompositeBatchContext} — bridges the
 *       {@link io.github.nabilcarel.composite.model.ResponseTracker ResponseTracker}
 *       completion callbacks to the
 *       {@link io.github.nabilcarel.composite.model.SubRequestCoordinator SubRequestCoordinator}
 *       execution pipeline.</li>
 * </ul>
 */
package io.github.nabilcarel.composite.service;

/**
 * Core domain model for the Composite library.
 *
 * <p>This package contains the runtime coordination types:
 * <ul>
 *   <li>{@link io.github.nabilcarel.composite.model.ResponseTracker} /
 *       {@link io.github.nabilcarel.composite.model.ResponseTrackerImpl} — accumulates
 *       sub-responses and signals overall completion.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.SubRequestCoordinator} /
 *       {@link io.github.nabilcarel.composite.model.SubRequestCoordinatorImpl} — manages
 *       the dependency DAG and determines execution order.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.NodeReference},
 *       {@link io.github.nabilcarel.composite.model.ObjectFieldReference},
 *       {@link io.github.nabilcarel.composite.model.ArrayElementReference} — structural
 *       pointers into request body JSON trees for in-place placeholder substitution.</li>
 *   <li>{@link io.github.nabilcarel.composite.model.PlaceholderResolution} — intermediate
 *       value object produced during placeholder expression parsing.</li>
 * </ul>
 *
 * <p>Request and response DTOs are in the {@code model.request} and {@code model.response}
 * sub-packages respectively.
 */
package io.github.nabilcarel.composite.model;

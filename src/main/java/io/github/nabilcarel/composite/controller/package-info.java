/**
 * Built-in REST controller for the Composite library.
 *
 * <p>The {@link io.github.nabilcarel.composite.controller.CompositeController} exposes two
 * endpoints under the base path configured by
 * {@code composite.base-path}:
 * <ul>
 *   <li>{@code POST /execute} — executes a composite request.</li>
 *   <li>{@code GET /endpoints} — lists the registered composite-eligible endpoints.</li>
 * </ul>
 *
 * <p>Disable the built-in controller by setting
 * {@code composite.controller-enabled=false} and provide your own.
 *
 * @see io.github.nabilcarel.composite.controller.CompositeController
 */
package io.github.nabilcarel.composite.controller;

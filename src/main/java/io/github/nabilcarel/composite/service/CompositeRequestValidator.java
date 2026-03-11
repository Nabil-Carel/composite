package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import java.util.List;
import java.util.Set;

/**
 * Validates a {@link CompositeRequest} and its constituent {@link SubRequestDto sub-requests}
 * before execution begins.
 *
 * <p>Validation is performed eagerly in the
 * {@link io.github.nabilcarel.composite.config.filter.CompositeRequestFilter filter}, before
 * any sub-requests are dispatched. Any errors are collected into a list and surfaced to the
 * caller as a {@code 400 Bad Request} response; no sub-requests are executed if validation
 * fails.
 *
 * <p>The validation pipeline covers:
 * <ul>
 *   <li>Bean Validation constraints declared on {@link CompositeRequest} and
 *       {@link SubRequestDto}.</li>
 *   <li>Uniqueness of {@code referenceId} values within the request.</li>
 *   <li>Dependency integrity — all {@code ${referenceId...}} placeholders must reference
 *       a {@code referenceId} that is declared elsewhere in the same request.</li>
 *   <li>Circular dependency detection (DFS cycle check).</li>
 *   <li>Maximum dependency depth enforcement.</li>
 *   <li>Endpoint allowlist check — each target URL must match a
 *       {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint
 *       &#64;CompositeEndpoint}-annotated method in the
 *       {@link io.github.nabilcarel.composite.config.EndpointRegistry EndpointRegistry}.</li>
 *   <li>URL format correctness for statically-known URLs.</li>
 * </ul>
 *
 * @see CompositeRequestValidatorImpl
 * @since 0.0.1
 */
public interface CompositeRequestValidator {

    /**
     * Performs full validation of the composite request.
     *
     * @param request the composite request to validate; must not be {@code null}
     * @return a list of human-readable error messages; empty if the request is valid
     */
    List<String> validateRequest(CompositeRequest request);

    /**
     * Validates that all {@code ${referenceId...}} placeholder expressions in the given
     * sub-request refer to IDs present in {@code availableRefs}.
     *
     * @param request       the sub-request whose placeholders are to be validated
     * @param availableRefs the set of declared {@code referenceId}s in the composite request
     * @return a list of error messages for unknown references; empty if all are valid
     */
    List<String> validateReferences(SubRequest request, Set<String> availableRefs);

    /**
     * Validates that the target endpoint of the given sub-request is registered in the
     * {@link io.github.nabilcarel.composite.config.EndpointRegistry EndpointRegistry},
     * and that the HTTP method and body are consistent.
     *
     * @param request the sub-request to validate; must not be {@code null}
     * @return a list of error messages; empty if the endpoint is accessible
     */
    List<String> validateEndpointAccess(SubRequestDto request);

    /**
     * Validates the format of a fully resolved URL (i.e. after placeholder substitution).
     *
     * <p>Called after reference resolution to guard against SSRF via path traversal or
     * invalid URI constructions injected through placeholder values.
     *
     * @param url the resolved URL string to validate; must not be {@code null}
     * @return an error message if the URL is invalid, or {@code null} if it is valid
     */
    String validateResolvedUrlFormat(String url);
}

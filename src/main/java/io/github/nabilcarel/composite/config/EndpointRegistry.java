package io.github.nabilcarel.composite.config;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.util.SubRequestTarget;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.http.server.PathContainer;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Discovers and maintains an allowlist of Spring MVC handlers that are eligible for
 * composite execution.
 *
 * <p>At application startup (on {@link org.springframework.boot.context.event.ApplicationReadyEvent
 * ApplicationReadyEvent}), the registry reads every handler method registered in the
 * {@link RequestMappingHandlerMapping}, including those without
 * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint &#64;CompositeEndpoint}.
 *
 * <p>A sub-request path is allowed only when the handler Spring would route it to is
 * annotated. Knowing the unannotated routes is what makes this possible: {@code /users/me}
 * matches an annotated {@code /users/{id}}, but Spring prefers an unannotated
 * {@code /users/me} handler, so that request is refused. If several equally specific
 * mappings match, all of them must be annotated.
 *
 * @see io.github.nabilcarel.composite.annotation.CompositeEndpoint
 * @see io.github.nabilcarel.composite.service.CompositeRequestValidator
 * @since 0.0.1
 */
@Slf4j
@RequiredArgsConstructor
public class EndpointRegistry implements ApplicationListener<ApplicationReadyEvent> {
    private static final List<String> COMPOSITE_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ApplicationContext applicationContext;
    @Qualifier("requestMappingHandlerMapping")
    private final RequestMappingHandlerMapping handlerMapping;

    private volatile Map<String, List<Route>> routesByMethod = Map.of();
    private volatile Set<EndpointInfo> availableEndpoints = Set.of();

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        discoverEndpoints();
    }

    private void discoverEndpoints() {
        Map<String, List<Route>> routes = new HashMap<>();
        Set<EndpointInfo> endpoints = new LinkedHashSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            CompositeEndpoint annotation = entry.getValue().getMethodAnnotation(CompositeEndpoint.class);
            PathPatternsRequestCondition pathCondition = mapping.getPathPatternsCondition();

            if (pathCondition == null) {
                if (annotation != null) {
                    log.warn("Composite endpoint {} ignored: only PathPattern matching is supported",
                            entry.getValue());
                }
                continue;
            }

            for (PathPattern pattern : pathCondition.getPatterns()) {
                for (String method : methodsOf(mapping)) {
                    EndpointInfo info = null;

                    if (annotation != null) {
                        info = EndpointInfo.builder()
                                .pattern(pattern.getPatternString())
                                .method(method)
                                .build();
                        endpoints.add(info);
                        log.info("Registered composite endpoint: {} {}", method, pattern.getPatternString());
                    }

                    routes.computeIfAbsent(method, key -> new ArrayList<>()).add(new Route(pattern, info));
                }
            }
        }

        routesByMethod = Map.copyOf(routes);
        availableEndpoints = Collections.unmodifiableSet(endpoints);
    }

    private List<String> methodsOf(RequestMappingInfo mapping) {
        Set<RequestMethod> declared = mapping.getMethodsCondition().getMethods();
        if (declared.isEmpty()) {
            return COMPOSITE_METHODS;
        }
        return declared.stream().map(RequestMethod::name).filter(COMPOSITE_METHODS::contains).toList();
    }

    /**
     * Finds the annotated endpoint a sub-request would be routed to.
     *
     * @param method the HTTP method, in any case
     * @param url a URL template or rendered URL; the query string is ignored
     * @return the endpoint, or empty if the URL is unsafe or routes to an unannotated handler
     */
    public Optional<EndpointInfo> getEndpointInformations(String method, String url) {
        if (method == null) {
            return Optional.empty();
        }

        Optional<String> path = SubRequestTarget.routablePath(url);
        if (path.isEmpty()) {
            return Optional.empty();
        }

        PathContainer container = PathContainer.parsePath(path.get());
        PathPattern mostSpecific = null;
        EndpointInfo selected = null;
        boolean unannotated = false;

        for (Route route : routesByMethod.getOrDefault(method.toUpperCase(Locale.ROOT), List.of())) {
            if (!route.pattern().matches(container)) {
                continue;
            }

            int comparison = mostSpecific == null
                    ? -1
                    : PathPattern.SPECIFICITY_COMPARATOR.compare(route.pattern(), mostSpecific);

            if (comparison < 0) {
                mostSpecific = route.pattern();
                selected = route.endpoint();
                unannotated = route.endpoint() == null;
            } else if (comparison == 0) {
                unannotated |= route.endpoint() == null;
            }
        }

        return mostSpecific == null || unannotated ? Optional.empty() : Optional.of(selected);
    }

    public Set<EndpointInfo> getAvailableEndpoints() {
        return new HashSet<>(availableEndpoints);
    }

    /** A mapped path and its allowlist entry, which is {@code null} for unannotated handlers. */
    private record Route(PathPattern pattern, EndpointInfo endpoint) {
    }

    /**
     * Metadata about a registered composite-eligible endpoint.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    @Builder
    public static class EndpointInfo {

        /** The URL pattern registered for this endpoint. */
        private String pattern;

        /** The HTTP method of this endpoint (e.g. {@code GET}). */
        private String method;

        /** Optional human-readable description of the endpoint. */
        private String description;
    }
}

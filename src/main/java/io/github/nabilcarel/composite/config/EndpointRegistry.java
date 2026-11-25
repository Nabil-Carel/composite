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
import org.springframework.web.util.pattern.PathPatternParser;

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
        PathPatternParser parser = handlerMapping.getPatternParser() != null
                ? handlerMapping.getPatternParser()
                : PathPatternParser.defaultInstance;
        List<String> antMatched = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            CompositeEndpoint annotation = entry.getValue().getMethodAnnotation(CompositeEndpoint.class);
            PathPatternsRequestCondition pathCondition = mapping.getPathPatternsCondition();

            if (pathCondition == null) {
                if (annotation != null) {
                    antMatched.add(entry.getValue().toString());
                }
                continue;
            }

            for (PathPattern pattern : pathCondition.getPatterns()) {
                PathPattern relaxed = parser.parse(withoutConstraints(pattern.getPatternString()));
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

                    routes.computeIfAbsent(method, key -> new ArrayList<>()).add(new Route(pattern, relaxed, info));
                }
            }
        }

        // Starting anyway would reject every sub-request, which looks like a routing bug.
        if (!antMatched.isEmpty()) {
            throw new IllegalStateException("""
                    Composite endpoints need Spring MVC path patterns, but this application matches \
                    paths with AntPathMatcher, usually because of \
                    spring.mvc.pathmatch.matching-strategy=ant_path_matcher. Remove that setting or set \
                    it to path_pattern_parser. Affected handlers: %s""".formatted(String.join(", ", antMatched)));
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
     * <p>A URL template is checked before its references have values, so each reference stands
     * in as a placeholder segment and regex constraints such as {@code {id:\d+}} are ignored for
     * it. The rendered URL is checked again with the full patterns before it is sent.
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

        boolean template = url.contains("${");
        PathContainer container = PathContainer.parsePath(path.get());
        PathPattern mostSpecific = null;
        EndpointInfo selected = null;
        boolean unannotated = false;

        for (Route route : routesByMethod.getOrDefault(method.toUpperCase(Locale.ROOT), List.of())) {
            PathPattern pattern = template ? route.relaxed() : route.pattern();
            if (!pattern.matches(container)) {
                continue;
            }

            int comparison = mostSpecific == null
                    ? -1
                    : PathPattern.SPECIFICITY_COMPARATOR.compare(pattern, mostSpecific);

            if (comparison < 0) {
                mostSpecific = pattern;
                selected = route.endpoint();
                unannotated = route.endpoint() == null;
            } else if (comparison == 0) {
                unannotated |= route.endpoint() == null;
            }
        }

        return mostSpecific == null || unannotated ? Optional.empty() : Optional.of(selected);
    }

    /**
     * Removes regex constraints from URI variables: {@code /orders/{id:\d+}} becomes
     * {@code /orders/{id}}. Braces inside a constraint, as in {@code {code:[a-z]{3}}}, are
     * balanced so the variable ends at its own closing brace.
     */
    static String withoutConstraints(String pattern) {
        StringBuilder result = new StringBuilder(pattern.length());
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c != '{') {
                result.append(c);
                i++;
                continue;
            }

            int depth = 1;
            int colon = -1;
            int end = i + 1;
            while (end < pattern.length() && depth > 0) {
                char inner = pattern.charAt(end);
                if (inner == '\\') {
                    end += 2;
                    continue;
                }
                if (inner == '{') {
                    depth++;
                } else if (inner == '}') {
                    depth--;
                } else if (inner == ':' && depth == 1 && colon < 0) {
                    colon = end;
                }
                if (depth > 0) {
                    end++;
                }
            }

            if (depth > 0) {
                // Unbalanced: leave the rest unchanged and let the parser report it.
                result.append(pattern, i, pattern.length());
                break;
            }
            result.append(pattern, i, colon < 0 ? end : colon).append('}');
            i = end + 1;
        }
        return result.toString();
    }

    public Set<EndpointInfo> getAvailableEndpoints() {
        return new HashSet<>(availableEndpoints);
    }

    /**
     * A mapped path, the same path without regex constraints, and its allowlist entry, which
     * is {@code null} for unannotated handlers.
     */
    private record Route(PathPattern pattern, PathPattern relaxed, EndpointInfo endpoint) {
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

package io.github.nabilcarel.composite.config;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Discovers and maintains an allowlist of Spring MVC endpoints that are eligible for
 * composite execution.
 *
 * <p>At application startup (on {@link org.springframework.boot.context.event.ApplicationReadyEvent
 * ApplicationReadyEvent}), the registry scans all handler methods registered in the
 * {@link RequestMappingHandlerMapping} and retains only those annotated with
 * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint &#64;CompositeEndpoint}.
 *
 * <p>During request validation and execution, every sub-request URL is matched against the
 * registered patterns using Spring's {@link AntPathMatcher}. A sub-request that targets an
 * unregistered URL is rejected immediately, preventing Server-Side Request Forgery (SSRF)
 * attacks. The {@link EndpointInfo} stored for each registered endpoint carries the
 * {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint#value() response class}
 * needed to deserialize the loopback response.
 *
 * <p>For efficiency, patterns are indexed by their first path segment so that the matching
 * loop only compares candidates with a matching prefix.
 *
 * @see io.github.nabilcarel.composite.annotation.CompositeEndpoint
 * @see io.github.nabilcarel.composite.service.CompositeRequestValidator
 * @since 0.0.1
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EndpointRegistry implements ApplicationListener<ApplicationReadyEvent> {
    private final ApplicationContext applicationContext;
    @Qualifier("requestMappingHandlerMapping")
    private final RequestMappingHandlerMapping handlerMapping;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Map<EndpointPattern, EndpointInfo> availableEndpoints = new HashMap<>();
    private final Map<String, Set<EndpointPattern>> patternsByFirstSegment = new HashMap<>();

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        discoverEndpoints();
    }

    private void discoverEndpoints() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            CompositeEndpoint annotation = handlerMethod.getMethodAnnotation(CompositeEndpoint.class);

            if (annotation != null) {
                RequestMappingInfo mapping = entry.getKey();
                if (mapping.getPathPatternsCondition() != null) {
                    String pattern = mapping.getPathPatternsCondition().getPatterns().iterator().next()
                            .getPatternString();
                    Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();

                    for (RequestMethod method : methods) {
                        EndpointPattern endpointPattern = new EndpointPattern(method.name(), pattern);
                        EndpointInfo info = EndpointInfo.builder()
                                .pattern(pattern)
                                .method(method.name())
                                .returnClass(annotation.value())
                                .build();

                        availableEndpoints.put(endpointPattern, info);
                        String firstSegment = getFirstSegment(pattern);
                        patternsByFirstSegment.computeIfAbsent(firstSegment, k -> new HashSet<>())
                                .add(endpointPattern);

                        log.info("Registered composite endpoint: {}", endpointPattern);
                    }
                }
            }
        }
    }

    public Optional<EndpointInfo> getEndpointInformations(String method, String url) {
        String normalizedUrl = normalizePath(url);
        String firstSegment = getFirstSegment(normalizedUrl);
        if (!patternsByFirstSegment.containsKey(firstSegment)) {
            return Optional.empty();
        }

        return findMatchingEndpoint(method, normalizedUrl);
    }

    private String normalizePath(String url) {
        try {
            URI uri = new URI(url).normalize();
            String path = uri.getPath();
            String query = uri.getRawQuery();
            return query != null ? path + "?" + query : path;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private Optional<EndpointInfo> findMatchingEndpoint(String method, String path) {
        String firstSegment = getFirstSegment(path);
        Set<EndpointPattern> potentialPatterns =
                patternsByFirstSegment.getOrDefault(firstSegment, Set.of());

        return potentialPatterns.stream()
                .filter(p -> p.getMethod().equalsIgnoreCase(method))
                .filter(p -> pathMatcher.match(p.getPattern(), path))
                .map(availableEndpoints::get)
                .findFirst();
    }

    private String getFirstSegment(String path) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        int nextSlash = normalizedPath.indexOf('/');
        return nextSlash > 0 ?
                normalizedPath.substring(0, nextSlash) :
                normalizedPath;
    }

    public Set<EndpointInfo> getAvailableEndpoints() {
        return new HashSet<>(availableEndpoints.values());
    }

    /**
     * Composite key used to index registered endpoints by HTTP method and Ant-style URL
     * pattern.
     *
     * @since 0.0.1
     */
    @Getter
    @Setter
    @AllArgsConstructor
    public static class EndpointPattern {

        /** The HTTP method (e.g. {@code GET}, {@code POST}). */
        private String method;

        /** The Ant-style URL pattern (e.g. {@code /api/users/{id}}). */
        private String pattern;

        @Override
        public String toString() {
            return method + " " + pattern;
        }
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

        /** The Ant-style URL pattern registered for this endpoint. */
        private String pattern;

        /** The HTTP method of this endpoint (e.g. {@code GET}). */
        private String method;

        /** Optional human-readable description of the endpoint. */
        private String description;

        /**
         * The Java class into which the endpoint's response body should be deserialized,
         * as declared by {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint#value()}.
         */
        private Class<?> returnClass;
    }
}

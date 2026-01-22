package io.github.nabilcarel.composite.config;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
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
        String firstSegment = getFirstSegment(url);
        if (!patternsByFirstSegment.containsKey(firstSegment)) {
            throw new RuntimeException(String.format("Endpoint %s not available for composite use", url));
        }

        return findMatchingEndpoint(method, url);
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

    @Getter
    @Setter
    @AllArgsConstructor
    public static class EndpointPattern {
        private String method;
        private String pattern;

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    @Getter
    @Setter
    @Builder
    public static class EndpointInfo {
        private String pattern;
        private String method;
        private String description;
        private Class<?> returnClass;
    }
}

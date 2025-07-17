package com.example.composite.config;

import com.example.composite.annotation.CompositeEndpoint;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
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

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class EndpointRegistry implements ApplicationListener<ApplicationReadyEvent>{
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ApplicationContext applicationContext;
    private final Map<EndpointPattern, EndpointInfo> availableEndpoints = new HashMap<>();
    private final Map<String, Set<EndpointPattern>> patternsByFirstSegment = new HashMap<>();
    private LoadingCache<String, Optional<EndpointInfo>> matchCache;
    
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
    
    private void discoverEndpoints() {
        RequestMappingHandlerMapping handlerMapping = applicationContext.getBean(RequestMappingHandlerMapping.class);
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
    
    @PostConstruct
    void initCache() {
        matchCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build(this::findMatchingEndpointUncached);
    }
    
    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        discoverEndpoints();
    }

    public Optional<EndpointInfo> getEndpointInformations(String method, String url) {
        try {
            // String path = url.startsWith("http") ?
            //     new URI(url).getPath() : url;

            String firstSegment = getFirstSegment(url);
            if (!patternsByFirstSegment.containsKey(firstSegment)) {
                throw new RuntimeException(String.format("Endpoint %s not available for composite use", url));
            }

            return matchCache.get(method + ":" + url);

        } catch (Exception e) {
            log.error("Error checking endpoint availability", e);
            throw new RuntimeException(e);
        }
    }

    private String getFirstSegment(String path) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        int nextSlash = normalizedPath.indexOf('/');
        return nextSlash > 0 ? 
            normalizedPath.substring(0, nextSlash) : 
            normalizedPath;
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


    private Optional<EndpointInfo> findMatchingEndpointUncached(String key) {
        String[] parts = key.split(":", 2);
        String method = parts[0];
        String path = parts[1];
        
        String firstSegment = getFirstSegment(path);
        Set<EndpointPattern> potentialPatterns = 
            patternsByFirstSegment.getOrDefault(firstSegment, Set.of());
        
        return potentialPatterns.stream()
            .filter(p -> p.getMethod().equalsIgnoreCase(method))
            .filter(p -> pathMatcher.match(p.getPattern(), path))
            .map(availableEndpoints::get)
            .findFirst();
    }

    public Set<EndpointInfo> getAvailableEndpoints() {
        return new HashSet<>(availableEndpoints.values());
    }
}

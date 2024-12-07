package com.example.composite.config;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

import com.example.composite.annotation.CompositeEndpoint;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class EndpointRegistry implements ApplicationListener<ApplicationReadyEvent>{
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ApplicationContext applicationContext;
    private final CompositeApiProperties properties;
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
    
    @Getter
    @Setter
    @Builder
    public static class EndpointInfo {
        private String pattern;
        private String method;
        private String description;
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

    private void discoverEndpoints() {
        RequestMappingHandlerMapping handlerMapping = applicationContext.getBean(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            CompositeEndpoint annotation = handlerMethod.getMethodAnnotation(CompositeEndpoint.class);

            if (annotation != null) {
                RequestMappingInfo mapping = entry.getKey();
                if (mapping.getPatternsCondition() != null) {
                    String pattern = mapping.getPatternsCondition().getPatterns().iterator().next();
                    Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();

                    for (RequestMethod method : methods) {
                        EndpointPattern endpointPattern = new EndpointPattern(method.name(), pattern);
                        EndpointInfo info = EndpointInfo.builder()
                            .pattern(pattern)
                            .method(method.name())
                            .description(annotation.description())
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

    private String getFirstSegment(String path) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        int nextSlash = normalizedPath.indexOf('/');
        return nextSlash > 0 ? 
            normalizedPath.substring(0, nextSlash) : 
            normalizedPath;
    }

    public boolean isEndpointAvailable(String method, String url) {
        try {
            String path = url.startsWith("http") ? 
                new URI(url).getPath() : url;
            
            String firstSegment = getFirstSegment(path);
            if (!patternsByFirstSegment.containsKey(firstSegment)) {
                return false;
            }
            
            return matchCache.get(method + ":" + path).isPresent();
            
        } catch (Exception e) {
            log.error("Error checking endpoint availability", e);
            return false;
        }
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

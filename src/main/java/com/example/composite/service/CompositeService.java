package com.example.composite.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeanWrapper;


import com.example.composite.config.BaseUrlProvider;
import com.example.composite.config.EndpointRegistry;
import com.example.composite.exception.CircularDependencyException;
import com.example.composite.exception.CompositeExecutionException;
import com.example.composite.exception.ReferenceResolutionException;
import com.example.composite.model.context.ExecutionContext;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;

import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompositeService {
    private final RestTemplate compositeRestTemplate;
    private final EndpointRegistry endpointRegistry;
    private final TransactionTemplate transactionTemplate;
    private final Validator validator;
    private final BaseUrlProvider baseUrlProvider;

    private String baseUrl;

    @Transactional
    public CompositeResponse processRequests(CompositeRequest request) {

        return transactionTemplate.execute(status -> {
            ExecutionContext context = new ExecutionContext();
            
            try {
                validateRequest(request);
                if (request.isAllOrNone()) {
                    return processAllOrNone(request, context);
                } else {
                    return processIndependently(request, context);
                }
            } catch (Exception e) {
                status.setRollbackOnly();
                log.error("Failed to process composite request", e);
                throw e;
            }
        });
    }

    @PostConstruct
    public void setBaseUrl(){
        baseUrl = baseUrlProvider.getBaseUrl();
    }

    private CompositeResponse processAllOrNone(CompositeRequest request, 
                                             ExecutionContext context) {
        Map<String, SubResponse> responses = new LinkedHashMap<>();
        
        for (SubRequest subRequest : request.getSubRequests()) {
            validateReferences(subRequest, context);
            validateEndpointAccess(subRequest);
            
            SubResponse response = executeRequest(subRequest, context);
            
            if (!response.getHttpStatus().is2xxSuccessful()) {
                throw new CompositeExecutionException(
                    "Request failed: " + subRequest.getReferenceId());
            }
            
            responses.put(subRequest.getReferenceId(), response);
            context.getResponseMap().put(subRequest.getReferenceId(), response);
        }
        
        return buildResponse(responses);
    }

    private CompositeResponse processIndependently(CompositeRequest request, 
                                                 ExecutionContext context) {
        Map<String, SubResponse> responses = new LinkedHashMap<>();
        
        for (SubRequest subRequest : request.getSubRequests()) {
            try {
                validateReferences(subRequest, context);
                validateEndpointAccess(subRequest);
                
                SubResponse response = executeRequest(subRequest, context);
                responses.put(subRequest.getReferenceId(), response);
                context.getResponseMap().put(subRequest.getReferenceId(), response);
            } catch (Exception e) {
                log.error("Failed executing request: {}", 
                    subRequest.getReferenceId(), e);
                responses.put(subRequest.getReferenceId(), 
                    createErrorResponse(subRequest, e));
            }
        }
        
        return buildResponse(responses);
    }

    private SubResponse executeRequest(SubRequest request, ExecutionContext context) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, 
                entry -> Collections.singletonList(entry.getValue()))));

        String resolvedUrl = (String) resolveReferences(request.getUrl(), context.getResponseMap());
        
        validateResolvedUrlFormat(resolvedUrl);
        Object resolvedBody = resolveReferences(request.getBody(), 
            context.getResponseMap());

        HttpEntity<?> httpEntity = new HttpEntity<>(resolvedBody, headers);

        try {
            ResponseEntity<Object> response = compositeRestTemplate.exchange(
                baseUrl + resolvedUrl,
                HttpMethod.valueOf(request.getMethod().toUpperCase()),
                httpEntity,
                Object.class
            );

            return SubResponse.builder()
                .httpStatus(HttpStatus.valueOf(response.getStatusCode().value()))
                .referenceId(request.getReferenceId())
                .body(response.getBody())
                .headers(headers.toSingleValueMap())
                .build();
        } catch (HttpStatusCodeException e) {
            return createErrorResponse(request, e);
        }
    }

    private void validateReferences(SubRequest request, ExecutionContext context) {
        Set<String> missingDeps = request.getDependencies().stream()
            .filter(dep -> !context.getResponseMap().containsKey(dep))
            .collect(Collectors.toSet());
            
        if (!missingDeps.isEmpty()) {
            throw new ValidationException(
                "Missing dependencies: " + missingDeps);
        }

        validateExpression(request.getUrl(), context.getResponseMap().keySet());
        if (request.getBody() != null) {
            validateExpression(String.valueOf(request.getBody()), 
                context.getResponseMap().keySet());
        }
    }

    private void validateExpression(String expression, Set<String> availableRefs) {
        Matcher matcher = Pattern.compile("\\$\\{([^}]+)\\}")
            .matcher(expression);
            
        while (matcher.find()) {
            String reference = matcher.group(1);
            String refId = reference.split("\\.")[0];
            
            if (!availableRefs.contains(refId)) {
                throw new ValidationException(
                    "Invalid reference: " + reference + 
                    ". Available references: " + availableRefs);
            }
        }
    }

    private Object resolveReferences(Object value, Map<String, SubResponse> responseMap) {
        if (value == null)
            return null;
    
        if (value instanceof String) {
            String strValue = (String) value;
            Matcher matcher = Pattern.compile("\\$\\{([^}]+)\\}")
                    .matcher(strValue);
            StringBuffer resolved = new StringBuffer();

            while (matcher.find()) {
                String reference = matcher.group(1);
                String[] parts = reference.split("\\.");

                // Get the referenced response
                SubResponse referencedResponse = responseMap.get(parts[0]);
                if (referencedResponse == null) {
                    throw new ReferenceResolutionException(
                            "Referenced response not found: " + parts[0], reference);
                }

                // Handle the special 'body' path component
                Object replacement = referencedResponse;
                for (int i = 1; i < parts.length; i++) {
                    String part = parts[i];

                    // Special handling for 'body' property
                    if (i == 1 && "body".equals(part)) {
                        replacement = referencedResponse.getBody();
                        continue;
                    }

                    // Navigate through the object structure
                    if (replacement == null) {
                        throw new ReferenceResolutionException(
                                "Null value while resolving path: " + reference, reference);
                    }

                    replacement = navigateObject(replacement, part);
                }

                if (replacement == null) {
                    throw new ReferenceResolutionException(
                            "Resolved to null value: " + reference, reference);
                }

                matcher.appendReplacement(resolved,
                        Matcher.quoteReplacement(String.valueOf(replacement)));
            }

            matcher.appendTail(resolved);
            return resolved.toString();
        }
        return value;
}

private Object navigateObject(Object obj, String key) {
    if (obj instanceof Map) {
        return ((Map<?, ?>) obj).get(key);
    } 
    
    // Handle array/list index access (e.g., items[0])
    Matcher arrayMatcher = Pattern.compile("(\\w+)\\[(\\d+)\\]").matcher(key);
    if (arrayMatcher.matches()) {
        String arrayKey = arrayMatcher.group(1);
        int index = Integer.parseInt(arrayMatcher.group(2));
        
        if (obj instanceof Map) {
            Object array = ((Map<?, ?>) obj).get(arrayKey);
            if (array instanceof List) {
                return ((List<?>) array).get(index);
            }
            if (array instanceof Object[]) {
                return ((Object[]) array)[index];
            }
        }
    }

    BeanWrapper wrapper = new BeanWrapperImpl(obj);
    if (wrapper.isReadableProperty(key)) {
        return wrapper.getPropertyValue(key);
    }

    throw new ReferenceResolutionException(
        "Cannot navigate to path component: " + key, key);
}

    private SubResponse createErrorResponse(SubRequest request, Exception e) {
        return SubResponse.builder()
            .referenceId(request.getReferenceId())
            .httpStatus(e instanceof HttpStatusCodeException ? 
                HttpStatus.valueOf(((HttpStatusCodeException) e).getStatusCode().value()): 
                HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of(
                "error", e.getMessage(),
                "timestamp", Instant.now()
            ))
            .build();
    }

    private CompositeResponse buildResponse(Map<String, SubResponse> responses) {
        boolean hasErrors = responses.values().stream()
                .anyMatch(r -> !r.getHttpStatus().is2xxSuccessful());

        return CompositeResponse.builder()
                .responses(responses)
                .hasErrors(hasErrors)
                .build();
    }
    
    private void validateRequest(CompositeRequest request) {
        // Basic validation using javax validation
        Set<ConstraintViolation<CompositeRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<List<String>> violationMessages = new ArrayList<>();
            for (ConstraintViolation<CompositeRequest> violation : violations) {
                violationMessages.add(Collections.singletonList(violation.getMessage())); // Group messages
            }

            if (!violationMessages.isEmpty()) {
                throw new ValidationException("Invalid request " + violationMessages);
            }
        }

        // Validate unique reference IDs
        Set<String> referenceIds = new HashSet<>();
        for (SubRequest subRequest : request.getSubRequests()) {
            if (!referenceIds.add(subRequest.getReferenceId())) {
                throw new ValidationException(
                        "Duplicate reference ID found: " + subRequest.getReferenceId());
            }
        }

        // Validate dependencies
        validateDependencies(request.getSubRequests());

        // Validate request URLs and access
        for (SubRequest subRequest : request.getSubRequests()) {
            validateEndpointAccess(subRequest);
        }

        // Validate timeout configuration if present
        if (request.getTimeout() != null && request.getTimeout().isNegative()) {
            throw new ValidationException("Timeout duration cannot be negative");
        }
    }
    
    private void validateDependencies(List<SubRequest> requests) {
        // Build dependency graph
        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        for (SubRequest request : requests) {
            dependencyGraph.put(request.getReferenceId(), 
                new HashSet<>(request.getDependencies()));
        }

        // Check for missing dependencies
        for (SubRequest request : requests) {
            for (String dependency : request.getDependencies()) {
                if (!dependencyGraph.containsKey(dependency)) {
                    throw new ValidationException(
                        "Missing dependency reference: " + dependency + 
                        " for request: " + request.getReferenceId());
                }
            }
        }

        // Check for circular dependencies
        Set<String> visited = new HashSet<>();
        Set<String> currentPath = new HashSet<>();

        for (String referenceId : dependencyGraph.keySet()) {
            if (!visited.contains(referenceId)) {
                if (hasCircularDependency(referenceId, dependencyGraph, visited, currentPath)) {
                    throw new CircularDependencyException(
                        "Circular dependency detected in path: " + currentPath);
                }
            }
        }

        // Validate dependency depth
        int maxDepth = calculateMaxDependencyDepth(dependencyGraph);
        if (maxDepth > 10) { // configurable max depth
            throw new ValidationException("Maximum dependency depth exceeded: " + maxDepth);
        }
    }

    private boolean hasCircularDependency(
            String current,
            Map<String, Set<String>> dependencyGraph,
            Set<String> visited,
            Set<String> currentPath) {
        
        if (currentPath.contains(current)) {
            return true;
        }

        if (visited.contains(current)) {
            return false;
        }

        visited.add(current);
        currentPath.add(current);

        for (String dependency : dependencyGraph.get(current)) {
            if (hasCircularDependency(dependency, dependencyGraph, visited, currentPath)) {
                return true;
            }
        }

        currentPath.remove(current);
        return false;
    }

    private int calculateMaxDependencyDepth(Map<String, Set<String>> dependencyGraph) {
        Map<String, Integer> depth = new HashMap<>();
        
        for (String referenceId : dependencyGraph.keySet()) {
            calculateDepth(referenceId, dependencyGraph, depth);
        }

        return depth.values().stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
    }

    private int calculateDepth(
            String current,
            Map<String, Set<String>> dependencyGraph,
            Map<String, Integer> depth) {
        
        if (depth.containsKey(current)) {
            return depth.get(current);
        }

        if (dependencyGraph.get(current).isEmpty()) {
            depth.put(current, 0);
            return 0;
        }

        int maxChildDepth = dependencyGraph.get(current).stream()
            .mapToInt(dep -> calculateDepth(dep, dependencyGraph, depth))
            .max()
            .orElse(-1);

        int currentDepth = maxChildDepth + 1;
        depth.put(current, currentDepth);
        return currentDepth;
    }

    private void validateEndpointAccess(SubRequest request) {
            // Validate endpoint availability
            if (!endpointRegistry.isEndpointAvailable(request.getMethod(), request.getUrl())) {
                throw new ValidationException(
                    "Endpoint not available: " + request.getMethod() + " " + request.getUrl());
            }

            // Validate request body against method
            validateRequestBody(request);
    }

    private void validateRequestBody(SubRequest request) {
        boolean hasBody = request.getBody() != null;
        boolean requiresBody = Arrays.asList("POST", "PUT", "PATCH")
                .contains(request.getMethod().toUpperCase());
        boolean forbidsBody = Arrays.asList("GET", "DELETE", "HEAD", "OPTIONS")
                .contains(request.getMethod().toUpperCase());

        if (requiresBody && !hasBody) {
            throw new ValidationException(
                    "Request body is required for " + request.getMethod() + " method");
        }

        if (forbidsBody && hasBody) {
            throw new ValidationException(
                    "Request body is not allowed for " + request.getMethod() + " method");
        }
    }
    
    private void validateResolvedUrlFormat(String url) {
        // Validate URL format
        try{
             URI uri = new URI(baseUrl + url);
            if (!uri.isAbsolute() && !url.startsWith("/")) {
                throw new ValidationException(
                    "URL must be absolute or start with /: " + url);
            }
        }
        catch (URISyntaxException e) {
            throw new ValidationException("Invalid URL format: " + url);
        }
}
}

package com.example.composite.service;

import com.example.composite.config.EndpointRegistry;
import com.example.composite.config.EndpointRegistry.EndpointInfo;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.request.SubRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class CompositeRequestValidatorImpl implements CompositeRequestValidator {
    private final Validator validator;
    private final EndpointRegistry endpointRegistry;

    @Value("${composite.max-depth:10}")
    private int maxDepth;

    public List<String> validateRequest(CompositeRequest request) {
        List<String> errors = new ArrayList<>();
        Set<String> references = new HashSet<>();

        for (int i = 0; i < request.getSubRequests().size(); i++) {
            if(!references.add(request.getSubRequests().get(i).getReferenceId())){
                errors.add("Duplicate reference ID found: " + request.getSubRequests().get(i).getReferenceId());
                log.error(errors.get(errors.size() - 1));
            }
        }

        // Basic validation using javax validation
        Set<ConstraintViolation<CompositeRequest>> violations = validator.validate(request);

        if (!violations.isEmpty()) {
            for (ConstraintViolation<CompositeRequest> violation : violations) {
                errors.add(violation.getMessage());
            }

            log.error("Invalid request {}", errors);
        }

        // Validate unique reference IDs
        Set<String> referenceIds = new HashSet<>();

        for (SubRequestDto subRequest : request.getSubRequests()) {
            if (!referenceIds.add(subRequest.getReferenceId())) {
                errors.add("Duplicate reference ID found: " + subRequest.getReferenceId());
                log.error(errors.get(errors.size() - 1));
            }
        }

        // Validate dependencies
        errors.addAll(validateDependencies(request.getSubRequests()));

        // Validate request URLs and access
        for (SubRequestDto subRequest : request.getSubRequests()) {
            errors.addAll(validateEndpointAccess(subRequest));
        }

        return errors;
    }

    public List<String> validateReferences(SubRequest request, Set<String> availableRefs) {
        return new ArrayList<>(validateExpression(request.getUrl(), availableRefs));
    }

    private List<String> validateExpression(String expression, Set<String> availableRefs) {
        List<String> errors = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\$\\{([^}]+)}")
                .matcher(expression);

        while (matcher.find()) {
            String reference = matcher.group(1);

            // Validate reference format - must be dot-separated identifiers
            if (!reference.matches("[a-zA-Z0-9]+(?>\\.(?>[a-zA-Z0-9]+))*")) {
                errors.add("Invalid reference format: " + reference);
                log.error("Invalid reference format: {}", reference);
            }

            String refId = reference.split("\\.")[0];

            if (!availableRefs.contains(refId)) {
                errors.add("Invalid reference: " + reference +
                                ". Available references: " + availableRefs);
                log.error(errors.get(errors.size() - 1));
            }
        }

        return errors;
    }

    private List<String> validateDependencies(List<SubRequestDto> requests) {
        // Build dependency graph
        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        List<String> errors = new ArrayList<>();

        for (SubRequestDto request : requests) {
            dependencyGraph.put(request.getReferenceId(),
                    new SubRequest(request).getDependencies());
        }

        // Check for missing dependencies
        for (SubRequestDto request : requests) {
            for (String dependency : new SubRequest(request).getDependencies()) {
                if (!dependencyGraph.containsKey(dependency)) {
                    errors.add("Missing dependency reference: '" + dependency +
                                    "' for request: " + request.getReferenceId());
                    log.error(errors.get(errors.size() - 1));
                }
            }
        }

        // Check for circular dependencies
        Set<String> visited = new HashSet<>();
        Set<String> currentPath = new HashSet<>();

        for (String referenceId : dependencyGraph.keySet()) {
            if (!visited.contains(referenceId) &&
                hasCircularDependency(referenceId, dependencyGraph, visited, currentPath)) {
                    errors.add("Circular dependency detected in path: " + currentPath);
                    log.error(errors.get(errors.size() - 1));
            }
        }

        // Validate dependency depth
        int currentDepth = calculateMaxDependencyDepth(dependencyGraph);

        if (currentDepth > maxDepth) {
            errors.add("Maximum dependency depth exceeded: current=" + currentDepth + " maxDepth=" + maxDepth);
            log.error(errors.get(errors.size() - 1));
        }

        return errors;
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

    public List<String> validateEndpointAccess(SubRequestDto request) {
        List<String> errors = new ArrayList<>();
        String methodError = validateHttpMethod(request.getMethod());

        if(methodError != null){
            errors.add(methodError);
        }

        // Validate endpoint availability
        Optional<EndpointInfo> endpointInfo = endpointRegistry.getEndpointInformations(request.getMethod(),
                request.getUrl());

        if (endpointInfo.isEmpty()) {
            errors.add("Endpoint not available: " + request.getMethod() + " " + request.getUrl());
            log.error(errors.get(errors.size() - 1));
        }

        // Validate request body against method
        errors.addAll(validateRequestBody(request));

        // Validate URL format
        String urlError = validateResolvedUrlFormat(request.getUrl());

        if (urlError != null) {
            errors.add(urlError);
        }

        return errors;
    }

    private List<String> validateRequestBody(SubRequestDto request) {
        List<String> errors = new ArrayList<>();

        boolean hasBody = request.getBody() != null;
        boolean requiresBody = Arrays.asList("POST", "PUT", "PATCH")
                .contains(request.getMethod().toUpperCase());
        boolean forbidsBody = Arrays.asList("GET", "DELETE", "HEAD", "OPTIONS")
                .contains(request.getMethod().toUpperCase());

        if (requiresBody && !hasBody) {
            errors.add("Request body is required for " + request.getMethod() + " method");
            log.error(errors.get(0));
        }

        if (forbidsBody && hasBody) {
            errors.add("Request body is not allowed for " + request.getMethod() + " method");
            log.error(errors.get(0)); // This if and the previous one are mutually exclusive
        }

        return errors;
    }

    private String validateHttpMethod(String method) {
        if (method == null || method.trim().isEmpty()) {
            return "HTTP method cannot be null or empty";
        }

        Set<String> validMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
        String normalizedMethod = method.toUpperCase();

        if (!validMethods.contains(normalizedMethod)) {
            return "Invalid HTTP method: " + method +
                    ". Valid methods are: " + String.join(", ", validMethods);
        }

         return null;
    }

    public String validateResolvedUrlFormat(String url) {
        String error = null;

        try {
            URI uri = new URI(url);

            if (!uri.isAbsolute() && !url.startsWith("/")) {
                error = "URL must be absolute or start with /: " + url;
                log.error(error);
            }
        }
        catch (URISyntaxException e) {
            error = "Invalid URL format: " + url;
            log.error(error);
        }

        return error;
    }
}
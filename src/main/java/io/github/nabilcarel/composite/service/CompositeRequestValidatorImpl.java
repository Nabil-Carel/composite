package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.config.EndpointRegistry.EndpointInfo;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.util.SubRequestHeaderPolicy;
import io.github.nabilcarel.composite.util.SubRequestTarget;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompositeRequestValidatorImpl implements CompositeRequestValidator {
    private static final String URL_RULES = "Use an application-relative path such as /api/users/1, "
            + "without a host, fragment, '..' segment, encoded separator or ';' parameter.";

    private final Validator validator;
    private final EndpointRegistry endpointRegistry;
    private final CompositeProperties properties;

    public List<String> validateRequest(CompositeRequest request) {
        List<String> errors = new ArrayList<>();

        if (request.getSubRequests() == null || request.getSubRequests().isEmpty()) {
            errors.add("At least one sub-request is required. Add an entry to 'subRequests'.");
            return errors;
        }

        if (request.getSubRequests().stream().anyMatch(Objects::isNull)) {
            errors.add("Sub-requests must not be null. Remove null entries from 'subRequests'.");
            return errors;
        }

        if (request.getSubRequests().size() > properties.getMaxSubRequestsPerComposite()) {
            errors.add("Too many sub-requests: " + request.getSubRequests().size() +
                    " exceeds the maximum of " + properties.getMaxSubRequestsPerComposite() +
                    ". Split the work into smaller batches.");
            log.error(errors.get(0));
            return errors;
        }

        Set<String> references = new HashSet<>();

        for (int i = 0; i < request.getSubRequests().size(); i++) {
            if(!references.add(request.getSubRequests().get(i).getReferenceId())){
                errors.add("Duplicate reference ID found: '" + request.getSubRequests().get(i).getReferenceId()
                        + "'. Each sub-request needs a unique referenceId.");
                log.error(errors.get(errors.size() - 1));
            }
        }

        // Basic validation using javax validation
        Set<ConstraintViolation<CompositeRequest>> violations = validator.validate(request);

        if (!violations.isEmpty()) {
            for (ConstraintViolation<CompositeRequest> violation : violations) {
                errors.add(describe(violation));
            }

            log.error("Invalid request {}", errors);

            // Later checks assume the required fields are present
            return errors;
        }

        List<String> headerErrors = new ArrayList<>();
        for (SubRequestDto subRequest : request.getSubRequests()) {
            if (subRequest.getHeaders() != null
                    && subRequest.getHeaders().values().stream().anyMatch(Objects::isNull)) {
                headerErrors.add("[" + subRequest.getReferenceId() + "] Header values must not be null. Use a string or remove the header.");
            }
        }

        if (!headerErrors.isEmpty()) {
            errors.addAll(headerErrors);
            return errors;
        }

        // Validate dependencies
        errors.addAll(validateDependencies(request.getSubRequests()));

        // Validate request URLs and access
        for (SubRequestDto subRequest : request.getSubRequests()) {
            errors.addAll(validateEndpointAccess(subRequest));
        }

        return errors;
    }

    private List<String> validateDependencies(List<SubRequestDto> requests) {
        // Build dependency graph
        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        List<String> errors = new ArrayList<>();

        for (SubRequestDto request : requests) {
            SubRequest subRequest = new SubRequest(request);
            // Dependencies are extracted from URL, headers, and body
            dependencyGraph.put(request.getReferenceId(), subRequest.getDependencies());
        }

        // Check for missing dependencies
        for (SubRequestDto request : requests) {
            for (String dependency : new SubRequest(request).getDependencies()) {
                if (!dependencyGraph.containsKey(dependency)) {
                    errors.add("Missing dependency reference: '" + dependency +
                                    "' for request: " + request.getReferenceId() +
                                    ". Add a sub-request with that referenceId or fix the placeholder.");
                    log.error(errors.get(errors.size() - 1));
                }
            }
        }

        // Check for circular dependencies
        Set<String> visited = new HashSet<>();
        Set<String> currentPath = new HashSet<>();
        boolean hasCircular = false;

        for (String referenceId : dependencyGraph.keySet()) {
            if (!visited.contains(referenceId) &&
                hasCircularDependency(referenceId, dependencyGraph, visited, currentPath)) {
                    errors.add("Circular dependency involving: " + String.join(", ", new TreeSet<>(currentPath))
                            + ". Remove one of the references so the sub-requests can run in order.");
                    currentPath.clear();
                    log.error(errors.get(errors.size() - 1));
                    hasCircular = true;
            }
        }

        // Validate dependency depth (skip if circular - would cause infinite recursion)
        if (!hasCircular) {
            int currentDepth = calculateMaxDependencyDepth(dependencyGraph);

            if (currentDepth > properties.getMaxDepth()) {
                errors.add("Maximum dependency depth exceeded: the longest chain is " + currentDepth
                        + " but the limit is " + properties.getMaxDepth() + ". Shorten the chain of references.");
                log.error(errors.get(errors.size() - 1));
            }
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

        Set<String> dependencies = dependencyGraph.get(current);
        if (dependencies == null) {
            return false; // Dependency doesn't exist in graph (caught by missing dependency check)
        }

        for (String dependency : dependencies) {
            if (!dependencyGraph.containsKey(dependency)) {
                continue; // Skip missing dependencies (already reported as error)
            }
            if (hasCircularDependency(dependency, dependencyGraph, visited, currentPath)) {
                return true;
            }
        }

        currentPath.remove(current);
        return false;
    }

    private int calculateMaxDependencyDepth(Map<String, Set<String>> dependencyGraph) {
        Map<String, Integer> depth = new HashMap<>();
        Set<String> processing = new HashSet<>();

        for (String referenceId : dependencyGraph.keySet()) {
            calculateDepth(referenceId, dependencyGraph, depth, processing);
        }

        return depth.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    private int calculateDepth(
            String current,
            Map<String, Set<String>> dependencyGraph,
            Map<String, Integer> depth,
            Set<String> processing) {

        if (depth.containsKey(current)) {
            return depth.get(current);
        }

        if (processing.contains(current)) {
            // Cycle detected - shouldn't happen if hasCircular check worked, but safety net
            return 0;
        }

        Set<String> dependencies = dependencyGraph.get(current);
        if (dependencies == null || dependencies.isEmpty()) {
            depth.put(current, 0);
            return 0;
        }

        processing.add(current);

        int maxChildDepth = dependencies.stream()
                .filter(dependencyGraph::containsKey)
                .mapToInt(dep -> calculateDepth(dep, dependencyGraph, depth, processing))
                .max()
                .orElse(-1);

        processing.remove(current);

        int currentDepth = maxChildDepth + 1;
        depth.put(current, currentDepth);
        return currentDepth;
    }

    public List<String> validateEndpointAccess(SubRequestDto request) {
        List<String> errors = new ArrayList<>();
        String refId = request.getReferenceId();

        String methodError = validateHttpMethod(request.getMethod());

        if (methodError != null) {
            errors.add("[" + refId + "] " + methodError);
        }

        String urlError = validateUrlTemplate(request.getUrl());

        // Validate endpoint availability
        Optional<EndpointInfo> endpointInfo = endpointRegistry.getEndpointInformations(request.getMethod(),
                request.getUrl());

        // A malformed URL already has a more specific error
        if (endpointInfo.isEmpty() && urlError == null) {
            String error = "[" + refId + "] Endpoint not available for composite execution: " + request.getMethod()
                    + " " + request.getUrl() + ". Check the method and path, and that the handler has @CompositeEndpoint.";
            errors.add(error);
            log.error(error);
        }

        // Validate request body against method
        for (String bodyError : validateRequestBody(request)) {
            errors.add("[" + refId + "] " + bodyError);
        }

        // Report URL format errors
        if (urlError != null) {
            errors.add("[" + refId + "] " + urlError);
        }

        // Validate client-supplied headers
        if (request.getHeaders() != null) {
            for (String headerName : request.getHeaders().keySet()) {
                if (!SubRequestHeaderPolicy.isClientHeaderAllowed(headerName, properties)) {
                    String error = "[" + refId + "] Header not allowed on a sub-request: " + headerName
                            + ". Remove it. Credentials are forwarded from the outer request.";
                    errors.add(error);
                    log.error(error);
                }
            }
        }

        return errors;
    }

    private String validateUrlTemplate(String url) {
        try {
            SubRequestTarget.parse(url);
            return null;
        } catch (IllegalArgumentException e) {
            log.error("Invalid URL format: {}", url);
            return "Invalid URL format: " + url + ". " + URL_RULES;
        }
    }

    private List<String> validateRequestBody(SubRequestDto request) {
        List<String> errors = new ArrayList<>();

        boolean hasBody = request.getBody() != null
                && !request.getBody().isNull()
                && !request.getBody().isMissingNode();
        boolean requiresBody = Arrays.asList("POST", "PUT", "PATCH")
                .contains(request.getMethod().toUpperCase());
        boolean forbidsBody = Arrays.asList("GET", "DELETE")
                .contains(request.getMethod().toUpperCase());

        if (requiresBody && !hasBody) {
            errors.add("Request body is required for " + request.getMethod() + " method. Add a 'body' field.");
            log.error(errors.get(0));
        }

        if (forbidsBody && hasBody) {
            errors.add("Request body is not allowed for " + request.getMethod() + " method. Remove the 'body' field.");
            log.error(errors.get(0)); // This if and the previous one are mutually exclusive
        }

        return errors;
    }

    private String validateHttpMethod(String method) {
        if (method == null || method.trim().isEmpty()) {
            return "HTTP method is required. Use GET, POST, PUT, PATCH or DELETE.";
        }

        Set<String> validMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
        String normalizedMethod = method.toUpperCase();

        if (!validMethods.contains(normalizedMethod)) {
            return "Invalid HTTP method: " + method + ". Use GET, POST, PUT, PATCH or DELETE.";
        }

         return null;
    }

    public String validateResolvedUrlFormat(String url) {
        if (SubRequestTarget.routablePath(url).isPresent()) {
            return null;
        }

        String error = "Invalid URL format: " + url + ". " + URL_RULES;
        log.error(error);
        return error;
    }

    private static String describe(ConstraintViolation<?> violation) {
        String field = String.valueOf(violation.getPropertyPath()).replace(".bodyValid", ".body");
        return field + ": " + violation.getMessage();
    }
}
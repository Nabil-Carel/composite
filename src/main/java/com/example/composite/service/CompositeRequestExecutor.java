package com.example.composite.service;

import com.example.composite.exception.ReferenceResolutionException;
import com.example.composite.model.context.ExecutionContext;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.request.SubRequestDto;
import com.example.composite.model.request.SubRequestWrapper;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@Component
@RequiredArgsConstructor
@Slf4j
public class CompositeRequestExecutor {

    public SubResponse executeSubRequest(SubRequestDto subRequestDto, HttpServletRequest originalRequest) {
        // SubRequestWrapper wrapper = new SubRequestWrapper(originalRequest, subRequestDto);
        // SubRequest subRequest = new SubRequest(subRequestDto);
        // AtomicReference<Object> result = new AtomicReference<>();

        // executeWithFreshSecurityContext(subRequest, () -> {
        //     executeWithNewRequestScope(wrapper, () -> {
        //         // Execute through Spring's handler
        //         result.set(endpoint.execute(wrapper));
        //     });
        // });

        // return new SubResponse(result.get());
        return null;
    }

    private Object resolveReferences(Object value, Map<String, SubResponse> responseMap) {
        if (value == null)
            return null;

        if (value instanceof JsonNode) {
            return resolveReferences(value.toString(), responseMap);
        }

        if (value instanceof String) {
            String strValue = (String) value;
            Matcher matcher = Pattern.compile("\\$\\{([^}]+)\\}")
                    .matcher(strValue);
            StringBuffer resolved = new StringBuffer();

            while (matcher.find()) {
                String reference = matcher.group(1);
                String[] parts = reference.split("\\.");

                SubResponse referencedResponse = responseMap.get(parts[0]);
                if (referencedResponse == null) {
                    throw new ReferenceResolutionException(
                            "Referenced response not found: " + parts[0], reference);
                }

                Object replacement = referencedResponse;
                for (int i = 1; i < parts.length; i++) {
                    String part = parts[i];

                    if (i == 1 && "body".equals(part)) {
                        replacement = referencedResponse.getBody();
                        continue;
                    }

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

        Matcher arrayMatcher = Pattern.compile("(\\w+)\\[(\\d+)]").matcher(key);
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

    public CompositeResponse buildResponse(Map<String, SubResponse> responses) {
        boolean hasErrors = responses.values().stream()
                .anyMatch(r -> !HttpStatus.valueOf(r.getHttpStatus()).is2xxSuccessful());

        return CompositeResponse.builder()
                .responses(responses)
                .hasErrors(hasErrors)
                .build();
    }

    private void executeWithFreshSecurityContext(SubRequest request, Runnable execution) {
        SecurityContext originalContext = SecurityContextHolder.getContext();
        try {
            SecurityContext newContext = SecurityContextHolder.createEmptyContext();
            newContext.setAuthentication(originalContext.getAuthentication());
            SecurityContextHolder.setContext(newContext);

            execution.run();
        } finally {
            SecurityContextHolder.setContext(originalContext);
        }
    }

    private void executeWithNewRequestScope(SubRequestWrapper request, Runnable execution) {
        RequestAttributes originalAttributes = RequestContextHolder.getRequestAttributes();
        try {
            ServletRequestAttributes newAttributes = new ServletRequestAttributes(request);
            RequestContextHolder.setRequestAttributes(newAttributes, true);

            execution.run();
        } finally {
            RequestContextHolder.setRequestAttributes(originalAttributes, true);
        }
    }
}
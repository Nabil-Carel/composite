package com.example.composite.config.interceptor;

import com.example.composite.model.ResponseTracker;
import com.example.composite.model.response.SubResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.catalina.connector.ResponseFacade;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import com.example.composite.model.response.SubResponseWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
public class ResponseInterceptor  implements HandlerInterceptor{
    @Qualifier("compositeObjectMapper")
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        return true;
    }

    @Override
    public void postHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                           @NonNull Object handler, @Nullable ModelAndView modelAndView) throws JsonProcessingException {
        // This only handles successful responses (2xx)
        processResponse(request, response);
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, @Nullable Exception ex) throws JsonProcessingException {
        // This handles ALL responses, including errors (4xx, 5xx)
        // But avoid double processing successful responses
        if (response.getStatus() >= 400) {
            processResponse(request, response);
        }
    }

    private void processResponse(HttpServletRequest request, HttpServletResponse response) throws JsonProcessingException {
        Boolean isComposite = (Boolean) request.getAttribute("composite");

        if((isComposite == null || !isComposite || request.getRequestURI().equals("/api/composite/execute"))
                && !(response instanceof SubResponseWrapper)
                && !(request.getRequestURI().equals("/error"))) {
            return;
        }

        String requestId = (String) request.getAttribute("requestId");

        // Add null check for responseStore.get(requestId)
        ResponseTracker tracker = responseStore.get(requestId);
        if (tracker == null) {
            // Handle case where tracker doesn't exist
            return;
        }

        SubResponseWrapper subResponseWrapper = (SubResponseWrapper) response;
        Class<?> returnClass = subResponseWrapper.getResponseType();
        Map<String, String> headers = subResponseWrapper.getHeadersAsMap();

        String responseBody = subResponseWrapper.getCapturedResponseBody();
        Object body = String.class.equals(returnClass) ?
                responseBody :
                objectMapper.readValue(responseBody, returnClass);

        SubResponse subResponse = SubResponse.builder()
                .headers(headers)
                .httpStatus(response.getStatus())
                .body(body)
                .referenceId(subResponseWrapper.getReference())
                .build();

        tracker.addResponse(subResponseWrapper.getReference(), subResponse);
        response.reset();
    }
}


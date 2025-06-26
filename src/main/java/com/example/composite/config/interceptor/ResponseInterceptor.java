package com.example.composite.config.interceptor;

import com.example.composite.model.ResponseTracker;
import com.example.composite.model.response.SubResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ResponseTracker> responseStore;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        return true;
    }

    @Override
    public void postHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                           @NonNull Object handler, @Nullable ModelAndView modelAndView) throws JsonProcessingException {
        Boolean isComposite = (Boolean) request.getAttribute("composite");

        if(isComposite != null && isComposite){
           String requestId = (String) request.getAttribute("requestId");
           SubResponseWrapper subResponseWrapper = (SubResponseWrapper) response;
           Class<?> returnClass = subResponseWrapper.getResponseType();
           Map<String, String> headers = subResponseWrapper.getHeadersAsMap();

           //SubResponseWrapper subResponseWrapper = new SubResponseWrapper(response, Object.class, objectMapper);
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

           responseStore.get(requestId).addResponse(subResponseWrapper.getReference(), subResponse);
       }

    }
}


package com.example.composite.service;

import com.example.composite.model.request.SubRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;

public interface CompositeRequestService {
    void forwardSubrequest(
            SubRequest subRequest,
            HttpServletRequest originalRequest,
            HttpServletResponse originalResponse,
            SecurityContext securityContext,
            String requestId
    );
}

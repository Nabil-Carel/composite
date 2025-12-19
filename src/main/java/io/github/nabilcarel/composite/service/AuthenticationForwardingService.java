package io.github.nabilcarel.composite.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;


public interface AuthenticationForwardingService {
    public void forwardAuthentication(HttpServletRequest originalRequest, HttpHeaders targetHeaders);
}

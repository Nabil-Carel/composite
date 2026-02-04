package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.CompositeProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthenticationForwardingServiceImpl implements AuthenticationForwardingService {
    private final CompositeProperties properties;

    public void forwardAuthentication(HttpServletRequest originalRequest, HttpHeaders targetHeaders) {
        for (String headerName : properties.getSecurity().getForwardedHeaders()) {
            String value = originalRequest.getHeader(headerName);
            if (value != null) {
                targetHeaders.add(headerName, value);
            }
        }
    }
}

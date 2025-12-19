package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.config.CompositeProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class AuthenticationForwardingServiceImpl implements AuthenticationForwardingService{
    private final CompositeProperties properties;

    public void forwardAuthentication(HttpServletRequest originalRequest, HttpHeaders targetHeaders) {
        // Forward Authorization header if present
        String authHeader = originalRequest.getHeader("Authorization");
        if (authHeader != null) {
            targetHeaders.add("Authorization", authHeader);
        }

        // Forward cookies if present
        Cookie[] cookies = originalRequest.getCookies();
        if (cookies != null && cookies.length > 0) {
            String cookieHeader = Arrays.stream(cookies)
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));
            targetHeaders.add("Cookie", cookieHeader);
        }

        // Forward common auth headers if present
        forwardIfPresent(originalRequest, targetHeaders, "X-API-Key");
        forwardIfPresent(originalRequest, targetHeaders, "X-Auth-Token");

        // Forward user-configured additional headers
        properties.getSecurity().getAdditionalAuthHeaders().forEach(headerName ->
                forwardIfPresent(originalRequest, targetHeaders, headerName)
        );
    }

    private void forwardIfPresent(HttpServletRequest request, HttpHeaders target, String headerName) {
        String value = request.getHeader(headerName);
        if (value != null) {
            target.add(headerName, value);
        }
    }
}

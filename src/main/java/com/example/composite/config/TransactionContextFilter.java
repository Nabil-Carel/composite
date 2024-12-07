package com.example.composite.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

// Filter to restore transaction context from header
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class TransactionContextFilter extends OncePerRequestFilter {
    private final CompositeTransactionManager transactionManager;
    
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws IOException, ServletException {
        
        String transactionId = request.getHeader("X-Transaction-Id");
        if (transactionId != null) {
            try {
                transactionManager.resumeTransaction(transactionId);
                filterChain.doFilter(request, response);
            } finally {
                transactionManager.cleanupTransaction();
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }
}

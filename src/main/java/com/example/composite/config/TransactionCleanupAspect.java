package com.example.composite.config;

import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

// Aspect to handle transaction cleanup
@Aspect
@Component
@RequiredArgsConstructor
class TransactionCleanupAspect {
    private final CompositeTransactionManager transactionManager;
    
    @After("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void cleanup() {
        transactionManager.cleanupTransaction();
    }
}

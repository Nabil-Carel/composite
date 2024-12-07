package com.example.composite.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;



// Transaction manager to handle transaction propagation
@Component
class CompositeTransactionManager {
    private final PlatformTransactionManager platformTransactionManager;
    private final ThreadLocal<String> currentTransactionId = new ThreadLocal<>();
    private final Map<String, TransactionStatus> suspendedTransactions = new ConcurrentHashMap<>();

    public CompositeTransactionManager() {
        this(new JpaTransactionManager());
    }

    @Autowired
    public CompositeTransactionManager(PlatformTransactionManager platformTransactionManager) {
        this.platformTransactionManager = platformTransactionManager;
    }

    public String getCurrentTransactionId() {
        return currentTransactionId.get();
    }

    public void resumeTransaction(String transactionId) {
        TransactionStatus status = suspendedTransactions.get(transactionId);
        if (status != null) {
            platformTransactionManager.getTransaction(new DefaultTransactionDefinition());
            currentTransactionId.set(transactionId);
        }
    }

    public void suspendTransaction() {
        String txId = currentTransactionId.get();
        if (txId != null) {
            TransactionStatus status = platformTransactionManager.getTransaction(new DefaultTransactionDefinition());
            suspendedTransactions.put(txId, status);
            currentTransactionId.remove();
        }
    }

    public void cleanupTransaction() {
        String txId = currentTransactionId.get();
        if (txId != null) {
            suspendedTransactions.remove(txId);
            currentTransactionId.remove();
        }
    }
}

// For custom TransactionManager Definition
/*
 *   @Bean
    public PlatformTransactionManager customTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public CompositeTransactionManager transactionManager(PlatformTransactionManager customTransactionManager) {
        return new CompositeTransactionManager(customTransactionManager);
    }
 */

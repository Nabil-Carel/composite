package com.example.composite.config;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.lang.NonNull;

// Interceptor to add transaction header to outgoing requests
@Component
class TransactionPropagationInterceptor implements ClientHttpRequestInterceptor {
    private static final String TRANSACTION_HEADER = "X-Transaction-Id";
    
    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution) throws IOException {

        String txId = TransactionSynchronizationManager.getCurrentTransactionName();
        
        if(txId != null) {
            request.getHeaders().add(TRANSACTION_HEADER, txId);
        }
            
        return execution.execute(request, body);
    }
}

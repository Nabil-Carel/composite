package com.example.composite.model;

import com.example.composite.datastructure.ObservableConcurrentMap;
import com.example.composite.datastructure.ObservableMap;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.model.response.SubResponse;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ResponseTracker class manages the tracking of sub-responses in a composite operation.
 * It maintains an ObservableInt to count outstanding responses and an ObservableMap to associate response IDs with SubResponse objects.
 * The class provides methods to add new responses, decrement the response count, and perform cleanup.
 * Designed for concurrent and event-driven scenarios, it enables observers to react to changes in response state.
 */
public class ResponseTracker {
    @Getter
    private ObservableMap<String, SubResponse> subResponseMap = new ObservableConcurrentMap<>();
    @Getter
    private final CompletableFuture<CompositeResponse> future = new CompletableFuture<>();
    private final AtomicInteger remainingResponses;


    public ResponseTracker(int value) {
        remainingResponses = new AtomicInteger(value);
    }

    public void addResponse(String id, SubResponse subResponse) {
        subResponseMap.put(id, subResponse);
        int remaining = remainingResponses.decrementAndGet();

        if (remaining == 0) {
            completeResponse();
        }
    }

    private void completeResponse() {
        future.complete(CompositeResponse.builder()
                .responses(subResponseMap)
                .build());
    }

    public void cancel(Throwable t) {
        future.completeExceptionally(t);
    }
}

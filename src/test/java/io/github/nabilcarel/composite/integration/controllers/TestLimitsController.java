package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Records how many handlers run at the same time. */
@RestController
@RequestMapping("/api/limits")
public class TestLimitsController {

    public static final AtomicInteger MAX_ACTIVE = new AtomicInteger();
    public static final AtomicInteger COMPLETED = new AtomicInteger();
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    public static void reset() {
        ACTIVE.set(0);
        MAX_ACTIVE.set(0);
        COMPLETED.set(0);
    }

    @CompositeEndpoint
    @GetMapping("/work/{id}")
    public Map<String, Object> work(@PathVariable("id") String id) throws InterruptedException {
        int active = ACTIVE.incrementAndGet();
        MAX_ACTIVE.accumulateAndGet(active, Math::max);
        try {
            Thread.sleep(150);
        } finally {
            ACTIVE.decrementAndGet();
        }
        COMPLETED.incrementAndGet();
        return Map.of("id", id);
    }

    @CompositeEndpoint
    @GetMapping("/async-ok")
    public CompletableFuture<Map<String, Object>> asyncOk() {
        return CompletableFuture.supplyAsync(() -> Map.of("ok", true), CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS));
    }

    @CompositeEndpoint
    @GetMapping("/async-fail")
    public CompletableFuture<Map<String, Object>> asyncFail() {
        return CompletableFuture.supplyAsync(() -> {
            throw new IllegalStateException("async handler failed");
        }, CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS));
    }

    @CompositeEndpoint
    @GetMapping("/throw")
    public Map<String, Object> fail() {
        throw new IllegalStateException("handler failed");
    }
}

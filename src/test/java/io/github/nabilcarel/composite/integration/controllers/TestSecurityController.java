package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints used by the security regression tests. Handlers without
 * {@link CompositeEndpoint} must never be reachable through a composite request.
 */
@RestController
@RequestMapping("/api/sec")
public class TestSecurityController {

    public static final AtomicInteger HIDDEN_CALLS = new AtomicInteger();
    public static volatile String lastId;
    public static volatile String lastQuery;
    public static volatile Map<String, Object> lastEcho;
    public static volatile Map<String, String> lastHeaders;

    public static void reset() {
        HIDDEN_CALLS.set(0);
        lastId = null;
        lastQuery = null;
        lastEcho = null;
        lastHeaders = null;
    }

    @CompositeEndpoint(Map.class)
    @GetMapping("/items/{id}/details")
    public Map<String, Object> details(@PathVariable("id") String id) {
        lastId = id;
        return Map.of("handler", "details");
    }

    @GetMapping("/items/{id}")
    public Map<String, Object> hiddenItem(@PathVariable("id") String id) {
        HIDDEN_CALLS.incrementAndGet();
        return Map.of("handler", "NOT_ALLOWLISTED");
    }

    @CompositeEndpoint(Map.class)
    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable("id") String id, HttpServletRequest request) {
        lastId = id;
        lastQuery = request.getQueryString();
        Map<String, Object> user = new HashMap<>();
        user.put("id", id);
        user.put("age", 30);
        user.put("evil", "1?role=admin");
        user.put("me", "me");
        user.put("dots", "..");
        user.put("semicolon", "me;x=1");
        user.put("slash", "a/b");
        return user;
    }

    @GetMapping("/users/me")
    public Map<String, Object> hiddenCurrentUser() {
        HIDDEN_CALLS.incrementAndGet();
        return Map.of("handler", "NOT_ALLOWLISTED");
    }

    @CompositeEndpoint(Map.class)
    @GetMapping("/secret")
    public Map<String, Object> secret() {
        return Map.of("token", "TOP-SECRET");
    }

    @CompositeEndpoint(Map.class)
    @GetMapping("/stored")
    public Map<String, Object> stored() {
        return Map.of("template", "${secret.token}", "bare", "secret.token");
    }

    @CompositeEndpoint(Map.class)
    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
        lastEcho = body;
        return body;
    }

    @CompositeEndpoint(Map.class)
    @GetMapping("/headers")
    public Map<String, String> headers(@RequestHeader Map<String, String> headers) {
        lastHeaders = headers;
        return Map.of("ok", "true");
    }
}

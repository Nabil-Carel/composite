package io.github.nabilcarel.composite.integration.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Endpoints used to attack the composite pipeline. Unannotated handlers must stay unreachable. */
@RestController
@RequestMapping("/api/adv")
public class TestAdversarialController {

    public static final AtomicInteger HIDDEN_CALLS = new AtomicInteger();
    public static final AtomicInteger WRITES = new AtomicInteger();
    public static final AtomicInteger ACTIVE = new AtomicInteger();
    public static final AtomicInteger MAX_ACTIVE = new AtomicInteger();
    public static volatile HttpHeaders lastHeaders;
    public static volatile String lastId;
    public static volatile String lastWrite;
    public static volatile String redirectTarget;

    public static void reset() {
        HIDDEN_CALLS.set(0);
        WRITES.set(0);
        ACTIVE.set(0);
        MAX_ACTIVE.set(0);
        lastHeaders = null;
        lastId = null;
        lastWrite = null;
    }

    @CompositeEndpoint
    @GetMapping("/headers")
    public Map<String, Object> headers(@RequestHeader HttpHeaders headers) {
        lastHeaders = headers;
        return Map.of("ok", true);
    }

    @CompositeEndpoint
    @GetMapping("/values")
    public Map<String, Object> values() {
        Map<String, Object> values = new HashMap<>();
        values.put("crlf", "safe\r\nX-Injected: yes");
        values.put("lf", "safe\nX-Injected: yes");
        values.put("hidden", "hidden");
        values.put("dots", "..");
        values.put("encodedDots", "%2e%2e");
        values.put("doubleEncoded", "%252e%252e");
        values.put("encodedSlash", "..%2fhidden");
        values.put("backslash", "..\\hidden");
        values.put("fullwidth", "．．");
        values.put("overlong", "%c0%ae%c0%ae");
        values.put("nul", "a\u0000b");
        values.put("template", "${values.hidden}");
        values.put("spel", "#{T(java.lang.Runtime).getRuntime()}");
        values.put("sql", "1' OR '1'='1");
        values.put("html", "<script>alert(1)</script>");
        values.put("query", "1&admin=true#x");
        return values;
    }

    @CompositeEndpoint
    @GetMapping("/items/{id}")
    public Map<String, Object> item(@PathVariable("id") String id) {
        lastId = id;
        return Map.of("id", id);
    }

    @GetMapping("/items/hidden")
    public Map<String, Object> hiddenItem() {
        HIDDEN_CALLS.incrementAndGet();
        return Map.of("handler", "NOT_ALLOWLISTED");
    }

    @GetMapping("/hidden")
    public Map<String, Object> hidden() {
        HIDDEN_CALLS.incrementAndGet();
        return Map.of("handler", "NOT_ALLOWLISTED");
    }

    @CompositeEndpoint
    @GetMapping("/redirect")
    public ResponseEntity<Void> redirect() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectTarget)).build();
    }

    @CompositeEndpoint
    @PostMapping("/write")
    public Map<String, Object> write(@RequestBody JsonNode body) {
        WRITES.incrementAndGet();
        lastWrite = body.toString();
        return Map.of("ok", true);
    }

    @CompositeEndpoint
    @GetMapping("/large")
    public Map<String, Object> large() {
        return Map.of("value", "x".repeat(200 * 1024));
    }

    @CompositeEndpoint
    @GetMapping("/slow/{millis}")
    public Map<String, Object> slow(@PathVariable("millis") long millis) throws InterruptedException {
        MAX_ACTIVE.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
        try {
            Thread.sleep(millis);
        } finally {
            ACTIVE.decrementAndGet();
        }
        return Map.of("id", "slow");
    }

    @GetMapping("/fast")
    public Map<String, Object> fast() {
        return Map.of("ok", true);
    }
}

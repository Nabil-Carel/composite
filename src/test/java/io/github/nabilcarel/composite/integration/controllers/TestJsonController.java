package io.github.nabilcarel.composite.integration.controllers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Endpoints whose JSON representation differs from their Java representation. */
@RestController
@RequestMapping("/api/json")
public class TestJsonController {

    public static volatile String lastBody;

    public static class Account {
        @JsonProperty("user_id")
        private final String userId = "u1";

        @JsonIgnore
        private final String secret = "HIDDEN";

        public String getUserId() {
            return userId;
        }

        public String getSecret() {
            return secret;
        }
    }

    @CompositeEndpoint
    @GetMapping("/account")
    public Account account() {
        return new Account();
    }

    @CompositeEndpoint
    @GetMapping("/mismatch")
    public Map<String, Object> mismatch() {
        return Map.of("user_id", Map.of("x", 1));
    }

    @CompositeEndpoint
    @GetMapping("/invalid")
    public ResponseEntity<String> invalid() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{not json");
    }

    @CompositeEndpoint
    @GetMapping(value = "/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public String text() {
        return "plain value";
    }

    @CompositeEndpoint
    @GetMapping("/numbers")
    public ResponseEntity<String> numbers() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body("{\"big\":12345678901234567890,\"price\":19.10,\"small\":1E+3}");
    }

    @CompositeEndpoint
    @GetMapping("/nested")
    public Map<String, Object> nested() {
        return Map.of(
                "items", List.of(Map.of("name", "first")),
                "db.host", "localhost",
                "object", Map.of("a", 1));
    }

    @CompositeEndpoint
    @GetMapping("/empty")
    public ResponseEntity<Void> empty() {
        return ResponseEntity.noContent().build();
    }

    @CompositeEndpoint
    @GetMapping("/large")
    public Map<String, Object> large() {
        return Map.of("value", "x".repeat(300 * 1024));
    }

    @CompositeEndpoint
    @PostMapping("/capture")
    public Map<String, Object> capture(@RequestBody String body) {
        lastBody = body;
        return Map.of("ok", true);
    }
}

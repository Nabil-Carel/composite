package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/headers")
public class TestHeaderController {

    @GetMapping("/echo")
    @CompositeEndpoint(java.util.Map.class)
    public Map<String, String> echoHeaders(@RequestHeader Map<String, String> headers) {
        Map<String, String> result = new HashMap<>();
        headers.forEach((key, value) -> {
            // Filter out standard Spring headers
            if (!key.startsWith("host") && 
                !key.startsWith("content-length") && 
                !key.startsWith("accept")) {
                result.put(key.toLowerCase(), value);
            }
        });
        return result;
    }

    @GetMapping("/auth")
    @CompositeEndpoint(Map.class)
    public Map<String, String> checkAuth(@RequestHeader(value = "Authorization", required = false) String auth) {
        Map<String, String> result = new HashMap<>();
        result.put("hasAuth", auth != null ? "true" : "false");
        if (auth != null) {
            result.put("authHeader", auth);
        }
        return result;
    }

    @GetMapping("/custom")
    @CompositeEndpoint(java.util.Map.class)
    public Map<String, String> checkCustomHeaders(
            @RequestHeader(value = "X-Custom-Header", required = false) String customHeader,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        Map<String, String> result = new HashMap<>();
        if (customHeader != null) {
            result.put("customHeader", customHeader);
        }
        if (apiKey != null) {
            result.put("apiKey", apiKey);
        }
        return result;
    }
}

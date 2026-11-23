package io.github.nabilcarel.composite.secured;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.parameters.P;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Method-secured endpoints. Counters prove whether a handler body actually ran. */
@RestController
@RequestMapping("/secure")
public class SecuredController {

    static final AtomicInteger ADMIN_CALLS = new AtomicInteger();
    static final AtomicInteger HIDDEN_CALLS = new AtomicInteger();
    static final AtomicInteger WRITES = new AtomicInteger();

    static void reset() {
        ADMIN_CALLS.set(0);
        HIDDEN_CALLS.set(0);
        WRITES.set(0);
    }

    @CompositeEndpoint
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of("name", authentication.getName());
    }

    @CompositeEndpoint
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> admin() {
        ADMIN_CALLS.incrementAndGet();
        return Map.of("id", "admin-data");
    }

    @CompositeEndpoint
    @GetMapping("/owner/{name}")
    @PreAuthorize("#name == authentication.name")
    public Map<String, Object> owner(@P("name") @PathVariable("name") String name) {
        return Map.of("owner", name);
    }

    @CompositeEndpoint
    @PostMapping("/write")
    @PreAuthorize("hasRole('USER')")
    public Map<String, Object> write(@RequestBody Map<String, Object> body) {
        WRITES.incrementAndGet();
        return body;
    }

    @GetMapping("/hidden")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> hidden() {
        HIDDEN_CALLS.incrementAndGet();
        return Map.of("handler", "NOT_ALLOWLISTED");
    }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }
}

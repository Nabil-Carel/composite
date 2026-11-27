package io.github.nabilcarel.composite.sample;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.parameters.P;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Endpoints covering each Spring Security authorization style. */
@RestController
@RequestMapping("/api")
public class SampleController {

    /** Records handler bodies that actually ran, as {@code handler:user}. */
    static final List<String> EXECUTED = new CopyOnWriteArrayList<>();

    private static final Map<Long, Order> ORDERS = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(100);

    public record Order(long id, String owner, String item) {
        public String getOwner() {
            return owner;
        }
    }

    public record NewOrder(String item, String owner) {
    }

    static void reset() {
        EXECUTED.clear();
        ORDERS.clear();
        ORDERS.put(1L, new Order(1, "alice", "alice-order"));
        ORDERS.put(2L, new Order(2, "bob", "bob-order"));
    }

    static boolean ran(String handler) {
        return EXECUTED.stream().anyMatch(entry -> entry.startsWith(handler + ":"));
    }

    static boolean orderExists(long id) {
        return ORDERS.containsKey(id);
    }

    private static void record(String handler, Authentication authentication) {
        EXECUTED.add(handler + ":" + (authentication == null ? "anonymous" : authentication.getName()));
    }

    @CompositeEndpoint
    @GetMapping("/public/status")
    public Map<String, Object> status() {
        EXECUTED.add("status:public");
        return Map.of("status", "up");
    }

    @CompositeEndpoint
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        record("me", authentication);
        return Map.of("name", authentication.getName());
    }

    @CompositeEndpoint
    @GetMapping("/accounts/{owner}")
    @PreAuthorize("#owner == authentication.name or hasRole('ADMIN')")
    public Map<String, Object> account(@P("owner") @PathVariable("owner") String owner, Authentication authentication) {
        record("account", authentication);
        return Map.of("owner", owner, "balance", 100);
    }

    @CompositeEndpoint
    @PostMapping("/orders")
    @PreAuthorize("hasRole('USER')")
    public Order createOrder(@RequestBody NewOrder request, Authentication authentication) {
        record("createOrder", authentication);
        Order order = new Order(IDS.incrementAndGet(), authentication.getName(), request.item());
        ORDERS.put(order.id(), order);
        return order;
    }

    @CompositeEndpoint
    @GetMapping("/orders/{id}")
    @PostAuthorize("returnObject.owner == authentication.name or hasRole('ADMIN')")
    public Order order(@PathVariable("id") long id) {
        Order order = ORDERS.get(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return order;
    }

    @CompositeEndpoint
    @GetMapping("/orders")
    @PostFilter("filterObject.owner == authentication.name or hasRole('ADMIN')")
    public List<Order> orders() {
        return new ArrayList<>(ORDERS.values());
    }

    @CompositeEndpoint
    @DeleteMapping("/orders/{id}")
    @Secured("ROLE_ADMIN")
    public Map<String, Object> deleteOrder(@PathVariable("id") long id, Authentication authentication) {
        record("deleteOrder", authentication);
        ORDERS.remove(id);
        return Map.of("deleted", id);
    }

    @CompositeEndpoint
    @GetMapping("/audit")
    @RolesAllowed("ADMIN")
    public Map<String, Object> audit(Authentication authentication) {
        record("audit", authentication);
        return Map.of("entries", 0);
    }

    @CompositeEndpoint
    @GetMapping("/admin/report")
    public Map<String, Object> adminReport(Authentication authentication) {
        record("adminReport", authentication);
        return Map.of("report", "confidential");
    }

    @CompositeEndpoint
    @GetMapping("/scoped")
    @PreAuthorize("hasAuthority('SCOPE_orders.read')")
    public Map<String, Object> scoped(Authentication authentication) {
        record("scoped", authentication);
        return Map.of("scoped", true);
    }

    @GetMapping("/internal/metrics")
    public Map<String, Object> internalMetrics(Authentication authentication) {
        record("internalMetrics", authentication);
        return Map.of("secret", "not-allowlisted");
    }

    @CompositeEndpoint
    @GetMapping("/slow/{millis}")
    public Map<String, Object> slow(@PathVariable("millis") long millis, Authentication authentication)
            throws InterruptedException {
        Thread.sleep(millis);
        record("slow", authentication);
        return Map.of("id", "slow");
    }

    @CompositeEndpoint
    @GetMapping("/set-cookie")
    public Map<String, Object> setCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie("SUB_REQUEST_COOKIE", "must-not-reach-client");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
        response.setHeader("X-Sub-Request-Secret", "must-not-reach-client");
        return Map.of("ok", true);
    }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }
}

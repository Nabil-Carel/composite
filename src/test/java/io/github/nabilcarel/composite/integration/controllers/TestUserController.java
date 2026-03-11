package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class TestUserController {

    @GetMapping("/{id}")
    @CompositeEndpoint(User.class)
    public User getUser(@PathVariable("id") String id) {
        return new User(id, "User " + id, "user" + id + "@example.com");
    }

    @PostMapping
    @CompositeEndpoint(User.class)
    public User createUser(@RequestBody CreateUserRequest request) {
        return new User(request.id(), request.name(), request.email());
    }

    @GetMapping("/{id}/profile")
    @CompositeEndpoint(UserProfile.class)
    public UserProfile getUserProfile(@PathVariable("id") String id) {
        return new UserProfile(id, "Profile for user " + id, "Bio for user " + id);
    }

    @GetMapping("/{id}/orders")
    @CompositeEndpoint(OrderList.class)
    public OrderList getUserOrders(@PathVariable("id") String id) {
        return new OrderList(List.of(
            new Order("order1", id, 100.0),
            new Order("order2", id, 200.0)
        ));
    }

    @GetMapping("/{id}/settings")
    @CompositeEndpoint(java.util.Map.class)
    public Map<String, Object> getUserSettings(@PathVariable("id") String id) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("theme", "dark");
        settings.put("notifications", true);
        settings.put("language", "en");
        return settings;
    }

    @GetMapping("/{id}/nested")
    @CompositeEndpoint(NestedData.class)
    public NestedData getNestedData(@PathVariable("id") String id) {
        return new NestedData(
            new NestedData.InnerData(
                new NestedData.InnerData.DeepData("deep-value-" + id)
            )
        );
    }

    @GetMapping("/{id}/metadata")
    @CompositeEndpoint(java.util.Map.class)
    public Map<String, Object> getUserMetadata(@PathVariable("id") String id) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("category", "premium");
        metadata.put("created.at", "2024-01-01");
        metadata.put("tags", List.of("vip", "early-adopter"));
        return metadata;
    }

    // DTOs
    public record User(String id, String name, String email) {}
    public record CreateUserRequest(String id, String name, String email) {}
    public record UserProfile(String userId, String displayName, String bio) {}
    public record Order(String orderId, String userId, Double amount) {}
    public record OrderList(List<Order> orders) {}
    public record NestedData(InnerData inner) {
        public record InnerData(DeepData deep) {
            public record DeepData(String value) {}
        }
    }
}

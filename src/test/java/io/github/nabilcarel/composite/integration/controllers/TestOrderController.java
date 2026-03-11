package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class TestOrderController {

    @GetMapping("/{orderId}")
    @CompositeEndpoint(Order.class)
    public Order getOrder(@PathVariable("orderId") String orderId) {
        return new Order(orderId, "user123", 150.0, "pending");
    }

    @PostMapping
    @CompositeEndpoint(Order.class)
    public Order createOrder(@RequestBody CreateOrderRequest request) {
        return new Order(
            "order-" + System.currentTimeMillis(),
            request.userId(),
            request.amount(),
            "created"
        );
    }

    @GetMapping("/{orderId}/items")
    @CompositeEndpoint(OrderItemList.class)
    public OrderItemList getOrderItems(@PathVariable("orderId") String orderId) {
        return new OrderItemList(List.of(
            new OrderItem("item1", orderId, "Product A", 50.0),
            new OrderItem("item2", orderId, "Product B", 100.0)
        ));
    }

    @GetMapping("/{orderId}/status")
    @CompositeEndpoint(java.util.Map.class)
    public Map<String, String> getOrderStatus(@PathVariable("orderId") String orderId) {
        return Map.of("orderId", orderId, "status", "shipped");
    }

    @GetMapping("/error/{orderId}")
    @CompositeEndpoint(Void.class)
    public void getOrderError(@PathVariable("orderId") String orderId) {
        throw new RuntimeException("Order not found: " + orderId);
    }

    @GetMapping("/notfound/{orderId}")
    @CompositeEndpoint(Order.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Order getOrderNotFound(@PathVariable("orderId") String orderId) {
        return null; // Will return 404
    }

    @DeleteMapping("/{orderId}")
    @CompositeEndpoint(Void.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrder(@PathVariable("orderId") String orderId) {
        // Successful void response - returns 204 No Content
    }

    @GetMapping("/{orderId}/empty")
    @CompositeEndpoint(Order.class)
    public Order getEmptyOrder(@PathVariable("orderId") String orderId) {
        return null; // Returns 200 with null/empty body
    }

    @GetMapping("/slow/{orderId}")
    @CompositeEndpoint(Order.class)
    public Order getSlowOrder(@PathVariable("orderId") String orderId) throws InterruptedException {
        Thread.sleep(5000); // Simulate slow response
        return new Order(orderId, "user123", 150.0, "pending");
    }

    // DTOs
    public record Order(String orderId, String userId, Double amount, String status) {}
    public record CreateOrderRequest(String userId, Double amount) {}
    public record OrderItem(String itemId, String orderId, String productName, Double price) {}
    public record OrderItemList(List<OrderItem> items) {}
}

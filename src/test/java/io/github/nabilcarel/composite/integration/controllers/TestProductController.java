package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class TestProductController {

    @GetMapping
    @CompositeEndpoint(ProductList.class)
    public ProductList getAllProducts() {
        return new ProductList(List.of(
            new Product("prod1", "Product 1", 10.0),
            new Product("prod2", "Product 2", 20.0),
            new Product("prod3", "Product 3", 30.0)
        ));
    }

    @GetMapping("/{productId}")
    @CompositeEndpoint(Product.class)
    public Product getProduct(@PathVariable("productId") String productId) {
        return new Product(productId, "Product " + productId, 25.0);
    }

    @GetMapping("/{productId}/reviews")
    @CompositeEndpoint(ReviewList.class)
    public ReviewList getProductReviews(@PathVariable("productId") String productId) {
        return new ReviewList(List.of(
            new Review("review1", productId, 5, "Great product!"),
            new Review("review2", productId, 4, "Good quality")
        ));
    }

    @GetMapping("/{productId}/metadata")
    @CompositeEndpoint(Map.class)
    public Map<String, Object> getProductMetadata(@PathVariable("productId") String productId) {
        return Map.of(
            "productId", productId,
            "category", "electronics",
            "inStock", true,
            "tags", List.of("popular", "featured")
        );
    }

    // DTOs
    public record Product(String productId, String name, Double price) {}
    public record ProductList(List<Product> products) {}
    public record Review(String reviewId, String productId, Integer rating, String comment) {}
    public record ReviewList(List<Review> reviews) {}
}

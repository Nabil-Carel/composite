package com.example.composite.controller;

import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.composite.config.EndpointRegistry;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.response.CompositeResponse;
import com.example.composite.service.CompositeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/composite")
@RequiredArgsConstructor
public class CompositeController {
     private final CompositeService compositeService;
    private final EndpointRegistry endpointRegistry;
    
    @PostMapping("/execute")
    public ResponseEntity<CompositeResponse> execute(
            @Valid @RequestBody CompositeRequest request) {
        return ResponseEntity.ok(compositeService.processRequests(request));
    }
    
    @GetMapping("/endpoints")
    public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
        return endpointRegistry.getAvailableEndpoints();
    }
}

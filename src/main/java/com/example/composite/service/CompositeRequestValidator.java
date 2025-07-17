package com.example.composite.service;

import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.request.SubRequestDto;

import java.util.List;
import java.util.Set;

public interface CompositeRequestValidator {
    List<String> validateRequest(CompositeRequest request);
    List<String> validateReferences(SubRequest request, Set<String> availableRefs);
    List<String> validateEndpointAccess(SubRequestDto request);
    String validateResolvedUrlFormat(String url);
}

package com.example.composite.model.response;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class CompositeResponse {
    private Map<String, SubResponse> responses;
    private boolean hasErrors;
    private String errorMessage;
}

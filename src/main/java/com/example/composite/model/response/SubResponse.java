package com.example.composite.model.response;

import java.util.Map;

import org.springframework.http.HttpStatus;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class SubResponse {
    private String referenceId;
    private HttpStatus httpStatus;
    private Object body;
    private Map<String, String> headers;
}

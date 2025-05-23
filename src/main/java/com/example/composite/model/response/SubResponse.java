package com.example.composite.model.response;

import lombok.*;

import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubResponse {
    private String referenceId;
    private int httpStatus;
    private Object body;
    private Map<String, String> headers;
}

package com.example.composite.model.context;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.composite.model.response.SubResponse;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExecutionContext {
    private final String requestId = UUID.randomUUID().toString();
    private final long startTime = System.currentTimeMillis();
    private final Map<String, SubResponse> responseMap = new ConcurrentHashMap<>();
}

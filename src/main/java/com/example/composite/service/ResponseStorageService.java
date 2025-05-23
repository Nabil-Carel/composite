package com.example.composite.service;

import java.util.concurrent.ConcurrentHashMap;

import com.example.composite.model.ResponseTracker;
import lombok.Getter;
import org.springframework.stereotype.Service;

@Service
@Getter
public class ResponseStorageService {
    private final ConcurrentHashMap<String, ResponseTracker> responseMap = new ConcurrentHashMap<>();
    
}

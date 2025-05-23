package com.example.composite.model;

import com.example.composite.datastructure.ObservableInt;
import com.example.composite.model.response.SubResponse;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ResponseTracker {
    @Getter
    private ObservableInt responseCount;
    @Getter
    private Map<String, SubResponse> subResponseMap = new ConcurrentHashMap<>();

    public ResponseTracker(int value) {
        responseCount = new ObservableInt(value);
    }

    public void addResponse(String id, SubResponse subResponse) {
        subResponseMap.put(id, subResponse);
        responseCount.decrement();
    }

    public void cleanup(){
        responseCount.cleanup();
    }
}

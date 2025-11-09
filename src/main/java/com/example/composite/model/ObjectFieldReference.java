package com.example.composite.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;


@AllArgsConstructor
public class ObjectFieldReference implements NodeReference {
    private final ObjectNode parent;
    private final String fieldName;

    @Override
    public JsonNode getValue() {
        return parent.get(fieldName);
    }

    @Override
    public void overrideValue(JsonNode newValue) {
        parent.set(fieldName, newValue);
    }
}

package io.github.nabilcarel.composite.model;

import com.fasterxml.jackson.databind.JsonNode;

public interface NodeReference {
    JsonNode getValue();
    void overrideValue(JsonNode newValue);
}

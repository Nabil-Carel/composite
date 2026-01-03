package io.github.nabilcarel.composite.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ArrayElementReference implements NodeReference{
    private final ArrayNode parent;
    private final int index;

    @Override
    public JsonNode getValue() {
        return parent.get(index);
    }
    @Override
    public void overrideValue(JsonNode newValue) {
        parent.set(index, newValue);
    }

}

package io.github.nabilcarel.composite.integration.controllers;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/arrays")
public class TestArrayController {

    @GetMapping("/simple")
    @CompositeEndpoint
    public String[] getSimpleArray() {
        return new String[]{"first", "second", "third"};
    }

    @GetMapping("/numbers")
    @CompositeEndpoint
    public Integer[] getNumberArray() {
        return new Integer[]{1, 2, 3, 4, 5};
    }

    @GetMapping("/objects")
    @CompositeEndpoint
    public ArrayItemList getObjectArray() {
        return new ArrayItemList(List.of(
            new ArrayItem("item1", "value1"),
            new ArrayItem("item2", "value2"),
            new ArrayItem("item3", "value3")
        ));
    }

    @GetMapping("/nested")
    @CompositeEndpoint
    public NestedArrayData getNestedArray() {
        return new NestedArrayData(
            List.of(
                new NestedArrayData.InnerArray(List.of("a", "b", "c")),
                new NestedArrayData.InnerArray(List.of("x", "y", "z"))
            )
        );
    }

    @GetMapping("/with-map")
    @CompositeEndpoint
    public Map<String, Object> getArrayInMap() {
        return Map.of(
            "items", List.of("item1", "item2", "item3"),
            "count", 3,
            "metadata", Map.of("type", "array", "size", 3)
        );
    }

    // DTOs
    public record ArrayItem(String id, String value) {}
    public record ArrayItemList(List<ArrayItem> items) {}
    public record NestedArrayData(List<InnerArray> arrays) {
        public record InnerArray(List<String> values) {}
    }
}

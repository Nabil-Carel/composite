package com.example.composite.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PlaceholderResolution {
    private final String objectId;
    private final String propertyPath;
    private final Object root;
}

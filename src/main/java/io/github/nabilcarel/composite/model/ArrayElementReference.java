package io.github.nabilcarel.composite.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.AllArgsConstructor;

/**
 * {@link NodeReference} implementation that points to an element at a specific index within
 * a JSON {@link ArrayNode}.
 *
 * @see NodeReference
 * @see ObjectFieldReference
 * @since 0.0.1
 */
@AllArgsConstructor
public class ArrayElementReference implements NodeReference {

  /** The JSON array that contains the target element. */
  private final ArrayNode parent;

  /** The zero-based index of the element within {@link #parent} that holds the placeholder value. */
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

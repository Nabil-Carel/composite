package io.github.nabilcarel.composite.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;

/**
 * {@link NodeReference} implementation that points to a named field within a JSON
 * {@link ObjectNode}.
 *
 * @see NodeReference
 * @see ArrayElementReference
 * @since 0.0.1
 */
@AllArgsConstructor
public class ObjectFieldReference implements NodeReference {

  /** The JSON object that contains the target field. */
  private final ObjectNode parent;

  /** The name of the field within {@link #parent} that holds the placeholder value. */
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

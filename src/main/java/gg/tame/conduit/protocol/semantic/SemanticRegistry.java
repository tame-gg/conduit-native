package gg.tame.conduit.protocol.semantic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry blob representation. Do not blindly forward raw registry NBT across protocol boundaries.
 */
public final class SemanticRegistry {
  private final String name;
  private final Map<String, byte[]> entries = new LinkedHashMap<>();

  public SemanticRegistry(String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("registry name required");
    this.name = name;
  }

  public String name() { return name; }

  public SemanticRegistry put(String id, byte[] payload) {
    entries.put(id, payload == null ? new byte[0] : payload.clone());
    return this;
  }

  public Optional<byte[]> get(String id) {
    byte[] value = entries.get(id);
    return value == null ? Optional.empty() : Optional.of(value.clone());
  }

  public Map<String, byte[]> entries() {
    Map<String, byte[]> copy = new LinkedHashMap<>();
    for (var e : entries.entrySet()) copy.put(e.getKey(), e.getValue().clone());
    return Map.copyOf(copy);
  }
}

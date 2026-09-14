package gg.tame.conduit.protocol.semantic;

import java.util.Map;
import java.util.Optional;

/**
 * Cross-version item stack. Supports legacy metadata and modern data components
 * without forcing every item translation immediately.
 */
public record SemanticItemStack(
    String identifier,
    int count,
    Optional<Integer> legacyMetadata,
    Map<String, byte[]> components
) {
  public SemanticItemStack {
    if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("item identifier required");
    if (count < 0) throw new IllegalArgumentException("count must be >= 0");
    if (legacyMetadata == null) legacyMetadata = Optional.empty();
    if (components == null) components = Map.of();
    else components = Map.copyOf(components);
  }

  public static SemanticItemStack of(String identifier, int count) {
    return new SemanticItemStack(identifier, count, Optional.empty(), Map.of());
  }

  public static SemanticItemStack empty() {
    return new SemanticItemStack("minecraft:air", 0, Optional.empty(), Map.of());
  }

  public boolean isEmpty() {
    return count == 0 || "minecraft:air".equals(identifier);
  }
}

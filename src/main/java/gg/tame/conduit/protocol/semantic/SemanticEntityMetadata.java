package gg.tame.conduit.protocol.semantic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Entity metadata keyed by semantic field names rather than wire indices.
 * Indices/types change between versions — do not blindly forward indices.
 */
public final class SemanticEntityMetadata {
  private final Map<String, Entry> fields = new LinkedHashMap<>();

  public SemanticEntityMetadata put(String semanticKey, MetadataType type, Object value) {
    fields.put(semanticKey, new Entry(type, value));
    return this;
  }

  public Optional<Entry> get(String semanticKey) {
    return Optional.ofNullable(fields.get(semanticKey));
  }

  public Map<String, Entry> all() {
    return Map.copyOf(fields);
  }

  public record Entry(MetadataType type, Object value) {}

  public enum MetadataType {
    BYTE, VARINT, FLOAT, STRING, COMPONENT, BOOLEAN, POSE, OPTIONAL_UUID, OPTIONAL_BLOCK_STATE, NBT, UNKNOWN
  }
}

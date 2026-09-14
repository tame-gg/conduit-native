package gg.tame.conduit.protocol;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog of protocol identities and which ones have codecs/translators.
 * A catalog entry is not a codec — {@link ProtocolDefinition#hasCodec(int)} is authoritative.
 */
public final class ProtocolCatalog {
  private static final Map<Integer, ProtocolVersion> BY_NUMBER = new LinkedHashMap<>();

  static {
    for (ProtocolVersion version : ProtocolVersion.CATALOG) {
      BY_NUMBER.put(version.number(), version);
    }
  }

  private ProtocolCatalog() {}

  public static Optional<ProtocolVersion> find(int number) {
    return Optional.ofNullable(BY_NUMBER.get(number));
  }

  public static List<ProtocolVersion> all() {
    return ProtocolVersion.CATALOG;
  }

  public static List<ProtocolVersion> withCodecs() {
    return ProtocolVersion.CATALOG.stream().filter(v -> ProtocolDefinition.hasCodec(v.number())).toList();
  }

  public static Collection<Integer> codecNumbers() {
    return withCodecs().stream().map(ProtocolVersion::number).toList();
  }

  public static String describeSupport(int client, int backend) {
    return client + "→" + backend + "=" + ProtocolCompatibility.between(client, backend);
  }
}

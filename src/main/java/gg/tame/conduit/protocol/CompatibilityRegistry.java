package gg.tame.conduit.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit compatibility registry for the modern program.
 * Falls back to {@link ProtocolCompatibility#between(int, int)} when no override exists.
 */
public final class CompatibilityRegistry {
  private static final Map<Long, CompatibilityEntry> ENTRIES = new LinkedHashMap<>();

  static {
    register(765, 765, TranslationSupport.DIRECT, CompatibilityCompleteness.FULL, "same-version 1.20.4");
    register(766, 766, TranslationSupport.DIRECT, CompatibilityCompleteness.FULL, "same-version 1.20.5/1.20.6");
    register(776, 776, TranslationSupport.DIRECT, CompatibilityCompleteness.FULL, "same-version 26.2");
    register(763, 763, TranslationSupport.DIRECT, CompatibilityCompleteness.FULL, "same-version 1.20/1.20.1");
    // Validated on the wire: the official Minecraft 1.13 client reached the
    // official 1.13 server through Conduit and played for ~3 minutes with no
    // disconnect — login, chunk streaming, movement, combat, death, respawn and
    // an advancement all round-tripped. DIRECT forwards bytes transparently
    // where inspection is unnecessary, so this exercises the real path.
    registerValidated(393, 393, TranslationSupport.DIRECT, CompatibilityCompleteness.FULL,
        ValidationStatus.DIRECT_VERIFIED,
        "1.13 native — verified with the official 1.13 client and server");
    // 404 DIRECT verified with scripted 1.13.2 client against real 1.13.2 server
    // through Conduit (login, play, chunks/entities/inventory/keepalive).
    registerValidated(404, 404, TranslationSupport.DIRECT, CompatibilityCompleteness.FULL,
        ValidationStatus.DIRECT_VERIFIED,
        "1.13.2 native — verified with scripted protocol-404 client against official 1.13.2 server");
    // 393↔404: Slot wire-form delta; bidirectionally verified with real jars + probes.
    registerValidated(393, 404, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        ValidationStatus.TRANSLATED_VERIFIED,
        "real 1.13 client probe sustained play on real 1.13.2 server; Slot rematerialised; "
            + "recipes/advancements/trades dropped");
    registerValidated(404, 393, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        ValidationStatus.TRANSLATED_VERIFIED,
        "real 1.13.2 client probe sustained play on real 1.13 server; Slot rematerialised; "
            + "recipes/advancements/trades dropped");
    register(765, 766, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        "control/login/config packets; JoinGame/player-info/registry unsupported");
    register(766, 765, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        "control/login/config packets; JoinGame/player-info/registry unsupported");
    register(765, 776, TranslationSupport.UNSUPPORTED, CompatibilityCompleteness.NONE, "no 765↔776 translator");
    register(776, 765, TranslationSupport.UNSUPPORTED, CompatibilityCompleteness.NONE, "no 776↔765 translator");
    // Both directions were run end to end with official Minecraft clients and
    // servers; see work/real-client-validation/RESULTS-393-765-CROSS.md.
    // PARTIAL is still the honest completeness: inventory, entity metadata,
    // attributes, equipment and sounds are deliberately withheld because their
    // registries are not mapped across the pair.
    registerValidated(393, 765, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        ValidationStatus.TRANSLATED_VERIFIED,
        "real 1.13 client sustained play on a real 1.20.4 server, zero translation failures; "
            + "world/entities/movement/chat/combat translate; inventory, metadata, attributes, "
            + "equipment and sounds withheld (unmapped registries)");
    registerValidated(765, 393, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        ValidationStatus.TRANSLATED_VERIFIED,
        "real 1.20.4 client sustained play on a real 1.13 server, zero translation failures; "
            + "configuration state synthesised, living-entity spawns folded into the unified "
            + "spawn packet, chunks/movement/world events translate; same registry-bound gaps");
  }

  private CompatibilityRegistry() {}

  private static void register(int client, int backend, TranslationSupport support,
                               CompatibilityCompleteness completeness, String notes) {
    ENTRIES.put(key(client, backend), new CompatibilityEntry(client, backend, support, completeness, notes));
  }

  private static void registerValidated(int client, int backend, TranslationSupport support,
                                        CompatibilityCompleteness completeness,
                                        ValidationStatus validation, String notes) {
    ENTRIES.put(key(client, backend),
        new CompatibilityEntry(client, backend, support, completeness, validation, notes));
  }

  public static CompatibilityEntry resolve(int clientProtocol, int backendProtocol) {
    CompatibilityEntry explicit = ENTRIES.get(key(clientProtocol, backendProtocol));
    if (explicit != null) return explicit;
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, backendProtocol);
    CompatibilityCompleteness completeness = switch (support) {
      // Same-version forwarding is only FULL when the protocol's own packet
      // table was authored and validated for it. A DERIVED table inherits a
      // neighbour's layout plus a published delta and its capabilities have not
      // been audited against the release, so the path is usable but PARTIAL.
      // Reporting it FULL would be a support claim nothing has verified.
      case DIRECT -> ProtocolDefinition.codecStatus(clientProtocol) == CodecStatus.DERIVED
          ? CompatibilityCompleteness.PARTIAL
          : CompatibilityCompleteness.FULL;
      case TRANSLATED, PARTIAL -> CompatibilityCompleteness.PARTIAL;
      case UNSUPPORTED -> CompatibilityCompleteness.NONE;
    };
    String notes = switch (support) {
      case DIRECT -> "same-version forwarding; codec " + ProtocolDefinition.codecStatus(clientProtocol);
      case TRANSLATED, PARTIAL -> "registered translator " + clientProtocol + "→" + backendProtocol;
      case UNSUPPORTED -> unsupportedReason(clientProtocol, backendProtocol);
    };
    return new CompatibilityEntry(clientProtocol, backendProtocol, support, completeness, notes);
  }

  private static String unsupportedReason(int clientProtocol, int backendProtocol) {
    if (!ProtocolDefinition.hasCodec(clientProtocol)) return "no codec for client protocol " + clientProtocol;
    if (!ProtocolDefinition.hasCodec(backendProtocol)) return "no codec for backend protocol " + backendProtocol;
    return "no " + clientProtocol + "→" + backendProtocol + " translator registered";
  }

  public static Optional<CompatibilityEntry> find(int clientProtocol, int backendProtocol) {
    return Optional.ofNullable(ENTRIES.get(key(clientProtocol, backendProtocol)));
  }

  public static Map<Long, CompatibilityEntry> allExplicit() {
    return Map.copyOf(ENTRIES);
  }

  private static long key(int client, int backend) {
    return (((long) client) << 32) | (backend & 0xffffffffL);
  }
}

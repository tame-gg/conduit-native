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
    register(393, 393, TranslationSupport.DIRECT, CompatibilityCompleteness.PARTIAL,
        "1.13 direct codec — login/play essentials; real-client verification pending");
    register(765, 766, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        "control/login/config packets; JoinGame/player-info/registry unsupported");
    register(766, 765, TranslationSupport.TRANSLATED, CompatibilityCompleteness.PARTIAL,
        "control/login/config packets; JoinGame/player-info/registry unsupported");
    register(765, 776, TranslationSupport.UNSUPPORTED, CompatibilityCompleteness.NONE, "no 765↔776 translator");
    register(776, 765, TranslationSupport.UNSUPPORTED, CompatibilityCompleteness.NONE, "no 776↔765 translator");
    register(393, 765, TranslationSupport.UNSUPPORTED, CompatibilityCompleteness.NONE,
        "1.13→1.20.4 translation not implemented yet");
    register(765, 393, TranslationSupport.UNSUPPORTED, CompatibilityCompleteness.NONE,
        "1.20.4→1.13 translation not implemented yet");
  }

  private CompatibilityRegistry() {}

  private static void register(int client, int backend, TranslationSupport support,
                               CompatibilityCompleteness completeness, String notes) {
    ENTRIES.put(key(client, backend), new CompatibilityEntry(client, backend, support, completeness, notes));
  }

  public static CompatibilityEntry resolve(int clientProtocol, int backendProtocol) {
    CompatibilityEntry explicit = ENTRIES.get(key(clientProtocol, backendProtocol));
    if (explicit != null) return explicit;
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, backendProtocol);
    CompatibilityCompleteness completeness = switch (support) {
      case DIRECT -> CompatibilityCompleteness.FULL;
      case TRANSLATED, PARTIAL -> CompatibilityCompleteness.PARTIAL;
      case UNSUPPORTED -> CompatibilityCompleteness.NONE;
    };
    return new CompatibilityEntry(clientProtocol, backendProtocol, support, completeness, "derived");
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

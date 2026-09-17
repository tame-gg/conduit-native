package gg.tame.conduit.protocol;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import gg.tame.conduit.viaversion.ConduitViaSupport;

/**
 * Answers, for one ordered (client, backend) protocol pair, what Conduit would actually do.
 *
 * <p>This is the single place the question is decided. It used to be answered in three: once to
 * produce a {@link TranslationSupport} verdict, once to pick a translator instance, and once more
 * in a hand-maintained table of overrides. Three answers to one question is three chances to
 * disagree, and they did &mdash; the override table still claimed 765&harr;776 had no translator
 * long after Via had a path for it, which is a pair the proxy refused to route while being
 * perfectly able to carry it. So the verdict and the engine are computed together, from the same
 * inputs, and everything else reads this.
 *
 * <p>Nothing here infers support from a protocol number existing somewhere. A native path requires
 * a registered translator for that exact ordered pair, and a Via path requires Via to return a
 * non-empty protocol path for it right now, with the addons the operator actually loaded. Both are
 * cheap lookups over structures built at startup, and neither allocates a session or a socket, so
 * this is safe to call per connection and per {@code /server}.
 */
public final class CompatibilityProbe {
  private CompatibilityProbe() {}

  /** Which implementation would carry the pair. */
  public enum Engine {
    /** No path at all. */
    NONE,
    /** Same protocol on both sides; bytes are forwarded without a translator. */
    DIRECT,
    /** One of Conduit's own translators, registered for this ordered pair. */
    NATIVE,
    /** The ViaVersion ecosystem, over a path it reports for this ordered pair. */
    VIA
  }

  /**
   * @param clientProtocol   the protocol the client speaks
   * @param backendProtocol  the protocol the backend speaks
   * @param support          the verdict Conduit reports for the pair
   * @param engine           who would carry it
   * @param clientAdmissible whether Conduit has a packet table for the client's own protocol, which
   *                         it needs before a session exists regardless of who translates
   * @param reason           why, in one phrase, for logs and diagnostics
   */
  public record Result(int clientProtocol, int backendProtocol, TranslationSupport support,
                       Engine engine, boolean clientAdmissible, String reason) {
    /** Whether a session for this pair can be established at all. */
    public boolean usable() {
      return clientAdmissible && support != TranslationSupport.UNSUPPORTED;
    }
  }

  public static Result probe(int clientProtocol, int backendProtocol) {
    boolean admissible = ProtocolDefinition.hasCodec(clientProtocol);

    if (clientProtocol == backendProtocol) {
      boolean known = admissible || ConduitViaSupport.knowsProtocol(clientProtocol);
      return known
          ? new Result(clientProtocol, backendProtocol, TranslationSupport.DIRECT, Engine.DIRECT,
              admissible, "same protocol on both sides")
          : new Result(clientProtocol, backendProtocol, TranslationSupport.UNSUPPORTED, Engine.NONE,
              admissible, "protocol " + clientProtocol + " is unknown to Conduit and to Via");
    }

    TranslationSettings settings = ConduitViaBootstrap.settings();
    TranslationSettings.TranslationEngine engine = settings.engine();

    boolean viaPossible = settings.enabled()
        && engine != TranslationSettings.TranslationEngine.NATIVE
        && ConduitViaSupport.supportsTranslation(clientProtocol, backendProtocol);
    boolean nativePossible = ProtocolDefinition.hasCodec(clientProtocol)
        && ProtocolDefinition.hasCodec(backendProtocol)
        && TranslatorRegistry.has(clientProtocol, backendProtocol);

    return switch (engine) {
      case VIA -> viaPossible
          ? translated(clientProtocol, backendProtocol, Engine.VIA, admissible, "Via path (engine=via)")
          : unsupported(clientProtocol, backendProtocol, admissible,
              "engine=via and Via reports no path");
      case NATIVE -> nativePossible
          ? translated(clientProtocol, backendProtocol, Engine.NATIVE, admissible,
              "registered native translator (engine=native)")
          : unsupported(clientProtocol, backendProtocol, admissible,
              "engine=native and no translator is registered for this ordered pair");
      case VIA_PREFERRED -> viaPossible
          ? translated(clientProtocol, backendProtocol, Engine.VIA, admissible, "Via path (preferred)")
          : nativePossible
              ? translated(clientProtocol, backendProtocol, Engine.NATIVE, admissible,
                  "registered native translator (Via has no path)")
              : unsupported(clientProtocol, backendProtocol, admissible,
                  settings.enabled()
                      ? "neither Via nor a native translator covers this ordered pair"
                      : "translation is disabled and the protocols differ");
    };
  }

  private static Result translated(int client, int backend, Engine engine, boolean admissible, String reason) {
    return new Result(client, backend, TranslationSupport.TRANSLATED, engine, admissible,
        admissible ? reason : reason + ", but Conduit has no packet table for protocol " + client);
  }

  private static Result unsupported(int client, int backend, boolean admissible, String reason) {
    return new Result(client, backend, TranslationSupport.UNSUPPORTED, Engine.NONE, admissible, reason);
  }
}

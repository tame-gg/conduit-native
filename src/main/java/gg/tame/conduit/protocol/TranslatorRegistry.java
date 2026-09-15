package gg.tame.conduit.protocol;

import gg.tame.conduit.protocol.translate.Protocol393To765Translator;
import gg.tame.conduit.protocol.translate.Protocol765To766Translator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single source of truth for which (client, backend) protocol pairs Conduit
 * can actually translate.
 *
 * <p>Translation direction is part of the key. A registration for 393&rarr;765
 * says nothing about 765&rarr;393: the two directions face different problems
 * (a field the source lacks must be synthesised one way and discarded the
 * other, the configuration state exists on one side only, block-state and
 * metadata ids map non-injectively) and are registered independently. Nothing
 * here mirrors a pair automatically.
 *
 * <p>Previously the set of translatable pairs was written out twice &mdash; once
 * to decide {@link TranslationSupport}, once to pick the translator instance
 * &mdash; which let the two disagree and let Conduit advertise a pair it could
 * not carry. Both now read this map.
 */
public final class TranslatorRegistry {

  /** Keyed by (client protocol, backend protocol); never mirrored. */
  private static final Map<Long, Supplier<ProtocolTranslator>> PAIRS = new LinkedHashMap<>();

  static {
    register(393, 765, () -> Protocol393To765Translator.CLIENT_393_BACKEND_765);
    register(765, 393, () -> Protocol393To765Translator.CLIENT_765_BACKEND_393);
    register(765, 766, () -> Protocol765To766Translator.V765_TO_766);
    register(766, 765, () -> Protocol765To766Translator.V766_TO_765);
  }

  private TranslatorRegistry() {}

  private static void register(int clientProtocol, int backendProtocol,
                               Supplier<ProtocolTranslator> translator) {
    PAIRS.put(key(clientProtocol, backendProtocol), translator);
  }

  /** Whether a translator exists for this ordered pair. */
  public static boolean has(int clientProtocol, int backendProtocol) {
    return PAIRS.containsKey(key(clientProtocol, backendProtocol));
  }

  /** The translator for this ordered pair, if one is registered. */
  public static Optional<ProtocolTranslator> find(int clientProtocol, int backendProtocol) {
    Supplier<ProtocolTranslator> supplier = PAIRS.get(key(clientProtocol, backendProtocol));
    return supplier == null ? Optional.empty() : Optional.of(supplier.get());
  }

  /** Every registered ordered pair, as {@code clientProtocol -> backendProtocol}. */
  public static Map<Integer, Integer> pairs() {
    Map<Integer, Integer> result = new LinkedHashMap<>();
    PAIRS.keySet().forEach(k -> result.put((int) (k >> 32), (int) (long) k));
    return Map.copyOf(result);
  }

  /** Number of registered ordered pairs. */
  public static int size() {
    return PAIRS.size();
  }

  private static long key(int clientProtocol, int backendProtocol) {
    return (((long) clientProtocol) << 32) | (backendProtocol & 0xffffffffL);
  }
}

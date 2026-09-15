package gg.tame.conduit.protocol;

/**
 * How far a client&rarr;backend path has been proven, kept separate from whether
 * a path exists at all ({@link TranslationSupport}) and from how much of the
 * protocol it covers ({@link CompatibilityCompleteness}).
 *
 * <p>The distinction that matters: a unit test exercising a codec proves the
 * codec, not the pairing. Only a real Minecraft client of the source version
 * reaching a real Minecraft server of the target version, through Conduit, may
 * promote a pair to one of the VERIFIED values.
 */
public enum ValidationStatus {
  /** No codec, or a codec too incomplete to attempt the path. */
  CODEC_PARTIAL,

  /** Codec covers the packets Conduit needs, but the path has not been run. */
  CODEC_COMPLETE,

  /** Same-version forwarding proven with a real client and real server. */
  DIRECT_VERIFIED,

  /** A translator exists and unit tests pass, but no real cross-version run. */
  TRANSLATED_PARTIAL,

  /**
   * A real client of the source version played through Conduit against a real
   * server of the target version. Does not mean every feature translates &mdash;
   * check {@link CompatibilityCompleteness} and the entry's notes for what is
   * still withheld.
   */
  TRANSLATED_VERIFIED
}

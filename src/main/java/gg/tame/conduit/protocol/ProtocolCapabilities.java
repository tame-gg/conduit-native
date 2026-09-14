package gg.tame.conduit.protocol;

/**
 * Version-scoped login/play capabilities. Prefer these over raw version comparisons.
 */
public record ProtocolCapabilities(
    boolean configurationPhase,
    boolean loginShouldAuthenticate,
    boolean knownPacks,
    boolean joinGameOnlineMode,
    boolean cookiePackets,
    boolean transferPackets,
    boolean chatSigning,
    boolean secureChat
) {
  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate) {
    this(configurationPhase, loginShouldAuthenticate, false, false, false, false, true, true);
  }
  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate, boolean knownPacks) {
    this(configurationPhase, loginShouldAuthenticate, knownPacks, false, false, false, true, true);
  }
  public ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate, boolean knownPacks,
                              boolean joinGameOnlineMode) {
    this(configurationPhase, loginShouldAuthenticate, knownPacks, joinGameOnlineMode, false, false, true, true);
  }

  public static ProtocolCapabilities modernConfig(boolean knownPacks, boolean joinGameOnlineMode,
                                                  boolean cookies, boolean transfer) {
    return new ProtocolCapabilities(true, joinGameOnlineMode, knownPacks, joinGameOnlineMode, cookies, transfer, true, true);
  }

  public static ProtocolCapabilities legacyPlay() {
    return new ProtocolCapabilities(false, false, false, false, false, false, false, false);
  }
}

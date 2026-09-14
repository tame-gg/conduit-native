package gg.tame.conduit.protocol;

/** Version-scoped login/play capabilities. Not a substitute for packet codecs. */
public record ProtocolCapabilities(boolean configurationPhase, boolean loginShouldAuthenticate) {}

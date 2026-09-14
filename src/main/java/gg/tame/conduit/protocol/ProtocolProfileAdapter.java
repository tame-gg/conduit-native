package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import java.io.IOException;

/** Applies the native authenticated profile to protocol-specific clientbound packets. */
public final class ProtocolProfileAdapter {
  private ProtocolProfileAdapter() {}
  public static byte[] backendToClient(ProtocolDefinition protocol, ConnectionState state, byte[] packet, PlayerProfile profile) {
    try {
      packet = LoginSuccess.replaceProfile(protocol, packet, profile);
      packet = PlayerInfoUpdate.ensureOwnTextures(protocol, packet, profile);
    } catch (IOException ignored) { }
    return packet;
  }
}

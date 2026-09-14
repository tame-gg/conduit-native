package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import java.io.IOException;

/** Applies the native authenticated profile to protocol-specific clientbound packets. */
public final class ProtocolProfileAdapter {
  private ProtocolProfileAdapter() {}
  public static byte[] backendToClient(ProtocolDefinition protocol, ConnectionState state, byte[] packet, PlayerProfile profile) {
    try {
      if (state == ConnectionState.LOGIN) return LoginSuccess.replaceProfile(protocol, packet, profile);
      if (state == ConnectionState.PLAY) return PlayerInfoUpdate.ensureOwnTextures(protocol, packet, profile);
    } catch (IOException ignored) { }
    return packet;
  }
}

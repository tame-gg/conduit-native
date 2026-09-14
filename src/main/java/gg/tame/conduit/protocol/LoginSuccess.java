package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Replaces the Game Profile in Login Success while preserving trailing fields
 * (1.20.2 strict-error boolean, 26.2 session UUID).
 */
public final class LoginSuccess {
  private LoginSuccess() {}
  public static byte[] replaceProfile(ProtocolDefinition protocol, byte[] packet, PlayerProfile profile) throws IOException {
    if (!profile.authenticated() && profile.properties().isEmpty()) return packet;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_SUCCESS)) return packet;
      GameProfiles.skip(input);
      byte[] trailer = input.readAllBytes();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(output, id);
        GameProfiles.write(output, profile);
        output.write(trailer);
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      return packet;
    }
  }
}

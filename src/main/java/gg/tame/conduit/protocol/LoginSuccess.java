// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Replaces the Game Profile in Login Success while preserving trailing fields
 * (1.20.2 strict-error boolean, 26.2 session UUID).
 * <p>1.13 Login Success is string UUID + username only (no properties).
 */
public final class LoginSuccess {
  private LoginSuccess() {}
  public static byte[] replaceProfile(ProtocolDefinition protocol, byte[] packet, PlayerProfile profile) throws IOException {
    if (!profile.authenticated() && profile.properties().isEmpty()) return packet;
    ProtocolCapabilities caps = protocol.capabilities();
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_SUCCESS)) return packet;
      if (!caps.loginSuccessBinaryUuid()) {
        MinecraftInput.string(input, 36);
        MinecraftInput.string(input, 16);
        byte[] trailer = input.readAllBytes();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
          MinecraftOutput.varInt(output, id);
          MinecraftOutput.string(output, profile.uniqueId().toString());
          MinecraftOutput.string(output, profile.username());
          output.write(trailer);
        }
        return bytes.toByteArray();
      }
      GameProfiles.skip(input);
      byte[] trailer = input.readAllBytes();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(output, id);
        if (caps.loginSuccessProperties()) GameProfiles.write(output, profile);
        else {
          GameProfiles.writeUuid(output, profile.uniqueId());
          MinecraftOutput.string(output, profile.username());
        }
        output.write(trailer);
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      return packet;
    }
  }

  public static byte[] encode(ProtocolDefinition protocol, PlayerProfile profile) throws IOException {
    ProtocolCapabilities caps = protocol.capabilities();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS));
      if (!caps.loginSuccessBinaryUuid()) {
        MinecraftOutput.string(output, profile.uniqueId().toString());
        MinecraftOutput.string(output, profile.username());
      } else if (caps.loginSuccessProperties()) {
        GameProfiles.write(output, profile);
      } else {
        GameProfiles.writeUuid(output, profile.uniqueId());
        MinecraftOutput.string(output, profile.username());
      }
    }
    return bytes.toByteArray();
  }

  public static UUID peekUuid(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      if (!protocol.capabilities().loginSuccessBinaryUuid()) {
        return UUID.fromString(MinecraftInput.string(input, 36));
      }
      return GameProfiles.readUuid(input);
    }
  }
}

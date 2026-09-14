package gg.tame.conduit.login;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.ProtocolCapabilities;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Login Start codec. Field layout follows the protocol definition:
 * modern: username + UUID; 1.13-era: username only.
 */
public record LoginStart(String username, UUID clientUuid) {
  public static LoginStart decode(byte[] packetWithoutId) throws IOException {
    return decode(packetWithoutId, true);
  }

  public static LoginStart decode(byte[] packetWithoutId, ProtocolDefinition protocol) throws IOException {
    return decode(packetWithoutId, protocol.capabilities().loginStartUuid());
  }

  public static LoginStart decode(byte[] packetWithoutId, boolean expectsUuid) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packetWithoutId))) {
      String username = MinecraftInput.string(input, 16);
      UUID uuid;
      if (expectsUuid) {
        uuid = new UUID(input.readLong(), input.readLong());
        if (input.available() != 0) throw new IOException("Login Start contains unsupported extra fields");
      } else {
        if (input.available() != 0) throw new IOException("Login Start contains unsupported extra fields");
        uuid = offlineUuid(username);
      }
      return new LoginStart(username, uuid);
    }
  }

  public PlayerProfile unverifiedProfile() { return new PlayerProfile(clientUuid, username, List.of(), false); }

  public static byte[] encode(PlayerProfile profile) throws IOException {
    return encode(profile, true);
  }

  public static byte[] encode(PlayerProfile profile, ProtocolDefinition protocol) throws IOException {
    if (protocol == null) return encode(profile, true);
    return encode(profile, protocol.capabilities().loginStartUuid());
  }

  public static byte[] encode(PlayerProfile profile, ProtocolCapabilities capabilities) throws IOException {
    return encode(profile, capabilities.loginStartUuid());
  }

  public static byte[] encode(PlayerProfile profile, boolean includeUuid) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0);
      MinecraftOutput.string(output, profile.username());
      if (includeUuid) {
        output.writeLong(profile.uniqueId().getMostSignificantBits());
        output.writeLong(profile.uniqueId().getLeastSignificantBits());
      }
    }
    return bytes.toByteArray();
  }

  public static UUID offlineUuid(String username) {
    return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
  }
}

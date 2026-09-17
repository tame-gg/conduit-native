package gg.tame.conduit.login;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.ProtocolCapabilities;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolEras;
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

  /**
   * 1.19 to 1.20.1 put optional fields after the username: the profile key signature (1.19-1.19.2),
   * then the UUID (1.19.1-1.20.1), each behind a present flag. Read as username-only, or as username
   * and a bare UUID, every one of those clients was refused with "Login Start contains unsupported
   * extra fields" before its login began.
   */
  public static LoginStart decode(byte[] packetWithoutId, ProtocolDefinition protocol) throws IOException {
    int number = protocol.version().number();
    if (!ProtocolEras.loginStartOptionalFields(number)) {
      return decode(packetWithoutId, protocol.capabilities().loginStartUuid());
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packetWithoutId))) {
      String username = MinecraftInput.string(input, 16);
      if (ProtocolEras.loginStartSignature(number) && input.readBoolean()) {
        input.readLong();                                  // key expiry
        skipBytes(input, MAX_PUBLIC_KEY_BYTES);            // public key
        skipBytes(input, MAX_KEY_SIGNATURE_BYTES);         // Mojang's signature over it
      }
      UUID uuid = null;
      if (ProtocolEras.loginStartOptionalUuid(number) && input.readBoolean()) {
        uuid = new UUID(input.readLong(), input.readLong());
      }
      if (input.available() != 0) throw new IOException("Login Start contains unsupported extra fields");
      return new LoginStart(username, uuid != null ? uuid : offlineUuid(username));
    }
  }

  private static final int MAX_PUBLIC_KEY_BYTES = 512;
  private static final int MAX_KEY_SIGNATURE_BYTES = 4096;

  private static void skipBytes(DataInputStream input, int maximum) throws IOException {
    int length = MinecraftInput.varInt(input);
    if (length < 0 || length > maximum) throw new IOException("Login Start key field of " + length + " bytes");
    input.readNBytes(length);
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
    int number = protocol.version().number();
    if (!ProtocolEras.loginStartOptionalFields(number)) return encode(profile, protocol.capabilities().loginStartUuid());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0);
      MinecraftOutput.string(output, profile.username());
      // No profile key goes to a backend: the key is the client's, and a proxy has none to give.
      if (ProtocolEras.loginStartSignature(number)) output.writeBoolean(false);
      if (ProtocolEras.loginStartOptionalUuid(number)) {
        output.writeBoolean(true);
        output.writeLong(profile.uniqueId().getMostSignificantBits());
        output.writeLong(profile.uniqueId().getLeastSignificantBits());
      }
    }
    return bytes.toByteArray();
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

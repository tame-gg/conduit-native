package gg.tame.conduit.login;

import gg.tame.conduit.protocol.MinecraftInput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Decodes the 1.20.4 Login Start fields Conduit needs before backend connection. */
public record LoginStart(String username, UUID clientUuid) {
  public static LoginStart decode(byte[] packetWithoutId) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packetWithoutId))) {
      String username = MinecraftInput.string(input, 16);
      UUID uuid = new UUID(input.readLong(), input.readLong());
      if (input.available() != 0) throw new IOException("Login Start contains unsupported extra fields");
      return new LoginStart(username, uuid);
    }
  }
  public PlayerProfile unverifiedProfile() { return new PlayerProfile(clientUuid, username, List.of(), false); }
}

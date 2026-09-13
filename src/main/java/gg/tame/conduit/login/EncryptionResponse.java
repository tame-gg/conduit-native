package gg.tame.conduit.login;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Serverbound Encryption Response for protocol 765. */
public record EncryptionResponse(byte[] encryptedSharedSecret, byte[] encryptedVerifyToken) {
  public static EncryptionResponse decode(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_ENCRYPTION_RESPONSE)) {
        throw new IOException("expected encryption response");
      }
      byte[] secret = MinecraftInput.bytes(input, 256);
      byte[] token = MinecraftInput.bytes(input, 256);
      if (input.available() != 0) throw new IOException("encryption response contains trailing data");
      return new EncryptionResponse(secret, token);
    }
  }
}

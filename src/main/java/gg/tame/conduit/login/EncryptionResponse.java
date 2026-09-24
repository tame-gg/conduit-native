// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolEras;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Serverbound Encryption Response. 1.7 prefixes both arrays with a short. 1.19 and 1.19.2 put a
 * flag after the secret: set, the encrypted verify token follows; clear, a salt and the client's
 * profile-key signature over the plain token and that salt stand in for it
 * ({@code encryptedVerifyToken} is then null).
 */
public record EncryptionResponse(byte[] encryptedSharedSecret, byte[] encryptedVerifyToken, long salt, byte[] signature) {
  public static EncryptionResponse decode(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_ENCRYPTION_RESPONSE)) {
        throw new IOException("expected encryption response");
      }
      EncryptionResponse response;
      if (protocol.shortLoginByteArrays()) {
        response = new EncryptionResponse(MinecraftInput.shortBytes(input, 256), MinecraftInput.shortBytes(input, 256), 0, null);
      } else {
        byte[] secret = MinecraftInput.bytes(input, 256);
        if (ProtocolEras.loginStartSignature(protocol.version().number()) && !input.readBoolean()) {
          response = new EncryptionResponse(secret, null, input.readLong(), MinecraftInput.bytes(input, 1024));
        } else {
          response = new EncryptionResponse(secret, MinecraftInput.bytes(input, 256), 0, null);
        }
      }
      if (input.available() != 0) throw new IOException("encryption response contains trailing data");
      return response;
    }
  }
}

package gg.tame.conduit.login;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.PublicKey;

/**
 * Clientbound login encryption request ({@code minecraft:hello}).
 * Protocol 776 adds Should Authenticate after the verify token; 765 does not.
 */
public record EncryptionRequest(String serverId, byte[] publicKey, byte[] verifyToken, boolean shouldAuthenticate) {
  public byte[] encode(ProtocolDefinition protocol) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST));
      MinecraftOutput.string(output, serverId);
      MinecraftOutput.bytes(output, publicKey);
      MinecraftOutput.bytes(output, verifyToken);
      if (protocol.loginShouldAuthenticate()) output.writeBoolean(shouldAuthenticate);
    }
    return bytes.toByteArray();
  }
  public static EncryptionRequest decode(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_ENCRYPTION_REQUEST)) {
        throw new IOException("expected encryption request");
      }
      String serverId = MinecraftInput.string(input, 20);
      byte[] publicKey = MinecraftInput.bytes(input, 1024);
      byte[] verifyToken = MinecraftInput.bytes(input, 32);
      boolean shouldAuthenticate = false;
      if (protocol.loginShouldAuthenticate()) {
        if (input.available() < 1) throw new IOException("truncated encryption request: missing should-authenticate");
        shouldAuthenticate = input.readBoolean();
      }
      if (input.available() != 0) throw new IOException("encryption request contains trailing data");
      return new EncryptionRequest(serverId, publicKey, verifyToken, shouldAuthenticate);
    }
  }
  public static EncryptionRequest create(ProtocolDefinition protocol, PublicKey publicKey, byte[] verifyToken) {
    return new EncryptionRequest("", publicKey.getEncoded(), verifyToken, protocol.loginShouldAuthenticate());
  }
}

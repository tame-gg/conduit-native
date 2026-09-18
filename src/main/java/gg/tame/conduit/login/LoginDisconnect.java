package gg.tame.conduit.login;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class LoginDisconnect {
  private LoginDisconnect() { }
  public static byte[] encode(ProtocolDefinition protocol, String message) throws IOException {
    // Escaped in full: a newline in a configured kick message left the JSON unreadable to the client.
    String json = gg.tame.conduit.protocol.text.ComponentCodec.literalJson(message);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT));
      MinecraftOutput.string(output, json);
    }
    return bytes.toByteArray();
  }
}

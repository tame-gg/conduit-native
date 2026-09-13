package gg.tame.conduit.login;

import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Encodes a serverbound login-plugin response without exposing raw secret material. */
public record LoginPluginResponse(int messageId, boolean successful, byte[] data) {
  public byte[] encode(int packetId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, packetId); MinecraftOutput.varInt(output, messageId); output.writeBoolean(successful); if (successful) output.write(data);
    }
    return bytes.toByteArray();
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import gg.tame.conduit.protocol.MinecraftInput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Login-plugin request, including its correlation id: a backend's, or one a plugin sends a client. */
public record LoginPluginRequest(int messageId, String channel, byte[] data) {
  public byte[] encode(int packetId) throws IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, packetId);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, messageId);
      gg.tame.conduit.protocol.MinecraftOutput.string(output, channel);
      output.write(data);
    }
    return bytes.toByteArray();
  }
  public static LoginPluginRequest decode(byte[] body, int maximumPayloadBytes) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int messageId = MinecraftInput.varInt(input);
      String channel = MinecraftInput.string(input, 32767);
      if (channel.isBlank()) throw new IOException("empty login plugin channel");
      byte[] data = input.readAllBytes();
      if (data.length > maximumPayloadBytes) throw new IOException("login plugin payload exceeds configured frame limit");
      return new LoginPluginRequest(messageId, channel, data);
    }
  }
}

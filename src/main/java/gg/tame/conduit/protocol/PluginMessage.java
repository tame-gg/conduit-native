// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record PluginMessage(String channel, byte[] data) {
  public static PluginMessage decodeBody(byte[] body, int maximumBytes) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      String channel = MinecraftInput.string(input, 32767);
      byte[] data = input.readAllBytes();
      if (data.length > maximumBytes) throw new IOException("plugin message exceeds limit");
      return new PluginMessage(channel, data);
    }
  }
  public byte[] encode(int packetId) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, packetId);
      MinecraftOutput.string(output, channel);
      output.write(data);
    }
    return bytes.toByteArray();
  }
  public static byte[] brandPayload(String brand) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.string(output, brand); }
    return bytes.toByteArray();
  }
  public String brandText() throws IOException {
    return MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(data)), 32767);
  }
}

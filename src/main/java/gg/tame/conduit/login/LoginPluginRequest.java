package gg.tame.conduit.login;

import gg.tame.conduit.protocol.MinecraftInput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Backend login-plugin request, including its correlation id. */
public record LoginPluginRequest(int messageId, String channel, byte[] data) {
  public static LoginPluginRequest decode(byte[] body, int maximumPayloadBytes) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int messageId = MinecraftInput.varInt(input);
      if (messageId < 0) throw new IOException("invalid login plugin message id");
      String channel = MinecraftInput.string(input, 32767);
      if (channel.isBlank()) throw new IOException("empty login plugin channel");
      byte[] data = input.readAllBytes();
      if (data.length > maximumPayloadBytes) throw new IOException("login plugin payload exceeds configured frame limit");
      return new LoginPluginRequest(messageId, channel, data);
    }
  }
}

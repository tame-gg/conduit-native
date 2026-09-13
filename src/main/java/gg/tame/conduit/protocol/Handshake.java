package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** The only packet decoded before transparent MVP relaying begins. */
public record Handshake(int protocolVersion, String requestedHost, int requestedPort, int nextState) {
  public byte[] encode() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0);
      MinecraftOutput.varInt(output, protocolVersion);
      MinecraftOutput.string(output, requestedHost);
      output.writeShort(requestedPort);
      MinecraftOutput.varInt(output, nextState);
    }
    return bytes.toByteArray();
  }
  public static Handshake decode(byte[] payload) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      if (MinecraftInput.varInt(input) != 0) throw new IOException("first packet must be handshake id 0");
      int protocolVersion = MinecraftInput.varInt(input);
      String host = MinecraftInput.string(input, 255);
      int port = input.readUnsignedShort();
      int nextState = MinecraftInput.varInt(input);
      if (input.available() != 0) throw new IOException("handshake contains trailing data");
      if (nextState != 1 && nextState != 2) throw new IOException("unsupported handshake target");
      return new Handshake(protocolVersion, host, port, nextState);
    }
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** The only packet decoded before transparent MVP relaying begins. */
public record Handshake(int protocolVersion, String requestedHost, int requestedPort, int nextState) {
  /** Intent of a client another server sent here with a Transfer packet: a login, arriving by transfer. */
  public static final int TRANSFER = 3;
  /** 1.20.5, the first version with transfers; an older client never sends that intent. */
  public static final int TRANSFERS_FROM = 766;
  /** The host and port the client says it dialled, unresolved, without the markers Forge appends after a NUL. */
  public java.net.InetSocketAddress virtualHost() {
    int marker = requestedHost.indexOf('\0');
    return java.net.InetSocketAddress.createUnresolved(marker < 0 ? requestedHost : requestedHost.substring(0, marker), requestedPort);
  }
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
      if (nextState != 1 && nextState != 2 && !(nextState == TRANSFER && protocolVersion >= TRANSFERS_FROM)) {
        throw new IOException("unsupported handshake target");
      }
      return new Handshake(protocolVersion, host, port, nextState);
    }
  }
}

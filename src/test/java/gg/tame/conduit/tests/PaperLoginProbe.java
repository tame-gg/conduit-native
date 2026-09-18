// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

/** Protocol-765 login probe used for Paper interoperability, not a full game client. */
public final class PaperLoginProbe {
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2) throw new IllegalArgumentException("usage: host port");
    String host = arguments[0];
    int port = Integer.parseInt(arguments[1]);
    UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 10_000);
      socket.setSoTimeout(20_000);
      MinecraftFrames.write(socket.getOutputStream(), handshake(host, port));
      MinecraftFrames.write(socket.getOutputStream(), loginStart("Player", uuid));
      byte[] packet = MinecraftFrames.read(socket.getInputStream(), 1_048_576);
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
        int id = MinecraftInput.varInt(input);
        System.out.println("first-packet-id=" + id);
        if (id == 0) {
          String reason = MinecraftInput.string(input, 262144);
          System.out.println("login-disconnect=" + sanitize(reason));
          System.exit(2);
        }
        if (id == 4) {
          int messageId = MinecraftInput.varInt(input);
          String channel = MinecraftInput.string(input, 32767);
          byte[] data = input.readAllBytes();
          System.out.println("login-plugin-request message-id=" + messageId + " channel=" + channel + " data-len=" + data.length + " data-hex=" + hex(data));
          System.exit(4);
        }
        if (id != 2) {
          System.out.println("unexpected-login-packet=" + id);
          System.exit(3);
        }
        UUID forwarded = new UUID(input.readLong(), input.readLong());
        String username = MinecraftInput.string(input, 16);
        System.out.println("login-success username=" + username + " uuid=" + forwarded);
      }
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {3});
      boolean finishedConfiguration = false;
      boolean reachedPlay = false;
      for (int i = 0; i < 64; i++) {
        byte[] next = MinecraftFrames.read(socket.getInputStream(), 1_048_576);
        int id = MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(next)));
        if (!finishedConfiguration && id == 2) {
          MinecraftFrames.write(socket.getOutputStream(), new byte[] {2});
          finishedConfiguration = true;
          System.out.println("configuration-finish");
          continue;
        }
        if (finishedConfiguration && id == 0x29) {
          reachedPlay = true;
          System.out.println("play-login");
          break;
        }
      }
      if (!reachedPlay) {
        System.out.println("play-not-reached finished-configuration=" + finishedConfiguration);
        System.exit(finishedConfiguration ? 5 : 6);
      }
      System.exit(0);
    }
  }
  private static byte[] handshake(String host, int port) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0);
      MinecraftOutput.varInt(output, 765);
      MinecraftOutput.string(output, host);
      output.writeShort(port);
      MinecraftOutput.varInt(output, 2);
    }
    return bytes.toByteArray();
  }
  private static byte[] loginStart(String username, UUID uuid) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0);
      MinecraftOutput.string(output, username);
      output.writeLong(uuid.getMostSignificantBits());
      output.writeLong(uuid.getLeastSignificantBits());
    }
    return bytes.toByteArray();
  }
  private static String hex(byte[] data) {
    StringBuilder builder = new StringBuilder();
    for (byte value : data) builder.append(String.format("%02x", value));
    return builder.toString();
  }
  private static String sanitize(String reason) {
    return reason.replaceAll("(?i)secret[^\"]{0,40}", "[redacted]");
  }
}

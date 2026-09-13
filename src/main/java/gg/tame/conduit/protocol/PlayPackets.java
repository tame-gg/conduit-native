package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public final class PlayPackets {
  private PlayPackets() {}
  public static byte[] loginAcknowledged(ProtocolDefinition protocol) throws IOException {
    return idOnly(protocol.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED));
  }
  public static byte[] startConfiguration(ProtocolDefinition protocol) throws IOException {
    return idOnly(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION));
  }
  public static byte[] systemChat(ProtocolDefinition protocol, String message) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT));
      if (protocol.hasConfiguration()) NetworkNbt.stringComponent(output, message);
      else MinecraftOutput.string(output, "{\"text\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
      output.writeBoolean(false);
    }
    return bytes.toByteArray();
  }
  public static byte[] tabComplete(ProtocolDefinition protocol, int transactionId, int start, int length, List<String> matches) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE));
      MinecraftOutput.varInt(output, transactionId);
      MinecraftOutput.varInt(output, start);
      MinecraftOutput.varInt(output, length);
      MinecraftOutput.varInt(output, matches.size());
      for (String match : matches) {
        MinecraftOutput.string(output, match);
        output.writeBoolean(false);
      }
    }
    return bytes.toByteArray();
  }
  public static String chatCommand(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return MinecraftInput.string(input, 256);
    }
  }
  public static TabRequest tabRequest(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      int id = MinecraftInput.varInt(input);
      String text = MinecraftInput.string(input, 32500);
      return new TabRequest(id, text);
    }
  }
  public static int packetId(byte[] packet) throws IOException {
    return MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(packet)));
  }
  public static byte[] idOnly(int id) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, id); }
    return bytes.toByteArray();
  }
  public record TabRequest(int transactionId, String text) {}
}

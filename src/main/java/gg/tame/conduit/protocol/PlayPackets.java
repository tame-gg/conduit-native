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
  public static byte[] knownPacks(ProtocolDefinition protocol) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KNOWN_PACKS));
      MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }
  public static byte[] resetChat(ProtocolDefinition protocol) throws IOException {
    return idOnly(protocol.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_RESET_CHAT));
  }
  public static byte[] configurationDisconnect(ProtocolDefinition protocol, String message) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_DISCONNECT));
      if (protocol.hasConfiguration()) NetworkNbt.stringComponent(output, message);
      else MinecraftOutput.string(output, "{\"text\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
    return bytes.toByteArray();
  }
  public static byte[] systemChat(ProtocolDefinition protocol, String message) throws IOException {
    return systemChat(protocol, gg.tame.conduit.api.text.Text.of(message == null ? "" : message));
  }
  public static byte[] systemChat(ProtocolDefinition protocol, gg.tame.conduit.api.text.Text message) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT));
      if (protocol.capabilities().legacyPlayChat()) {
        gg.tame.conduit.text.TextCodec.write(output, message == null ? gg.tame.conduit.api.text.Text.empty() : message, false);
        if (ProtocolEras.chatHasPosition(protocol.version().number())) output.writeByte(1); // system position
        if (ProtocolEras.chatHasSender(protocol.version().number())) {
          output.writeLong(0L); // nil UUID: the sender vanilla uses for system messages
          output.writeLong(0L);
        }
      } else {
        gg.tame.conduit.text.TextCodec.write(output, message == null ? gg.tame.conduit.api.text.Text.empty() : message, protocol.hasConfiguration());
        // 1.19 names the message's chat type by registry id, where 1 is "system"; from 1.19.1 the
        // field is a boolean that only says whether it belongs on the action bar.
        if (ProtocolEras.systemChatTypeId(protocol.version().number())) MinecraftOutput.varInt(output, 1);
        else output.writeBoolean(false);
      }
    }
    return bytes.toByteArray();
  }
  public static byte[] tabComplete(ProtocolDefinition protocol, int transactionId, int start, int length, List<String> matches) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE));
      if (!protocol.capabilities().commandTree()) {
        // Before 1.13's command tree the reply is the matches alone: no transaction to echo and no
        // range, because the client puts a match in place of the last word it typed.
        MinecraftOutput.varInt(output, matches.size());
        for (String match : matches) MinecraftOutput.string(output, match);
      } else {
        MinecraftOutput.varInt(output, transactionId);
        MinecraftOutput.varInt(output, start);
        MinecraftOutput.varInt(output, length);
        MinecraftOutput.varInt(output, matches.size());
        for (String match : matches) {
          MinecraftOutput.string(output, match);
          output.writeBoolean(false);
        }
      }
    }
    return bytes.toByteArray();
  }
  /** The matches in a Tab-Complete reply from before 1.13's command tree: a count, then strings. */
  public static List<String> legacyTabMatches(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      int count = MinecraftInput.varInt(input);
      if (count < 0 || count > 4096) throw new IOException("tab-complete reply with " + count + " matches");
      List<String> matches = new java.util.ArrayList<>(count);
      for (int index = 0; index < count; index++) matches.add(MinecraftInput.string(input, 32767));
      return matches;
    }
  }
  public static String chatCommand(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return MinecraftInput.string(input, 256);
    }
  }
  public static TabRequest tabRequest(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      // The transaction id came with 1.13's command tree. Before it the request opens with the text,
      // and what 1.8 and 1.12 append after it (a looked-at block, a command-block flag) is not needed.
      // Read as a transaction id, the text's length prefix sent the string read past the end of the
      // packet, and the exception ended the session on the client's first Tab press.
      int id = protocol.capabilities().commandTree() ? MinecraftInput.varInt(input) : -1;
      String text = MinecraftInput.string(input, 32500);
      return new TabRequest(id, text);
    }
  }
  public static int packetId(byte[] packet) throws IOException {
    return peekId(packet);
  }
  /** Reads a packet id without allocating streams. */
  public static int peekId(byte[] packet) throws IOException {
    int value = 0;
    int index = 0;
    for (int shift = 0; shift < 5; shift++) {
      if (index >= packet.length) throw new IOException("truncated packet id");
      int current = packet[index++] & 0xff;
      value |= (current & 0x7f) << (shift * 7);
      if ((current & 0x80) == 0) return value;
    }
    throw new IOException("VarInt exceeds five bytes");
  }
  /** The packet without its leading varint id. */
  public static byte[] body(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return input.readAllBytes();
    }
  }
  /** Re-frames a cached body under a different packet id, so it can be replayed into another state. */
  public static byte[] withId(int id, byte[] body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      output.write(body);
    }
    return bytes.toByteArray();
  }
  public static byte[] idOnly(int id) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) { MinecraftOutput.varInt(output, id); }
    return bytes.toByteArray();
  }
  public record TabRequest(int transactionId, String text) {}
}

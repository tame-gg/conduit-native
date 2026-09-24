// SPDX-License-Identifier: GPL-3.0-or-later
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
      if (ProtocolEras.textComponentNbt(protocol.version().number())) NetworkNbt.stringComponent(output, message);
      else MinecraftOutput.string(output, gg.tame.conduit.protocol.text.ComponentCodec.literalJson(message));
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
        gg.tame.conduit.text.TextCodec.write(output, message == null ? gg.tame.conduit.api.text.Text.empty() : message, protocol.version().number(), false);
        if (ProtocolEras.chatHasPosition(protocol.version().number())) output.writeByte(1); // system position
        if (ProtocolEras.chatHasSender(protocol.version().number())) {
          output.writeLong(0L); // nil UUID: the sender vanilla uses for system messages
          output.writeLong(0L);
        }
      } else {
        gg.tame.conduit.text.TextCodec.write(output, message == null ? gg.tame.conduit.api.text.Text.empty() : message, protocol.version().number());
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
  /**
   * The most bytes a chat line's 256 characters take in UTF-8. The limit is in characters: read as 256
   * bytes, a line of accented letters or emoji failed to parse.
   */
  public static final int CHAT_BYTES = 256 * 3;
  public static String chatCommand(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return MinecraftInput.string(input, CHAT_BYTES);
    }
  }
  /**
   * A 1.19.3+ serverbound chat message, as far as Conduit reads one: its text, whether the client
   * signed it, how many messages it acknowledges, and where the text ends -- everything after that is
   * kept byte for byte when the text is replaced.
   */
  public record SignedChat(String message, boolean signed, int acknowledged, int afterMessage) {}
  /**
   * A 1.19+ chat message in {@code protocol}'s layout. 1.19 to 1.19.2 sign with a length-prefixed
   * signature, empty when unsigned, and acknowledge nothing Conduit relays on its own, so their
   * {@code acknowledged} is 0.
   */
  public static SignedChat signedChat(int protocol, byte[] packet) throws IOException {
    if (ProtocolEras.chatSession(protocol)) return signedChat(packet);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      String message = MinecraftInput.string(input, CHAT_BYTES);
      int afterMessage = packet.length - input.available();
      input.readLong();                            // timestamp
      input.readLong();                            // salt
      boolean signed = MinecraftInput.bytes(input, 256).length > 0;
      return new SignedChat(message, signed, 0, afterMessage);
    }
  }
  /** Every release from 1.19.3 lays these fields out alike; what later ones append is not read. */
  public static SignedChat signedChat(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      String message = MinecraftInput.string(input, CHAT_BYTES);
      int afterMessage = packet.length - input.available();
      input.readLong();                            // timestamp
      input.readLong();                            // salt
      boolean signed = input.readBoolean();
      if (signed) input.skipNBytes(256);
      int acknowledged = MinecraftInput.varInt(input);
      return new SignedChat(message, signed, acknowledged, afterMessage);
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

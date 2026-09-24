// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.player.ChatSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the proxy reads and writes of 1.19+ secure chat. It reads the client's chat key, and it writes
 * chat, commands and deletions a client would send or be sent, unsigned: the proxy has no key of its
 * own and cannot sign for anyone.
 *
 * <p>Layouts are those of the published packet data (minecraft-data's protocol.json) for 1.19, 1.19.2,
 * 1.19.3 and 1.21.5, each of which later releases keep.
 */
public final class SecureChat {
  private SecureChat() {}

  private static final int PUBLIC_KEY_BYTES = 512;
  private static final int KEY_SIGNATURE_BYTES = 4096;
  private static final int MESSAGE_SIGNATURE_BYTES = 256;
  /** How many signed messages a 1.19.3+ acknowledgement covers: its fixed 20-bit set. */
  static final int WINDOW = 20;

  // ---- the client's chat key ------------------------------------------------------------------

  /** The profile key in a 1.19 to 1.19.2 Login Start (without its id); null when it carries none. */
  public static ChatSession loginKey(int protocol, byte[] body) throws IOException {
    if (!ProtocolEras.loginStartSignature(protocol)) return null;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      MinecraftInput.string(input, 16);
      if (!input.readBoolean()) return null;
      long expiresAt = input.readLong();
      return new ChatSession(null, expiresAt, MinecraftInput.bytes(input, PUBLIC_KEY_BYTES), MinecraftInput.bytes(input, KEY_SIGNATURE_BYTES));
    }
  }

  /** A 1.19.3+ Chat Session Update, as the client sends it once it is in the game. */
  public static ChatSession sessionUpdate(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      var sessionId = GameProfiles.readUuid(input);
      long expiresAt = input.readLong();
      return new ChatSession(sessionId, expiresAt, MinecraftInput.bytes(input, PUBLIC_KEY_BYTES), MinecraftInput.bytes(input, KEY_SIGNATURE_BYTES));
    }
  }

  // ---- what the client has seen -----------------------------------------------------------------

  /**
   * What a 1.19.3+ client packet says about the signed messages it has seen: how many new ones it has
   * taken in since it last said ({@code offset}), and which of the last 20 it acknowledges, bit 0 the
   * oldest. {@code acknowledged} is -1 for a Message Acknowledgment, which carries the offset alone.
   */
  public record Update(int offset, int acknowledged) {}

  /**
   * The acknowledgement a client's chat, command or Message Acknowledgment carries; null for any other
   * packet, for every packet before 1.19.3, and for one that ends early (a scripted client's bare
   * command), which acknowledges nothing the proxy could pass on.
   */
  public static Update update(ProtocolDefinition protocol, byte[] packet) throws IOException {
    int number = protocol.version().number();
    if (!ProtocolEras.chatSession(number)) return null;
    int id = PlayPackets.packetId(packet);
    var c2s = PacketDirection.CLIENT_TO_SERVER;
    boolean ack = protocol.is(ConnectionState.PLAY, c2s, id, PacketKind.PLAY_CHAT_ACKNOWLEDGEMENT);
    boolean chat = protocol.is(ConnectionState.PLAY, c2s, id, PacketKind.PLAY_CHAT);
    boolean command = protocol.is(ConnectionState.PLAY, c2s, id, PacketKind.PLAY_CHAT_COMMAND_SIGNED)
        || (!ProtocolEras.unsignedChatCommand(number) && protocol.is(ConnectionState.PLAY, c2s, id, PacketKind.PLAY_CHAT_COMMAND));
    if (!ack && !chat && !command) return null;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      if (ack) return new Update(MinecraftInput.varInt(input), -1);
      MinecraftInput.string(input, PlayPackets.CHAT_BYTES);
      input.readLong();                                          // timestamp
      input.readLong();                                          // salt
      if (chat) {
        if (input.readBoolean()) input.skipNBytes(MESSAGE_SIGNATURE_BYTES);
      } else {
        int arguments = MinecraftInput.varInt(input);
        if (arguments < 0 || arguments > 8) throw new IOException(arguments + " argument signatures");
        for (int index = 0; index < arguments; index++) {
          MinecraftInput.string(input, 64);
          input.skipNBytes(MESSAGE_SIGNATURE_BYTES);
        }
      }
      int offset = MinecraftInput.varInt(input);
      int bits = input.readUnsignedByte() | input.readUnsignedByte() << 8 | input.readUnsignedByte() << 16;
      return new Update(offset, bits);
    } catch (java.io.EOFException truncated) {
      return null;
    }
  }

  /** Whether this is a 1.19.3+ Player Chat the backend signed, which the client will count as seen. */
  public static boolean signedPlayerChat(ProtocolDefinition protocol, byte[] packet) throws IOException {
    int number = protocol.version().number();
    if (!ProtocolEras.chatSession(number)
        || !protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_PLAYER_CHAT)) {
      return false;
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      if (ProtocolEras.chatChecksum(number)) MinecraftInput.varInt(input);     // global index, 1.21.5+
      GameProfiles.readUuid(input);                                          // sender
      MinecraftInput.varInt(input);                                          // index in the sender's chain
      return input.readBoolean();
    } catch (IOException unreadable) {
      return false;                                                          // not the proxy's to judge
    }
  }

  /**
   * The 20 signed messages a 1.19.3+ backend holds this client to, as the backend tracks them: each
   * one the client acknowledged, one it was sent and has not yet said anything about, or none. It is
   * kept from what passes through the proxy, by the rules a vanilla server applies (an offset drops
   * that many of the oldest; an acknowledgement then keeps exactly the ones it names), so that
   * what the proxy writes on the client's behalf passes the backend's check and leaves the client's
   * own next message lining up.
   *
   * <p>That takes an offset of 0 -- the client's next offset still counts everything it has taken in
   * -- and acknowledging every message the backend already knows the client has: one it acknowledged
   * must stay acknowledged, and one it was sent must not be dropped, or the client acknowledging it
   * later is refused. The one case this cannot foresee is a client that ignores a message it was sent
   * (from a player it has blocked) and so never acknowledges it: acknowledged here first, the backend
   * then refuses the client's next message over it.
   */
  public static final class Window {
    private static final byte NONE = 0;
    private static final byte ACKNOWLEDGED = 1;
    private static final byte PENDING = 2;
    private final List<Byte> tracked = new ArrayList<>(Collections.nCopies(WINDOW, NONE));

    /** A new backend, which starts with nothing seen. */
    public synchronized void reset() {
      tracked.clear();
      tracked.addAll(Collections.nCopies(WINDOW, NONE));
    }
    /** The backend sent the client a signed message. */
    public synchronized void signedMessageSent() {
      // A backend disconnects a client that leaves 4096 unanswered; the proxy need not keep more.
      if (tracked.size() < WINDOW + 4096) tracked.add(PENDING);
    }
    /** The backend was sent this acknowledgement. */
    public synchronized void apply(Update update) {
      tracked.subList(0, Math.max(0, Math.min(update.offset(), tracked.size() - WINDOW))).clear();
      if (update.acknowledged() < 0) return;
      for (int index = 0; index < WINDOW; index++) {
        boolean kept = (update.acknowledged() >> index & 1) != 0 && tracked.get(index) != NONE;
        tracked.set(index, kept ? ACKNOWLEDGED : NONE);
      }
    }
    /**
     * The acknowledgement for a message the proxy writes, with offset 0: every message in the window
     * the backend knows the client has. Applying the message once it is sent counts them acknowledged.
     */
    public synchronized int acknowledgedForProxy() {
      int bits = 0;
      for (int index = 0; index < WINDOW; index++) {
        if (tracked.get(index) != NONE) bits |= 1 << index;
      }
      return bits;
    }
  }

  // ---- what the proxy writes ------------------------------------------------------------------

  /**
   * The unsigned chat line a 1.19+ client without a chat key sends, for {@code protocol}. From 1.19.1
   * to 1.19.2 it repeats {@code lastSeen}, the seen-messages list the client last sent (null for none
   * yet); from 1.19.3 it acknowledges {@code acknowledged} with an offset of 0 (see {@link Window}).
   */
  public static byte[] unsignedChat(ProtocolDefinition protocol, String message, int acknowledged, byte[] lastSeen) throws IOException {
    int number = protocol.version().number();
    return build(protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT), output -> {
      MinecraftOutput.string(output, message);
      output.writeLong(System.currentTimeMillis());
      output.writeLong(0L);                                      // salt, which only a signature uses
      if (ProtocolEras.chatSession(number)) {
        output.writeBoolean(false);                              // no signature
        acknowledgement(output, number, acknowledged);
      } else {
        MinecraftOutput.varInt(output, 0);                       // an empty signature
        output.writeBoolean(false);                              // no signed preview
        if (ProtocolEras.chatLastSeenList(number)) lastSeen(output, lastSeen);
      }
    });
  }

  /**
   * The command a 1.19 to 1.20.4 client sends with no argument signed, without its slash. From 1.20.5
   * such a command has a packet of its own, the command alone, which the caller writes instead.
   */
  public static byte[] unsignedCommand(ProtocolDefinition protocol, String command, int acknowledged, byte[] lastSeen) throws IOException {
    int number = protocol.version().number();
    return build(protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND), output -> {
      MinecraftOutput.string(output, command);
      output.writeLong(System.currentTimeMillis());
      output.writeLong(0L);
      MinecraftOutput.varInt(output, 0);                         // no argument signatures
      if (ProtocolEras.chatSession(number)) {
        acknowledgement(output, number, acknowledged);
      } else {
        output.writeBoolean(false);                              // no signed preview
        if (ProtocolEras.chatLastSeenList(number)) lastSeen(output, lastSeen);
      }
    });
  }

  private static void acknowledgement(DataOutputStream output, int protocol, int acknowledged) throws IOException {
    MinecraftOutput.varInt(output, 0);
    output.writeByte(acknowledged);
    output.writeByte(acknowledged >> 8);
    output.writeByte(acknowledged >> 16);
    if (ProtocolEras.chatChecksum(protocol)) output.writeByte(0);   // 0: the server skips the checksum
  }

  private static void lastSeen(DataOutputStream output, byte[] lastSeen) throws IOException {
    if (lastSeen != null) {
      output.write(lastSeen);
    } else {
      MinecraftOutput.varInt(output, 0);                         // nothing seen
      output.writeBoolean(false);                                // nothing refused
    }
  }

  /**
   * The seen-messages list that ends a 1.19.1-1.19.2 chat line or command, as the client wrote it (its
   * last-seen entries and its last refused message); null for any other packet or release.
   */
  public static byte[] lastSeenList(ProtocolDefinition protocol, byte[] packet) throws IOException {
    int number = protocol.version().number();
    if (!ProtocolEras.chatLastSeenList(number)) return null;
    int id = PlayPackets.packetId(packet);
    boolean chat = protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT);
    boolean command = protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT_COMMAND);
    if (!chat && !command) return null;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      MinecraftInput.string(input, PlayPackets.CHAT_BYTES);
      input.readLong();
      input.readLong();
      if (chat) {
        MinecraftInput.bytes(input, MESSAGE_SIGNATURE_BYTES);
      } else {
        int arguments = MinecraftInput.varInt(input);
        if (arguments < 0 || arguments > 8) throw new IOException(arguments + " argument signatures");
        for (int index = 0; index < arguments; index++) {
          MinecraftInput.string(input, 64);
          MinecraftInput.bytes(input, MESSAGE_SIGNATURE_BYTES);
        }
      }
      input.readBoolean();                                       // signed preview
      return input.readAllBytes();
    } catch (java.io.EOFException truncated) {
      return null;
    }
  }

  /**
   * Delete Chat for the message with this signature, which a 1.19.1+ client then shows as deleted. A
   * 1.19.3+ client names a message it has by its place in a cache or by the whole 256-byte signature;
   * the proxy knows no place, so it sends the signature.
   */
  public static byte[] deleteMessage(ProtocolDefinition protocol, byte[] signature) throws IOException {
    boolean session = ProtocolEras.chatSession(protocol.version().number());
    if (session && signature.length != MESSAGE_SIGNATURE_BYTES) {
      throw new IllegalArgumentException("a message signature is " + MESSAGE_SIGNATURE_BYTES + " bytes, not " + signature.length);
    }
    return build(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DELETE_MESSAGE), output -> {
      if (session) {
        MinecraftOutput.varInt(output, 0);                       // 0: the signature follows
        output.write(signature);
      } else {
        MinecraftOutput.bytes(output, signature);
      }
    });
  }

  private interface Body { void write(DataOutputStream output) throws IOException; }
  private static byte[] build(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      body.write(output);
    }
    return bytes.toByteArray();
  }
}

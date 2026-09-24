// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.player.ChatSession;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.TabListEntry;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.DisplayPackets;
import gg.tame.conduit.protocol.GameProfiles;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PlayerInfoUpdate;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.SecureChat;
import gg.tame.conduit.tests.LoginFlowTests.Proxy;
import gg.tame.conduit.tests.PlayerExtrasTests.ModernBackend;
import gg.tame.conduit.tests.PlayerExtrasTests.ModernClient;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;

/**
 * Secure chat: the client's chat key (Player.chatSession, Velocity's getIdentifiedKey), the backend's
 * record of the signed messages a client has seen, spoofed chat and commands for 1.19+ clients,
 * Delete Chat, chat sessions on tab-list entries, and signed messages sent through Adventure. The
 * same was run against real vanilla 1.19, 1.19.2, 1.20.4 and 26.2 backends with a scripted client.
 */
public final class SecureChatApiTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    packetIds();
    chatKeys();
    velocityKeys();
    theBackendsRecordOfWhatWasSeen();
    whatTheProxyWrites();
    chatSessionsOnTabListEntries();
    signedMessagesThroughAdventure();
    throughALiveProxy();
    System.out.println("SecureChatApiTests OK");
  }

  // --- ids ----------------------------------------------------------------------------------------

  private static void packetIds() {
    // minecraft-data (tools/packetids.py) through 26.1; Mojang's own reports for 26.2 and 26.3.
    idsAre(PacketKind.PLAY_CHAT_SESSION_UPDATE, PacketDirection.CLIENT_TO_SERVER,
        Map.of(760, -1, 761, 0x20, 762, 0x06, 765, 0x06, 766, 0x07, 768, 0x08, 771, 0x09, 775, 0x0A, 776, 0x0A, 777, 0x0A));
    idsAre(PacketKind.PLAY_CHAT_COMMAND_SIGNED, PacketDirection.CLIENT_TO_SERVER,
        Map.of(765, -1, 766, 0x05, 767, 0x05, 768, 0x06, 771, 0x07, 775, 0x08, 776, 0x08, 777, 0x08));
    // 1.19.1's acknowledgement is a list, not 1.19.3's count, so it has no kind.
    idsAre(PacketKind.PLAY_CHAT_ACKNOWLEDGEMENT, PacketDirection.CLIENT_TO_SERVER,
        Map.of(760, -1, 761, 0x03, 765, 0x03, 767, 0x03, 768, 0x04, 770, 0x04, 771, 0x05, 775, 0x06, 776, 0x06));
    idsAre(PacketKind.PLAY_DELETE_MESSAGE, PacketDirection.SERVER_TO_CLIENT,
        Map.of(759, -1, 760, 0x18, 761, 0x16, 763, 0x19, 765, 0x1A, 766, 0x1C, 770, 0x1B, 773, 0x1F, 776, 0x1F, 777, 0x1F));
    idsAre(PacketKind.PLAY_PLAYER_CHAT, PacketDirection.SERVER_TO_CLIENT,
        Map.of(761, 0x31, 762, 0x35, 765, 0x37, 766, 0x39, 768, 0x3B, 770, 0x3A, 773, 0x3F, 775, 0x41, 776, 0x41, 777, 0x42));
  }
  private static void idsAre(PacketKind kind, PacketDirection direction, Map<Integer, Integer> expected) {
    expected.forEach((protocol, id) -> {
      ProtocolDefinition definition = ProtocolDefinition.forVersion(protocol);
      int actual = definition.defines(ConnectionState.PLAY, direction, kind) ? definition.id(ConnectionState.PLAY, direction, kind) : -1;
      require(actual == id, kind + " on " + protocol + ": expected " + id + ", got " + actual);
    });
  }

  // --- the client's key ---------------------------------------------------------------------------

  private static void chatKeys() throws Exception {
    byte[] key = {1, 2, 3};
    byte[] signature = {9, 8};
    byte[] withKey = body(output -> {
      MinecraftOutput.string(output, "Signer");
      output.writeBoolean(true);
      output.writeLong(1234L);
      MinecraftOutput.bytes(output, key);
      MinecraftOutput.bytes(output, signature);
      output.writeBoolean(false);                   // 1.19.1's optional UUID
    });
    ChatSession login = SecureChat.loginKey(760, withKey);
    require(login != null && login.sessionId() == null && login.expiresAt() == 1234L
        && Arrays.equals(login.publicKey(), key) && Arrays.equals(login.keySignature(), signature), "a 1.19.2 Login Start's key");
    require(SecureChat.loginKey(760, body(output -> { MinecraftOutput.string(output, "Plain"); output.writeBoolean(false); output.writeBoolean(false); })) == null,
        "a Login Start without one");
    require(SecureChat.loginKey(761, withKey) == null, "1.19.3 has no key in its Login Start");

    UUID session = new UUID(5, 6);
    byte[] update = packet(0x06, output -> {
      GameProfiles.writeUuid(output, session);
      output.writeLong(99L);
      MinecraftOutput.bytes(output, key);
      MinecraftOutput.bytes(output, signature);
    });
    ChatSession played = SecureChat.sessionUpdate(update);
    require(played.sessionId().equals(session) && played.expiresAt() == 99L && Arrays.equals(played.publicKey(), key), "a Chat Session Update");
    key[0] = 7;
    require(played.publicKey()[0] == 1, "the key is the session's own copy");
  }

  private static void velocityKeys() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    ChatSession session = new ChatSession(new UUID(1, 2), 1_700_000_000_000L, pair.getPublic().getEncoded(), new byte[] {4, 4});
    Class<?> type = Class.forName("gg.tame.conduit.compat.velocity.VelocityIdentifiedKey");
    Method of = type.getDeclaredMethod("of", ChatSession.class, UUID.class, int.class);
    of.setAccessible(true);
    UUID holder = new UUID(3, 4);
    var key = (com.velocitypowered.api.proxy.crypto.IdentifiedKey) of.invoke(null, session, holder, 761);
    require(key.getSignedPublicKey().equals(pair.getPublic()) && key.getSignatureHolder().equals(holder)
        && key.getExpiryTemporal().toEpochMilli() == 1_700_000_000_000L && Arrays.equals(key.getSignature(), new byte[] {4, 4}), "the key as sent");
    require(key.getKeyRevision() == com.velocitypowered.api.proxy.crypto.IdentifiedKey.Revision.LINKED_V2, "1.19.3's keys name their holder");
    require(key.getSigner() == null && !key.isSignatureValid() && key.hasExpired(), "and are not vouched for by the proxy");
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(pair.getPrivate());
    signer.update(new byte[] {1, 2});
    signer.update(new byte[] {3});
    byte[] signed = signer.sign();
    require(key.verifyDataSignature(signed, new byte[] {1, 2}, new byte[] {3}), "a signature the key made");
    require(!key.verifyDataSignature(signed, new byte[] {1, 2, 4}), "not over other data");
    require(!key.verifyDataSignature(new byte[] {1}, new byte[] {1, 2}), "nor a malformed one");
    var generic = (com.velocitypowered.api.proxy.crypto.IdentifiedKey) of.invoke(null, session, holder, 759);
    require(generic.getKeyRevision() == com.velocitypowered.api.proxy.crypto.IdentifiedKey.Revision.GENERIC_V1, "1.19's do not");
    require(of.invoke(null, new ChatSession(null, 0, new byte[] {1, 2}, new byte[0]), holder, 760) == null, "a key that is not one is none");
    require(of.invoke(null, null, holder, 760) == null, "no key, no key");
  }

  // --- what the backend holds the client to -------------------------------------------------------

  private static void theBackendsRecordOfWhatWasSeen() {
    SecureChat.Window window = new SecureChat.Window();
    require(window.acknowledgedForProxy() == 0, "nothing seen");
    window.signedMessageSent();
    window.signedMessageSent();
    require(window.acknowledgedForProxy() == 0, "sent but not yet taken into the window: an offset of 0 does not reach them");
    // The client takes both in: offset 2, the newest at the top of the 20 bits.
    int newest = 1 << 18 | 1 << 19;
    window.apply(new SecureChat.Update(2, newest));
    require(window.acknowledgedForProxy() == newest, "what it acknowledged stays acknowledged");
    window.signedMessageSent();
    window.apply(new SecureChat.Update(1, -1));
    require(window.acknowledgedForProxy() == (1 << 17 | 1 << 18 | 1 << 19),
        "a Message Acknowledgment moves the window: the new message is in it, pending, and must not be dropped");
    window.apply(new SecureChat.Update(0, 1 << 19));
    require(window.acknowledgedForProxy() == 1 << 19, "a message the client left out is gone from the backend's record too");
    window.apply(new SecureChat.Update(50, 0));
    require(window.acknowledgedForProxy() == 0, "an offset past what was sent drops no more than there is");
    window.signedMessageSent();
    window.reset();
    window.apply(new SecureChat.Update(1, 1 << 19));
    require(window.acknowledgedForProxy() == 0, "a new backend starts with nothing");
  }

  // --- what the proxy writes ----------------------------------------------------------------------

  private static void whatTheProxyWrites() throws Exception {
    for (int protocol : List.of(761, 765, 766, 770, 776)) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(protocol);
      byte[] chat = SecureChat.unsignedChat(p, "hé", 1 << 19 | 1, null);
      require(id(chat) == p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT), protocol + ": the chat id");
      var line = PlayPackets.signedChat(protocol, chat);
      require(line.message().equals("hé") && !line.signed() && line.acknowledged() == 0, protocol + ": unsigned, offset 0");
      var update = SecureChat.update(p, chat);
      require(update.offset() == 0 && update.acknowledged() == (1 << 19 | 1), protocol + ": the acknowledgement it was given");
      try (var input = new DataInputStream(new ByteArrayInputStream(chat))) {
        MinecraftInput.varInt(input);
        MinecraftInput.string(input, 1024);
        input.skipNBytes(8 + 8 + 1 + 1 + 3);
        require(input.available() == (protocol >= 770 ? 1 : 0), protocol + ": a checksum byte from 1.21.5 only");
        if (protocol >= 770) require(input.readByte() == 0, "0, which the server does not check");
      }
    }
    ProtocolDefinition p765 = ProtocolDefinition.forVersion(765);
    byte[] command = SecureChat.unsignedCommand(p765, "spawn now", 1 << 19, null);
    require(PlayPackets.chatCommand(command).equals("spawn now"), "a 1.20.4 command, without its slash");
    var commandUpdate = SecureChat.update(p765, command);
    require(commandUpdate.offset() == 0 && commandUpdate.acknowledged() == 1 << 19, "with no signatures and the acknowledgement");
    require(SecureChat.update(ProtocolDefinition.forVersion(766), PlayPackets.withId(0x04, new byte[] {1, 'x'})) == null,
        "1.20.5's unsigned command acknowledges nothing");

    ProtocolDefinition p760 = ProtocolDefinition.forVersion(760);
    byte[] old = SecureChat.unsignedChat(p760, "hi", 0, null);
    var oldLine = PlayPackets.signedChat(760, old);
    require(oldLine.message().equals("hi") && !oldLine.signed(), "1.19.2 chat, unsigned");
    require(Arrays.equals(SecureChat.lastSeenList(p760, old), new byte[] {0, 0}), "seeing nothing and refusing nothing");
    byte[] seen = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 7, 7, 0};
    byte[] repeated = SecureChat.unsignedCommand(p760, "list", 0, seen);
    require(PlayPackets.chatCommand(repeated).equals("list") && Arrays.equals(SecureChat.lastSeenList(p760, repeated), seen),
        "a 1.19.2 command repeating the list the client last sent");
    byte[] first = SecureChat.unsignedChat(ProtocolDefinition.forVersion(759), "hey", 0, null);
    require(PlayPackets.signedChat(759, first).message().equals("hey") && SecureChat.lastSeenList(ProtocolDefinition.forVersion(759), first) == null,
        "1.19 has no list");
    String wide = "é".repeat(256);
    require(PlayPackets.signedChat(765, SecureChat.unsignedChat(p765, wide, 0, null)).message().equals(wide),
        "256 characters that take 512 bytes are a chat line too");

    byte[] signature = new byte[256];
    signature[0] = 42;
    byte[] delete = SecureChat.deleteMessage(ProtocolDefinition.forVersion(776), signature);
    require(delete.length == 1 + 1 + 256 && delete[0] == 0x1F && delete[1] == 0 && delete[2] == 42, "26.2: id 0, then the whole signature");
    byte[] deleteOld = SecureChat.deleteMessage(p760, new byte[] {5, 6});
    require(Arrays.equals(deleteOld, new byte[] {0x18, 2, 5, 6}), "1.19.2: the signature with its length");
    boolean refused = false;
    try { SecureChat.deleteMessage(p765, new byte[3]); } catch (IllegalArgumentException expected) { refused = true; }
    require(refused, "a 1.19.3+ signature is 256 bytes");

    byte[] signedChat = packet(0x37, output -> {
      GameProfiles.writeUuid(output, new UUID(1, 1));
      MinecraftOutput.varInt(output, 0);
      output.writeBoolean(true);
      output.write(new byte[256]);
    });
    require(SecureChat.signedPlayerChat(p765, signedChat), "a signed Player Chat");
    require(!SecureChat.signedPlayerChat(p765, packet(0x37, output -> {
      GameProfiles.writeUuid(output, new UUID(1, 1)); MinecraftOutput.varInt(output, 0); output.writeBoolean(false);
    })), "an unsigned one is not counted");
    require(SecureChat.signedPlayerChat(ProtocolDefinition.forVersion(776), packet(0x41, output -> {
      MinecraftOutput.varInt(output, 300);            // 1.21.5+'s global index
      GameProfiles.writeUuid(output, new UUID(1, 1)); MinecraftOutput.varInt(output, 0); output.writeBoolean(true);
    })), "26.2 opens with a global index");
  }

  // --- tab list ------------------------------------------------------------------------------------

  private static void chatSessionsOnTabListEntries() throws Exception {
    UUID sessionId = new UUID(7, 7);
    ChatSession session = new ChatSession(sessionId, 55L, new byte[] {1, 2}, new byte[] {3});
    TabListEntry entry = new TabListEntry(new UUID(9, 9), "Chatty", List.of(), null, 0, 0, true, 0, true, session);
    byte[] add = DisplayPackets.tabListAdd(ProtocolDefinition.forVersion(765), List.of(entry)).orElseThrow();
    try (var input = new DataInputStream(new ByteArrayInputStream(add))) {
      MinecraftInput.varInt(input);
      int actions = input.readUnsignedByte();
      require((actions & PlayerInfoUpdate.INITIALIZE_CHAT) != 0, "Initialize Chat, for an entry with a session");
      require(MinecraftInput.varInt(input) == 1 && GameProfiles.readUuid(input).equals(entry.id()), "one entry");
      MinecraftInput.string(input, 16);
      MinecraftInput.varInt(input);                   // no properties
      require(input.readBoolean() && GameProfiles.readUuid(input).equals(sessionId) && input.readLong() == 55L
          && Arrays.equals(MinecraftInput.bytes(input, 512), new byte[] {1, 2}) && Arrays.equals(MinecraftInput.bytes(input, 4096), new byte[] {3}),
          "its session, id, expiry, key and signature");
    }
    TabListEntry plain = new TabListEntry(new UUID(8, 8), "Quiet", List.of(), null, 0, 0, true, 0, true);
    byte[] without = DisplayPackets.tabListAdd(ProtocolDefinition.forVersion(765), List.of(plain)).orElseThrow();
    require((without[1] & PlayerInfoUpdate.INITIALIZE_CHAT) == 0, "no Initialize Chat without one, which would take one away");
    byte[] old = DisplayPackets.tabListAdd(ProtocolDefinition.forVersion(760), List.of(entry)).orElseThrow();
    require(old[old.length - 1] == 3 && old[old.length - 2] == 1 && old[old.length - 3] == 2 && old[old.length - 5] == 2,
        "1.19.2: the entry's profile key at the end (expiry, key, signature)");
  }

  // --- Adventure -------------------------------------------------------------------------------------

  private static void signedMessagesThroughAdventure() throws Exception {
    Method chat = Class.forName("gg.tame.conduit.compat.velocity.Texts").getDeclaredMethod("chat", SignedMessage.class, ChatType.Bound.class);
    chat.setAccessible(true);
    SignedMessage message = SignedMessage.system("hello", null);
    var line = (TranslatableComponent) chat.invoke(null, message, ChatType.CHAT.bind(Component.text("Alex")));
    require(line.key().equals("chat.type.text") && line.arguments().get(0).asComponent().equals(Component.text("Alex"))
        && line.arguments().get(1).asComponent().equals(Component.text("hello")), "chat as vanilla decorates it");
    var whisper = (TranslatableComponent) chat.invoke(null, SignedMessage.system("psst", Component.text("PSST")),
        ChatType.MSG_COMMAND_OUTGOING.bind(Component.text("Alex"), Component.text("Sam")));
    require(whisper.key().equals("commands.message.display.outgoing") && whisper.arguments().get(0).asComponent().equals(Component.text("Sam"))
        && whisper.arguments().get(1).asComponent().equals(Component.text("PSST")), "a whisper names its target, and unsigned content wins");
  }

  // --- a live proxy ----------------------------------------------------------------------------------

  private static void throughALiveProxy() throws Exception {
    try (ModernBackend lobby = new ModernBackend(765);
         Proxy proxy = new Proxy(List.of(lobby.server()), AuthenticationSettings.offline(), null, null, ForwardingMode.NONE, Optional.empty());
         ModernClient client = ModernClient.open(765, proxy.port(), "Keyed")) {
      Player player = client.playing(proxy);
      require(player.chatSession().isEmpty(), "no key before the client sends one");
      KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
      byte[] update = packet(0x06, output -> {
        GameProfiles.writeUuid(output, new UUID(11, 12));
        output.writeLong(4_000_000_000_000L);
        MinecraftOutput.bytes(output, pair.getPublic().getEncoded());
        MinecraftOutput.bytes(output, new byte[] {1, 2, 3});
      });
      client.send(update);
      require(lobby.await(packet -> Arrays.equals(packet, update)), "the Chat Session Update reached the backend as it was sent");
      require(waitFor(() -> player.chatSession().isPresent(), 5_000), "and the proxy kept the key");
      require(player.chatSession().get().sessionId().equals(new UUID(11, 12)), "with its session id");

      // Two signed messages from the backend, which the client takes in with a Message Acknowledgment.
      for (int index = 0; index < 2; index++) {
        int at = index;
        lobby.send("Keyed", packet(0x37, output -> {
          GameProfiles.writeUuid(output, new UUID(1, 1));
          MinecraftOutput.varInt(output, at);
          output.writeBoolean(true);
          output.write(new byte[256]);
        }));
      }
      require(client.await(ConnectionState.PLAY, PacketKind.PLAY_PLAYER_CHAT, 2).size() == 2, "the client got both");
      client.send(new byte[] {0x03, 2});
      require(lobby.await(packet -> Arrays.equals(packet, new byte[] {0x03, 2})), "its acknowledgement went on");
      require(player.spoofChatInput("for you"), "spoofed chat");
      int chatId = lobby.p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT);
      require(lobby.await(packet -> id(packet) == chatId), "reached the backend");
      byte[] spoofed = lobby.received(packet -> id(packet) == chatId).getFirst();
      var acknowledgement = SecureChat.update(lobby.p, spoofed);
      require(!PlayPackets.signedChat(765, spoofed).signed() && acknowledgement.offset() == 0
          && acknowledgement.acknowledged() == (1 << 18 | 1 << 19), "unsigned, acknowledging the two messages the backend knows the client has");

      require(player.deleteChatMessage(new byte[256]), "Delete Chat sent");
      byte[] deleted = client.await(ConnectionState.PLAY, PacketKind.PLAY_DELETE_MESSAGE, 1).getFirst();
      require(deleted.length == 258 && deleted[1] == 0, "by signature");
      require(proxy.runtime.player("Keyed").isPresent(), "and the player is still here");
    }
  }

  private interface Body { void write(java.io.DataOutputStream output) throws Exception; }
  private static byte[] body(Body body) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) { body.write(output); }
    return bytes.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

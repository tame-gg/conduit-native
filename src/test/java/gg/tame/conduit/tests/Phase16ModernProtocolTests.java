// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.protocol.CodecStatus;
import gg.tame.conduit.protocol.CompatibilityCompleteness;
import gg.tame.conduit.protocol.CompatibilityEntry;
import gg.tame.conduit.protocol.CompatibilityRegistry;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.LoginSuccess;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolCapabilities;
import gg.tame.conduit.protocol.ProtocolCatalog;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolFamily;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.protocol.ProtocolVersion;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.codec.SemanticCodec;
import gg.tame.conduit.protocol.diff.ProtocolDifferenceDatabase;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.semantic.SemanticChat;
import gg.tame.conduit.protocol.semantic.SemanticCommandNode;
import gg.tame.conduit.protocol.semantic.SemanticEntityMetadata;
import gg.tame.conduit.protocol.semantic.SemanticItemStack;
import gg.tame.conduit.protocol.semantic.SemanticRegistry;
import gg.tame.conduit.login.LoginPipeline;
import java.util.List;
import java.util.UUID;

/** Modern protocol compatibility program — catalog, 1.13 codec, capabilities, registry. */
public final class Phase16ModernProtocolTests {
  private Phase16ModernProtocolTests() {}

  public static void run() throws Exception {
    catalogCoverage();
    legacyOutOfScope();
    families();
    codecSupportBoundaries();
    capabilities113();
    states113();
    packetIds113();
    loginStart113();
    loginSuccess113();
    loginToPlayWithoutConfiguration();
    semanticCodec113();
    compatibilityRegistry();
    differenceDatabase();
    semanticFoundations();
    systemChat113();
    textIsJsonUntil1203();
    System.out.println("Phase16ModernProtocolTests passed.");
  }

  private static void catalogCoverage() {
    require(ProtocolCatalog.findRelease("1.13").orElseThrow().protocol() == 393, "1.13=393");
    require(ProtocolCatalog.findRelease("1.16.5").orElseThrow().protocol() == 754, "1.16.5=754");
    require(ProtocolCatalog.findRelease("1.20").orElseThrow().protocol() == 763, "1.20=763");
    require(ProtocolCatalog.findRelease("1.20.1").orElseThrow().protocol() == 763, "1.20.1 shares 763");
    require(ProtocolCatalog.findRelease("1.20.4").orElseThrow().protocol() == 765, "1.20.4=765");
    require(ProtocolCatalog.findRelease("1.20.6").orElseThrow().protocol() == 766, "1.20.6=766");
    require(ProtocolCatalog.findRelease("1.21.11").orElseThrow().protocol() == 774, "1.21.11=774");
    require(ProtocolCatalog.findRelease("26.2").orElseThrow().protocol() == 776, "26.2=776");
    require(ProtocolCatalog.modernReleases().size() >= 40, "modern releases");
    require(ProtocolCatalog.find(393).orElseThrow().displayName().equals("1.13"), "catalog 393");
  }

  private static void legacyOutOfScope() {
    require(ProtocolCatalog.findRelease("1.12.2").orElseThrow().legacyOutOfScope(), "1.12.2 out of scope");
    require(ProtocolFamily.ofProtocol(340) == ProtocolFamily.LEGACY_OUT_OF_SCOPE, "340 legacy family");
    require(!ProtocolCatalog.inModernProgram(340), "340 not modern");
    require(ProtocolCatalog.inModernProgram(393), "393 modern");
    // Out of the modern program and having a table are different claims, and 1.12.2 now makes
    // both at once: Conduit does not translate it, but it holds enough of its table to admit the
    // client and let Via do that. The pairing is the point -- "out of scope" is about whose
    // translators carry the version, not about whether it can connect.
    require(ProtocolDefinition.hasCodec(340), "1.12.2 has an admission table");
    require(ProtocolDefinition.codecStatus(340) == gg.tame.conduit.protocol.CodecStatus.DECLARED,
        "1.12.2 table is declared, not verified");
  }

  private static void families() {
    require(ProtocolVersion.MINECRAFT_1_13.family() == ProtocolFamily.V1_13, "1.13 family");
    require(ProtocolVersion.MINECRAFT_1_20_4.family() == ProtocolFamily.V1_20, "1.20 family");
    require(ProtocolVersion.MINECRAFT_1_21.family() == ProtocolFamily.V1_21, "1.21 family");
    require(ProtocolVersion.MINECRAFT_26_2.family() == ProtocolFamily.V26, "26 family");
  }

  private static void codecSupportBoundaries() {
    require(ProtocolDefinition.hasCodec(393), "1.13 codec");
    require(ProtocolDefinition.hasCodec(765), "765 codec");
    require(ProtocolCatalog.withCodecs().stream().anyMatch(v -> v.number() == 393), "codec list includes 393");

    // Declared tables are authored directly for their protocol; derived tables
    // inherit a declared table plus that release's delta. Both are usable
    // codecs, and the distinction must stay visible rather than collapsing
    // into a single "supported" bit.
    // 393 is the one protocol exercised end-to-end by a real client against a
    // real server of the same version, so it is the only VERIFIED codec.
    require(ProtocolDefinition.codecStatus(393) == CodecStatus.VERIFIED, "393 verified on the wire");
    require(ProtocolDefinition.codecStatus(765) == CodecStatus.DECLARED, "765 declared, not wire-verified");
    require(ProtocolDefinition.codecStatus(401) == CodecStatus.DERIVED, "401 derived from 393");
    require(ProtocolDefinition.codecStatus(764) == CodecStatus.DERIVED, "764 derived");
    require(ProtocolDefinition.codecStatus(767) == CodecStatus.DERIVED, "767 derived");

    // A catalog entry is still not a codec. 1.14.2 has no published packet data
    // to derive from and legacy versions are out of the program, so both remain
    // known identities with no packet table at all.
    require(!ProtocolDefinition.hasCodec(485), "1.14.2 catalog only, no published data to derive");
    require(ProtocolDefinition.codecStatus(485) == CodecStatus.NONE, "485 has no codec");
    require(ProtocolDefinition.hasCodec(47), "1.8.x has an admission table");
    require(ProtocolDefinition.codecStatus(47) == CodecStatus.DECLARED, "47 table is declared");
    // 1.14.2 is the shape the pre-flattening tables are not: a catalog identity with nothing
    // behind it. Keeping both cases here is what stops "in the catalog" and "has a table" from
    // collapsing into one idea again.
    require(!ProtocolDefinition.hasCodec(485), "485 is still catalog-only");

    // Having a codec on both sides never by itself implies a translation path.
    require(ProtocolDefinition.hasCodec(401) && ProtocolDefinition.hasCodec(765), "both have codecs");
    require(ProtocolCompatibility.between(401, 765) == TranslationSupport.UNSUPPORTED,
        "401->765 has codecs but no translator");
  }

  private static void capabilities113() {
    ProtocolCapabilities caps = ProtocolDefinition.forVersion(393).capabilities();
    require(!caps.configurationPhase(), "no configuration");
    require(!caps.loginStartUuid(), "login start username only");
    require(!caps.loginSuccessBinaryUuid(), "login success string uuid");
    require(!caps.loginSuccessProperties(), "no login success properties");
    require(caps.encryption() && caps.compression(), "encryption+compression");
    require(caps.pluginMessages() && caps.loginPluginMessage(), "plugin messages");
    require(caps.legacyPlayChat(), "legacy chat");
    require(!caps.knownPacks() && !caps.cookiePackets() && !caps.transferPackets(), "no modern config features");
    require(caps.named().contains("LEGACY_PLAY_CHAT"), "named caps");
  }

  private static void states113() {
    ProtocolDefinition v = ProtocolDefinition.forVersion(393);
    require(!v.hasConfiguration(), "no config state");
    require(v.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), "play login");
    require(!v.defines(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH),
        "no finish configuration");
    require(!v.defines(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED),
        "no login ack");
  }

  private static void packetIds113() throws Exception {
    ProtocolDefinition v = ProtocolDefinition.forVersion(393);
    require(v.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE) == 0x21, "keepalive s2c");
    require(v.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE) == 0x0E, "keepalive c2s");
    require(v.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN) == 0x25, "join game");
    require(v.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE) == 0x19, "plugin s2c");
    require(v.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE) == 0x0A, "plugin c2s");
    require(v.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION) == 0x10, "position");
    require(v.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND) == 0x02, "chat");
    require(v.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT) == 0x1B, "disconnect");
  }

  private static void loginStart113() throws Exception {
    PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), "Steve", List.of(), false);
    byte[] encoded = LoginStart.encode(profile, ProtocolDefinition.forVersion(393));
    require(PlayPackets.packetId(encoded) == 0, "login start id");
    byte[] body = PlayPackets.body(encoded);
    LoginStart decoded = LoginStart.decode(body, ProtocolDefinition.forVersion(393));
    require(decoded.username().equals("Steve"), "username");
    require(decoded.clientUuid().equals(LoginStart.offlineUuid("Steve")), "offline uuid derived");
    byte[] modern = LoginStart.encode(profile, ProtocolDefinition.forVersion(765));
    require(PlayPackets.body(modern).length > body.length, "modern login start longer");
    loginStartOptionalFields();
  }

  /**
   * 1.19 to 1.20.1, whose Login Start fields after the username are optional. Each release's own
   * bytes, as a real client sends them: every one was refused as "unsupported extra fields".
   */
  private static void loginStartOptionalFields() throws Exception {
    UUID id = UUID.fromString("22222222-3333-4444-5555-666666666666");
    PlayerProfile profile = new PlayerProfile(id, "Steve", List.of(), false);
    byte[] key = new byte[294];
    byte[] signature = new byte[512];
    // 1.19: username, then a present signature (expiry, public key, Mojang's signature).
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.string(out, "Steve");
      out.writeBoolean(true); out.writeLong(1234L);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(out, key.length); out.write(key);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(out, signature.length); out.write(signature);
    }
    LoginStart signed119 = LoginStart.decode(bytes.toByteArray(), ProtocolDefinition.forVersion(759));
    require(signed119.username().equals("Steve") && signed119.clientUuid().equals(LoginStart.offlineUuid("Steve")), "1.19 signed login start");
    // 1.19.2: no signature, a present UUID.
    bytes.reset();
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.string(out, "Steve");
      out.writeBoolean(false);
      out.writeBoolean(true); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    require(LoginStart.decode(bytes.toByteArray(), ProtocolDefinition.forVersion(760)).clientUuid().equals(id), "1.19.2 login start keeps its uuid");
    // 1.19.3-1.20.1: username and an optional UUID, present or absent.
    bytes.reset();
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.string(out, "Steve");
      out.writeBoolean(true); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    for (int protocol : new int[] {761, 762, 763}) {
      require(LoginStart.decode(bytes.toByteArray(), ProtocolDefinition.forVersion(protocol)).clientUuid().equals(id), protocol + " login start keeps its uuid");
    }
    byte[] absent = java.util.Arrays.copyOf(bytes.toByteArray(), 6 + 1);
    absent[6] = 0;
    require(LoginStart.decode(absent, ProtocolDefinition.forVersion(763)).clientUuid().equals(LoginStart.offlineUuid("Steve")), "1.20.1 without uuid");
    // What Conduit sends a backend of each release parses back as that release reads it.
    int[] lengths = {759, 7, 760, 24, 761, 23, 762, 23, 763, 23};
    for (int i = 0; i < lengths.length; i += 2) {
      ProtocolDefinition definition = ProtocolDefinition.forVersion(lengths[i]);
      byte[] body = PlayPackets.body(LoginStart.encode(profile, definition));
      require(body.length == lengths[i + 1], lengths[i] + " login start is " + lengths[i + 1] + " bytes, got " + body.length);
      LoginStart back = LoginStart.decode(body, definition);
      require(back.username().equals("Steve"), lengths[i] + " round trip username");
      require(back.clientUuid().equals(lengths[i] == 759 ? LoginStart.offlineUuid("Steve") : id), lengths[i] + " round trip uuid");
    }
  }

  private static void loginSuccess113() throws Exception {
    ProtocolDefinition v = ProtocolDefinition.forVersion(393);
    PlayerProfile profile = new PlayerProfile(UUID.fromString("11111111-1111-1111-1111-111111111111"), "Alex", List.of(), true);
    byte[] packet = LoginSuccess.encode(v, profile);
    require(v.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.LOGIN_SUCCESS), "id");
    require(LoginSuccess.peekUuid(v, packet).equals(profile.uniqueId()), "uuid roundtrip");
    byte[] rewritten = LoginSuccess.replaceProfile(v, packet, profile);
    require(LoginSuccess.peekUuid(v, rewritten).equals(profile.uniqueId()), "replace keeps uuid");
  }

  private static void loginToPlayWithoutConfiguration() throws Exception {
    ProtocolSession session = new ProtocolSession();
    session.acceptHandshake(2);
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(393);
    LoginPipeline pipeline = new LoginPipeline(session, protocol);
    byte[] loginStart = LoginStart.encode(new PlayerProfile(LoginStart.offlineUuid("Bob"), "Bob", List.of(), false), protocol);
    pipeline.observe(PacketDirection.CLIENT_TO_SERVER, loginStart);
    pipeline.observe(PacketDirection.SERVER_TO_CLIENT, LoginSuccess.encode(protocol,
        new PlayerProfile(LoginStart.offlineUuid("Bob"), "Bob", List.of(), false)));
    require(session.state() == ConnectionState.PLAY, "1.13 login success → play");
  }

  private static void semanticCodec113() throws Exception {
    ProtocolDefinition v = ProtocolDefinition.forVersion(393);
    SemanticCodec codec = new SemanticCodec(v, 4096);
    KeepAlivePacket keep = new KeepAlivePacket(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, 99L);
    byte[] encoded = codec.encode(keep);
    require(PlayPackets.packetId(encoded) == 0x21, "keepalive id");
    KeepAlivePacket decoded = (KeepAlivePacket) codec.decode(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, encoded);
    require(decoded.id() == 99L, "keepalive value");
    PluginMessagePacket plugin = new PluginMessagePacket(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
        "minecraft:brand", PluginMessage.brandPayload("conduit"));
    byte[] pluginWire = codec.encode(plugin);
    require(PlayPackets.packetId(pluginWire) == 0x19, "plugin id");
  }

  private static void compatibilityRegistry() {
    CompatibilityEntry same = CompatibilityRegistry.resolve(393, 393);
    require(same.support() == TranslationSupport.DIRECT, "393 direct");
    // Promoted from PARTIAL once the official 1.13 client sustained real play
    // against the official 1.13 server through Conduit. Native 393 being FULL
    // says nothing about 393 -> 765, which stays PARTIAL below.
    require(same.completeness() == CompatibilityCompleteness.FULL, "393 native verified");
    CompatibilityEntry cross = CompatibilityRegistry.resolve(393, 765);
    require(cross.support() == TranslationSupport.TRANSLATED, "393→765 translated");
    require(cross.completeness() == CompatibilityCompleteness.PARTIAL, "393→765 partial");
    require(cross.selectable(), "393→765 selectable");
    CompatibilityEntry t = CompatibilityRegistry.resolve(765, 766);
    require(t.support() == TranslationSupport.TRANSLATED && t.completeness() == CompatibilityCompleteness.PARTIAL, "765↔766");
    require(ProtocolCompatibility.between(393, 393) == TranslationSupport.DIRECT, "compat helper");
    require(ProtocolCompatibility.between(393, 765) == TranslationSupport.TRANSLATED, "393↔765 translated");
  }

  private static void differenceDatabase() {
    require(!ProtocolDifferenceDatabase.between(393, 765).isEmpty(), "393→765 diffs");
    require(ProtocolDifferenceDatabase.between(393, 765).stream()
        .anyMatch(c -> c.packetOrArea().equals("CONFIGURATION")), "config boundary documented");
  }

  private static void semanticFoundations() {
    require(SemanticItemStack.of("minecraft:stone", 64).count() == 64, "item");
    require(SemanticItemStack.empty().isEmpty(), "air");
    SemanticEntityMetadata meta = new SemanticEntityMetadata().put("custom_name", SemanticEntityMetadata.MetadataType.COMPONENT, "x");
    require(meta.get("custom_name").isPresent(), "metadata");
    require(SemanticChat.system("hi").kind() == SemanticChat.ChatKind.SYSTEM, "chat");
    require(new SemanticRegistry("minecraft:dimension_type").put("minecraft:overworld", new byte[]{1}).get("minecraft:overworld").isPresent(), "registry");
    require(new SemanticCommandNode("server", SemanticCommandNode.NodeType.LITERAL, true).executable(), "command");
  }

  private static void systemChat113() throws Exception {
    byte[] packet = PlayPackets.systemChat(ProtocolDefinition.forVersion(393), "hello");
    require(PlayPackets.packetId(packet) == 0x0E, "chat id");
    byte[] body = PlayPackets.body(packet);
    require(body[body.length - 1] == 1, "system position byte");
  }

  /**
   * 1.20.2 has a Configuration phase but still sends text as a JSON string; NBT text came with 1.20.3.
   * A real 1.20.2 client read Conduit's NBT reply to /conduit as a string and lost the connection.
   */
  private static void textIsJsonUntil1203() throws Exception {
    ProtocolDefinition v1202 = ProtocolDefinition.forVersion(764);
    require(jsonText(PlayPackets.systemChat(v1202, "hello")).contains("hello"), "1.20.2 system chat is a JSON string");
    require(jsonText(PlayPackets.configurationDisconnect(v1202, "bye")).contains("bye"), "1.20.2 configuration disconnect is a JSON string");
    byte[] kick = gg.tame.conduit.protocol.codec.JoinGameCodec.encodeDisconnect(v1202,
        new gg.tame.conduit.protocol.semantic.DisconnectPacket(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, "bye"));
    require(jsonText(kick).contains("bye"), "1.20.2 play disconnect is a JSON string");
    require(PlayPackets.body(PlayPackets.systemChat(ProtocolDefinition.forVersion(765), "hello"))[0] == 10, "1.20.3 system chat is an NBT compound");
  }

  private static String jsonText(byte[] packet) throws Exception {
    try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(packet)))) {
      String json = gg.tame.conduit.protocol.MinecraftInput.string(in, 262144);
      require(json.startsWith("{"), "expected JSON text, got " + json);
      return json;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

package gg.tame.conduit.tests;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.protocol.CompatibilityProbe;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.LegacyWorldReload;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;

/**
 * The compatibility probe, and the one piece of switching that cannot be seen from it.
 *
 * <p>The probe is the single place Conduit decides what a client/backend pair is, so what these
 * assert is that each engine setting produces the answer that setting promises, and that a pair
 * with no path says so rather than defaulting to something that forwards bytes. The hazard being
 * guarded is not a wrong verdict but a confident one: a pair reported DIRECT because nothing was
 * known about it is how one version's packets reach a server speaking another.
 */
public final class CompatibilityProbeTests {
  private CompatibilityProbeTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    if (!ConduitViaBootstrap.available()) {
      ConduitViaBootstrap.start(Files.createTempDirectory("conduit-probe-test"), "test",
          new TranslationSettings(true, TranslationSettings.TranslationEngine.VIA_PREFERRED, true, true, false, "via"));
    }

    // Same version on both sides needs no translator, and says so as DIRECT rather than as a
    // translated path that happens to be the identity.
    var direct = CompatibilityProbe.probe(765, 765);
    require(direct.support() == TranslationSupport.DIRECT, "same version is DIRECT");
    require(direct.engine() == CompatibilityProbe.Engine.DIRECT, "same version uses no engine");

    // A pair only Via covers. 47 to 765 has no native translator and never will; it is the pair
    // that proves the verdict is read from the live graph and not from a table of registrations.
    var viaOnly = CompatibilityProbe.probe(47, 765);
    require(viaOnly.support() == TranslationSupport.TRANSLATED, "47 to 765 is translated");
    require(viaOnly.engine() == CompatibilityProbe.Engine.VIA, "47 to 765 is carried by Via");
    require(viaOnly.clientAdmissible(), "a 1.8 client is admissible");

    // 765 to 776 is the pair a stale override once reported as having no translator at all, while
    // Via had a path for it and the router refused to use it.
    var ceiling = CompatibilityProbe.probe(765, 776);
    require(ceiling.support() == TranslationSupport.TRANSLATED, "765 to 776 is translated");
    require(gg.tame.conduit.protocol.CompatibilityRegistry.resolve(765, 776).selectable(),
        "765 to 776 is selectable, whatever an explicit entry once recorded");

    // Protocol 777 is past the installed artifacts' ceiling in both directions.
    var beyond = CompatibilityProbe.probe(765, 777);
    require(beyond.support() == TranslationSupport.UNSUPPORTED, "beyond the ceiling is unsupported");
    require(beyond.engine() == CompatibilityProbe.Engine.NONE, "unsupported pairs name no engine");
    require(!beyond.usable(), "an unsupported pair is not usable");

    // A client with no packet table cannot be carried even where Via has a path, because the proxy
    // reads that client's own packets before any translator is chosen.
    var unknownClient = CompatibilityProbe.probe(110, 765);
    require(!unknownClient.clientAdmissible(), "1.9.3 has no table, so no session");
    require(!unknownClient.usable(), "not usable without a client table");

    legacyWorldReload();
    legacyChat();
    System.out.println("CompatibilityProbeTests passed.");
  }

  /**
   * Conduit's own chat messages, which Via never sees, in the layout each legacy client reads.
   *
   * <p>A real 1.7.6 client typed /server and was shown "Packet was larger than I expected, found 1
   * bytes extra whilst reading packet 2": the "Connecting to..." message carried the position byte
   * 1.8 added after the text.
   */
  private static void legacyChat() throws Exception {
    require(trailingBytesAfterText(ProtocolDefinition.forVersion(5)) == 0,
        "a 1.7 chat message ends at its JSON");
    require(trailingBytesAfterText(ProtocolDefinition.forVersion(47)) == 1,
        "a 1.8 chat message keeps its position byte");
  }

  private static int trailingBytesAfterText(ProtocolDefinition protocol) throws Exception {
    byte[] body = PlayPackets.body(PlayPackets.systemChat(protocol, "Connecting to v113..."));
    var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(body));
    gg.tame.conduit.protocol.MinecraftInput.string(input, 32767);
    return input.available();
  }

  /**
   * The respawn pair a switched pre-Configuration client needs, and the silence a modern one gets.
   */
  private static void legacyWorldReload() throws Exception {
    ProtocolDefinition legacy = ProtocolDefinition.forVersion(47);
    byte[] joinGame = joinGame18(0);
    var reload = LegacyWorldReload.afterSwitch(legacy, joinGame);
    require(reload.size() == 2, "a 1.8 client gets two respawns");

    int respawnId = legacy.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN);
    require(PlayPackets.packetId(reload.get(0)) == respawnId, "first is a respawn");
    require(PlayPackets.packetId(reload.get(1)) == respawnId, "second is a respawn");
    // The first must name a dimension the client is not in, or it is the no-op the client skips;
    // the second must name the one the backend actually put the player in.
    require(dimensionOf(reload.get(0)) != 0, "the first respawn leaves the overworld");
    require(dimensionOf(reload.get(1)) == 0, "the second respawn arrives where Join Game said");

    require(LegacyWorldReload.afterSwitch(ProtocolDefinition.forVersion(765), joinGame).isEmpty(),
        "a client with a Configuration phase reloads its world through that phase instead");
    require(LegacyWorldReload.afterSwitch(legacy, new byte[] {0x7f}).isEmpty(),
        "a packet that is not Join Game produces nothing");

    ProtocolDefinition v1122 = ProtocolDefinition.forVersion(340);
    require(LegacyWorldReload.afterSwitch(v1122, joinGameWithDifficulty(v1122)).size() == 2,
        "1.12.2 still uses the layout the reload writes, and keeps its pair");
    // The exact Join Game a real 1.15.2 client was switched with, as Via translated it from 1.13.
    // It was once answered with 0x3A (Resource Pack Send in 1.15) carrying the 1.13 layout.
    ProtocolDefinition v1152 = ProtocolDefinition.forVersion(578);
    byte[] joinGame1152 = java.util.HexFormat.of().parseHex("26000000a2010000000000000000000000000504666c6174400001");
    var reload1152 = LegacyWorldReload.afterSwitch(v1152, joinGame1152);
    require(reload1152.size() == 2, "a 1.15.2 client gets its pair");
    for (byte[] respawn : reload1152) {
      require(PlayPackets.packetId(respawn) == 0x3B, "1.15.2 Respawn is 0x3B, not Resource Pack Send's 0x3A");
    }
    var first = new java.io.DataInputStream(new java.io.ByteArrayInputStream(PlayPackets.body(reload1152.get(0))));
    require(first.readInt() != 0, "the first 1.15.2 respawn leaves the overworld");
    require(first.readLong() == 0L, "then the hashed seed from Join Game");
    require(first.readUnsignedByte() == 1, "then the gamemode");
    require(gg.tame.conduit.protocol.MinecraftInput.string(first, 16).equals("flat"), "then the level type");
    require(first.available() == 0, "and nothing after it");
    require(dimensionOf(reload1152.get(1)) == 0, "the second 1.15.2 respawn arrives where Join Game said");

    // 1.14: no difficulty, no seed.
    ProtocolDefinition v114 = ProtocolDefinition.forVersion(477);
    byte[] joinGame114 = PlayPackets.withId(0x25, java.util.HexFormat.of().parseHex("0000000701000000001404666c61740800"));
    var reload114 = LegacyWorldReload.afterSwitch(v114, joinGame114);
    require(reload114.size() == 2 && PlayPackets.packetId(reload114.get(0)) == 0x3A, "a 1.14 client gets its pair under 0x3A");
    require(PlayPackets.body(reload114.get(1)).length == 4 + 1 + 5, "1.14 Respawn is dimension, gamemode, level type");

    require(LegacyWorldReload.afterSwitch(ProtocolDefinition.forVersion(754), joinGame1152).isEmpty(),
        "1.16 names dimensions from a registry and is not rebuilt this way");
  }

  /** Join Game for 1.9.1-1.13.2: entity, gamemode, int dimension, difficulty, max players, level type. */
  private static byte[] joinGameWithDifficulty(ProtocolDefinition protocol) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(7);
    out.writeByte(1);
    out.writeInt(0);
    out.writeByte(2);
    out.writeByte(20);
    MinecraftOutput.string(out, "default");
    out.writeBoolean(false);
    return PlayPackets.withId(
        protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        bytes.toByteArray());
  }

  private static int dimensionOf(byte[] respawn) throws Exception {
    byte[] body = PlayPackets.body(respawn);
    return ((body[0] & 0xff) << 24) | ((body[1] & 0xff) << 16) | ((body[2] & 0xff) << 8) | (body[3] & 0xff);
  }

  /** 1.8 Join Game: the dimension is a signed byte at this end of the range. */
  private static byte[] joinGame18(int dimension) throws Exception {
    ProtocolDefinition legacy = ProtocolDefinition.forVersion(47);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(7);            // entity id
    out.writeByte(0);           // survival
    out.writeByte(dimension);
    out.writeByte(2);           // normal
    out.writeByte(20);          // max players
    MinecraftOutput.string(out, "default");
    out.writeBoolean(false);    // reduced debug info
    return PlayPackets.withId(
        legacy.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        bytes.toByteArray());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.viaversion.ConduitViaBootstrap;
import gg.tame.conduit.viaversion.ConduitViaTranslator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Locks the observable behaviour a 1.20.4 client's mid-session switch onto a 1.13.2 backend
 * depends on.
 *
 * <p>Via builds that client's Configuration phase out of the old backend's Join Game, but only for
 * a session that has watched a login exchange go past: a backend Login Success on its way to the
 * client, and the client's Login Acknowledged on its way back. A session that has not seen that
 * exchange does the same work with Play packet ids, and the first of them — Change Difficulty as
 * {@code 0x0B} — is a packet a 1.20.4 client sitting in Configuration drops the connection over.
 *
 * <p>A switch has no login of its own; Conduit performs it on the player's behalf and withholds the
 * new backend's Login Success, because the client is already logged in. So Conduit replays that
 * exchange into the session instead. Both halves of this are asserted here, because the failure
 * they guard against is silent on Conduit's side: the packets are produced and written, and it is
 * the client that decides they are wrong.
 */
public final class ViaSwitchBridgeTests {
  private static final int CLIENT_PROTOCOL = 765;
  private static final int BACKEND_PROTOCOL = 404;

  private ViaSwitchBridgeTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    if (!ConduitViaBootstrap.available()) {
      ConduitViaBootstrap.start(TempFiles.dir("conduit-via-switch-test"), "test",
          new TranslationSettings(true, TranslationSettings.TranslationEngine.VIA_PREFERRED, true, true, false, "via"));
    }
    ProtocolDefinition client = ProtocolDefinition.forVersion(CLIENT_PROTOCOL);

    int playDifficulty = client.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DIFFICULTY);
    int registry = client.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_REGISTRY);
    int finish = client.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);

    // Without the bridge: the phase comes out with Play ids, which is the failure being guarded
    // against. Asserting it keeps the test honest — if Via ever stops needing the exchange, this is
    // the half that fails, and the replay can go.
    List<Integer> unbridged = configurationPacketsAfterJoinGame(false);
    require(unbridged.contains(playDifficulty),
        "an unbridged session emits Change Difficulty with the Play id a 1.20.4 client disconnects on");
    require(!unbridged.contains(registry),
        "an unbridged session never reaches Registry Data");

    // With the bridge: a real Configuration phase, and Change Difficulty held back for Play.
    List<Integer> bridged = configurationPacketsAfterJoinGame(true);
    require(bridged.contains(registry), "the bridged session emits Registry Data");
    require(bridged.contains(finish), "the bridged session emits Finish Configuration");
    require(bridged.indexOf(registry) < bridged.indexOf(finish),
        "Registry Data precedes Finish Configuration");
    require(!bridged.contains(playDifficulty),
        "the bridged session keeps Change Difficulty out of Configuration");

    // A backend with its own Configuration phase still needs the exchange: it is what moves Via's
    // client half out of Login, and a session left there passes Client Information through in the
    // client's layout. A real 1.20.4 backend closed a 1.21.8 client's switch over that extra byte.
    require(clientInformationLength(772, 765, false) == 15, "an unbridged 1.21.8 -> 1.20.4 session passes the 1.21.8 layout");
    require(clientInformationLength(772, 765, true) == 14, "a bridged 1.21.8 -> 1.20.4 session drops the field 1.20.4 lacks");
    require(clientInformationLength(765, 772, true) == 15, "a bridged 1.20.4 -> 1.21.8 session adds the field 1.21.8 reads");

    aSwitchHandsOverViasRegistration();

    System.out.println("ViaSwitchBridgeTests passed.");
  }

  /**
   * Both sessions of a switch log the same player in to Via. The one carrying the player steps out
   * of Via's registry first, so the target's login is not reported as a duplicate UUID, and steps
   * back in when the switch does not happen.
   */
  private static void aSwitchHandsOverViasRegistration() throws Exception {
    var connections = com.viaversion.viaversion.api.Via.getManager().getConnectionManager();
    java.util.UUID notch = java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.logging.Handler capture = new java.util.logging.Handler() {
      @Override public void publish(java.util.logging.LogRecord record) { warnings.add(String.valueOf(record.getMessage())); }
      @Override public void flush() {}
      @Override public void close() {}
    };
    java.util.logging.Logger viaLog = com.viaversion.viaversion.api.Via.getPlatform().getLogger();
    viaLog.addHandler(capture);
    try (ConduitViaTranslator current = ConduitViaTranslator.create(CLIENT_PROTOCOL, BACKEND_PROTOCOL, "127.0.0.1", 25565)) {
      current.backendToClient(ConnectionState.LOGIN, loginSuccess());
      current.drainToClient();
      require(connections.hasServerConnection(notch), "the session carrying the player is registered with Via");

      // A switch that fails: the player is Via's again on the session they never left.
      require(current.releaseRegistration(), "the current session was the registered one");
      require(!connections.hasServerConnection(notch), "released while the target logs in");
      current.restoreRegistration();
      require(connections.hasServerConnection(notch), "restored when the switch does not happen");

      // A switch that goes through.
      require(current.releaseRegistration(), "released again for the next switch");
      try (ConduitViaTranslator target = ConduitViaTranslator.create(CLIENT_PROTOCOL, BACKEND_PROTOCOL, "127.0.0.1", 25565)) {
        target.backendToClient(ConnectionState.LOGIN, loginSuccess());
        target.drainToClient();
        require(warnings.stream().noneMatch(message -> message.contains("Duplicate UUID")),
            "the target's login is not a duplicate UUID: " + warnings);
        current.close();
        require(connections.hasServerConnection(notch), "closing the old session leaves the target registered");
      }
    } finally {
      viaLog.removeHandler(capture);
    }
  }

  /**
   * Client Information (configuration id 0x00 on both sides) after a session is opened the way a
   * switch opens it, with or without the login exchange replayed into it. 1.21.2 appended a
   * particle status varint; 1.20.4's body is 13 bytes for these values.
   */
  private static int clientInformationLength(int clientProtocol, int backendProtocol, boolean armBridge) throws Exception {
    try (ConduitViaTranslator via = ConduitViaTranslator.create(clientProtocol, backendProtocol, "127.0.0.1", 25565)) {
      if (armBridge) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(0x069a79f444e94726L);
        out.writeLong(0xa5befca90e38aaf5L);
        MinecraftOutput.string(out, "Notch");
        MinecraftOutput.varInt(out, 0);
        via.backendToClient(ConnectionState.LOGIN, PlayPackets.withId(0x02, bytes.toByteArray()));
        via.drainToClient();
        via.drainToBackend();
        via.clientToBackend(ConnectionState.LOGIN, PlayPackets.withId(0x03, new byte[0]));
        via.drainToClient();
        via.drainToBackend();
      }
      via.backendEntered(ConnectionState.CONFIGURATION);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      MinecraftOutput.string(out, "en_us");
      out.writeByte(8);
      MinecraftOutput.varInt(out, 0);
      out.writeBoolean(true);
      out.writeByte(0x7f);
      MinecraftOutput.varInt(out, 1);
      out.writeBoolean(false);
      out.writeBoolean(true);
      if (clientProtocol >= 768) MinecraftOutput.varInt(out, 0);
      return via.clientToBackend(ConnectionState.CONFIGURATION, PlayPackets.withId(0x00, bytes.toByteArray())).length;
    }
  }

  /**
   * Opens a session the way a switch does, optionally replays the login exchange into it, and
   * returns the ids of everything it emits toward the client when the backend's Join Game arrives.
   */
  private static List<Integer> configurationPacketsAfterJoinGame(boolean armBridge) throws Exception {
    ProtocolDefinition client = ProtocolDefinition.forVersion(CLIENT_PROTOCOL);
    ConduitViaTranslator via = ConduitViaTranslator.create(CLIENT_PROTOCOL, BACKEND_PROTOCOL, "127.0.0.1", 25565);
    try {
      if (armBridge) {
        via.backendToClient(ConnectionState.LOGIN, loginSuccess());
        via.drainToClient();
        via.drainToBackend();
        via.clientToBackend(ConnectionState.LOGIN,
            PlayPackets.loginAcknowledged(client));
        via.drainToClient();
        via.drainToBackend();
      }
      via.backendEntered(ConnectionState.PLAY);
      via.backendToClient(ConnectionState.PLAY, joinGame());
      List<Integer> ids = new ArrayList<>();
      for (byte[] packet : via.drainToClient()) ids.add(PlayPackets.packetId(packet));
      return ids;
    } finally {
      via.close();
    }
  }

  private static byte[] loginSuccess() throws Exception {
    ProtocolDefinition backend = ProtocolDefinition.forVersion(BACKEND_PROTOCOL);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    MinecraftOutput.string(out, "069a79f4-44e9-4726-a5be-fca90e38aaf5");
    MinecraftOutput.string(out, "Notch");
    return PlayPackets.withId(
        backend.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS),
        bytes.toByteArray());
  }

  /** 1.13.2 Join Game: the packet Via turns into the client's whole Configuration phase. */
  private static byte[] joinGame() throws Exception {
    ProtocolDefinition backend = ProtocolDefinition.forVersion(BACKEND_PROTOCOL);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(1);      // entity id
    out.writeByte(0);     // survival
    out.writeInt(0);      // overworld
    out.writeByte(2);     // normal difficulty
    out.writeByte(20);    // max players
    MinecraftOutput.string(out, "default");
    out.writeBoolean(false);
    return PlayPackets.withId(
        backend.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN),
        bytes.toByteArray());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

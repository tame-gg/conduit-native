// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.LegacyWorldReload;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.session.SwitchJoinGate;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Locks when a switched 1.8 client's packets are let through to the new backend again.
 *
 * <p>The failure this guards against was silent on every side. A real 1.8.9 client switched from a
 * 1.13 backend back to a 1.20.4 one was written its Join Game, its world and the server's Keep
 * Alive, and answered all of it, but Conduit dropped every packet it sent: the hold only ended when
 * the translator's direct result was Join Game, and on that path Via cancels the result and queues
 * the client's Join Game as an extra. The backend timed the player out thirty seconds later.
 *
 * <p>So the hold is asserted against what reaches the client, in both shapes Via delivers it.
 */
public final class SwitchJoinGateTests {
  private SwitchJoinGateTests() {}

  public static void main(String[] args) throws Exception {
    run();
  }

  public static void run() throws Exception {
    ProtocolDefinition legacy = ProtocolDefinition.forVersion(47);
    byte[] joinGame = joinGame18();
    byte[] keepAlive = packet(legacy, PacketKind.PLAY_KEEP_ALIVE, new byte[] {0x05});
    byte[] chunk = PlayPackets.withId(0x21, new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 1});

    // A session that is not switching is never held, whatever it is written.
    SwitchJoinGate idle = new SwitchJoinGate(legacy);
    idle.written(joinGame);
    require(!idle.holding(), "an unswitched session is not held");
    require(idle.takeJoinGame() == null, "an unswitched session has no switched Join Game");

    // 47 -> 393: Via returns Join Game as the result, and it is the first thing written.
    SwitchJoinGate asResult = new SwitchJoinGate(legacy);
    asResult.hold();
    require(asResult.holding(), "a switched 1.8 client is held");
    asResult.written(joinGame);
    require(asResult.takeJoinGame() == joinGame, "a Join Game returned as the result ends the hold");
    require(!asResult.holding(), "client packets resume after the result Join Game");

    // 47 -> 765 after a legacy backend: the result is cancelled, so nothing is written for it, and
    // Join Game arrives among the extras, behind nothing and ahead of the rest. This is the shape
    // that used to hold the client forever.
    SwitchJoinGate asExtra = new SwitchJoinGate(legacy);
    asExtra.hold();
    require(asExtra.takeJoinGame() == null, "a cancelled result does not end the hold");
    asExtra.written(joinGame);
    asExtra.written(chunk);
    asExtra.written(keepAlive);
    byte[] released = asExtra.takeJoinGame();
    require(released == joinGame, "a Join Game written as a translator extra ends the hold");
    require(!asExtra.holding(), "client packets, Keep Alive responses among them, resume");
    require(asExtra.takeJoinGame() == null, "the hold ends once");
    require(LegacyWorldReload.afterSwitch(legacy, released).size() == 2,
        "the Join Game handed on is the client's, so the world reload still follows it");

    // Nothing but Join Game ends it: Keep Alive and chunks before it keep the client held.
    SwitchJoinGate early = new SwitchJoinGate(legacy);
    early.hold();
    early.written(keepAlive);
    early.written(chunk);
    require(early.holding() && early.takeJoinGame() == null, "only Join Game ends the hold");

    // A failed switch releases without one.
    early.release();
    require(!early.holding(), "release ends the hold");

    System.out.println("SwitchJoinGateTests passed.");
  }

  private static byte[] packet(ProtocolDefinition protocol, PacketKind kind, byte[] body) throws Exception {
    return PlayPackets.withId(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind), body);
  }

  /** 1.8 Join Game in the overworld. */
  private static byte[] joinGame18() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(7);            // entity id
    out.writeByte(0);           // survival
    out.writeByte(0);           // overworld
    out.writeByte(2);           // normal
    out.writeByte(20);          // max players
    MinecraftOutput.string(out, "default");
    out.writeBoolean(false);    // reduced debug info
    return packet(ProtocolDefinition.forVersion(47), PacketKind.PLAY_LOGIN, bytes.toByteArray());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

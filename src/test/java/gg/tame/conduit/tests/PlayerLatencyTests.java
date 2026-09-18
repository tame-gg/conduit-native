// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.chat;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.text;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.util.Arrays;
import java.util.List;

/**
 * A player's latency is the round trip of the backend's keep-alive, from the proxy writing it to the
 * client answering it, as the game measures it. Plugins read it from {@code Player.ping()} (and
 * Velocity plugins from {@code getPing()}), which answered -1, "unknown", for every player.
 */
public final class PlayerLatencyTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theKeepAliveRoundTripIsThePlayersPing();
    System.out.println("PlayerLatencyTests OK");
  }

  /** 1.8.9 keep-alive: id 0x00 both ways, with a VarInt body. */
  private static byte[] keepAlive(int value) throws java.io.IOException {
    return packet(0x00, output -> MinecraftOutput.varInt(output, value));
  }

  private static void theKeepAliveRoundTripIsThePlayersPing() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"));
         Client client = Client.join(proxy.port(), "pinger")) {
      require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
      Player player = proxy.runtime.player("pinger").orElseThrow();
      require(player.ping() == -1, "unknown before the client has answered a keep-alive");

      byte[] asked = keepAlive(424242);
      lobby.send(asked);
      require(client.await(packet -> Arrays.equals(packet, asked)), "the keep-alive reached the client");
      client.send(keepAlive(7));
      require(lobby.await(packet -> id(packet) == 0x00), "a wrong answer still goes to the backend");
      Thread.sleep(50);
      require(player.ping() == -1, "an answer to some other keep-alive measures nothing");
      Thread.sleep(100);
      client.send(asked);
      require(waitFor(() -> player.ping() >= 0, 5_000), "the answer measures the round trip");
      long ping = player.ping();
      require(ping >= 140 && ping < 2_000, "the round trip includes the client's delay, got " + ping + " ms");

      client.send(chat("/ping"));
      require(client.await(packet -> id(packet) == CHAT_OUT && text(packet).contains("ping " + ping + " ms")),
          "/ping tells the player their ping");
    }
  }
}

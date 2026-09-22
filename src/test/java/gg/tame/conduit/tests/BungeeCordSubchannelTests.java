// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.DISCONNECT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.text;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

/**
 * The two documented BungeeCord subchannels Conduit did not answer: {@code GetPlayerServer}, which a
 * queue or hub plugin asks before it moves someone, and {@code KickPlayerRaw}, KickPlayer with a
 * JSON reason. Both were consumed and ignored, so the plugin waited for an answer that never came.
 */
public final class BungeeCordSubchannelTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    getPlayerServerSaysWhereSomeoneIs();
    kickPlayerRawKicksWithTheJsonReason();
    System.out.println("BungeeCordSubchannelTests OK");
  }

  private static void getPlayerServerSaysWhereSomeoneIs() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client asker = NativeApiTests.Client.join(proxy.port(), "asker");
           NativeApiTests.Client other = NativeApiTests.Client.join(proxy.port(), "Other")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 2), "both joined");
        lobby.send(NativeApiTests.pluginMessage(NativeApiTests.pluginOut(), "bungeecord:main",
            write(out -> { out.writeUTF("GetPlayerServer"); out.writeUTF("other"); })));
        require(waitFor(() -> !answers(lobby, "GetPlayerServer").isEmpty(), 10_000), "the backend got an answer");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(answers(lobby, "GetPlayerServer").getFirst()))) {
          require(in.readUTF().equals("GetPlayerServer"), "the reply names its subchannel");
          require(in.readUTF().equals("Other"), "then the player, as they are named");
          require(in.readUTF().equals("lobby"), "then the server they are on");
        }
      }
    }
  }

  private static void kickPlayerRawKicksWithTheJsonReason() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "kicked")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 1), "joined");
        lobby.send(NativeApiTests.pluginMessage(NativeApiTests.pluginOut(), "bungeecord:main",
            write(out -> { out.writeUTF("KickPlayerRaw"); out.writeUTF("kicked"); out.writeUTF("{\"text\":\"Queue closed\"}"); })));
        require(client.await(packet -> id(packet) == DISCONNECT_OUT && text(packet).contains("Queue closed")),
            "the player is kicked with the JSON reason's text");
      }
    }
  }

  /** The bungeecord:main payloads the backend was sent that answer this subchannel. */
  private static List<byte[]> answers(NativeApiTests.Backend backend, String subchannel) {
    return backend.received(packet -> {
      try {
        if (!NativeApiTests.channelOf(packet).equals("bungeecord:main")) return false;
        return new DataInputStream(new ByteArrayInputStream(NativeApiTests.dataOf(packet))).readUTF().equals(subchannel);
      } catch (Exception notOne) {
        return false;
      }
    }).stream().map(NativeApiTests::dataOf).toList();
  }

  private interface Body { void write(DataOutputStream out) throws Exception; }

  private static byte[] write(Body body) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { body.write(out); }
    return bytes.toByteArray();
  }
}

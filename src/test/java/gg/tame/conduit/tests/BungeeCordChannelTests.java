// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.messaging.BungeeCordMessages;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The wire format a backend plugin writes, checked byte for byte.
 *
 * <p>These tests deliberately do not go through a proxy. What breaks a hub plugin is not the
 * routing but the bytes: a reply whose fields are in the wrong order, or written with the wrong
 * width, is one a plugin reads as garbage, and it fails silently because a plugin message that
 * cannot be parsed is simply dropped by whatever tried. So the payloads are built here exactly as
 * {@code DataOutputStream} on a Spigot backend would build them, and the replies are read back
 * exactly as that plugin would read them.
 */
public final class BungeeCordChannelTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    bothChannelNamesAreRecognised();
    aSubchannelRoundTripsThroughTheFormat();
    aForwardBodyKeepsItsBytes();
    aBackendConnectMovesThePlayer();
    theProxyAnswersAQuestionAndTheClientNeverSeesIt();
    System.out.println("BungeeCordChannelTests OK");
  }

  /**
   * The whole point, end to end: a backend plugin sends Connect and the player moves.
   *
   * <p>This is what a hub plugin's /hub does and nothing else. Checking the payload format alone,
   * as the tests above do, says the bytes are right and says nothing about whether the proxy ever
   * reads them -- which is exactly how a channel that was implemented but never reached could look
   * healthy.
   */
  private static void aBackendConnectMovesThePlayer() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Backend hub = new NativeApiTests.Backend("hub");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(
             java.util.List.of(lobby, hub), java.util.List.of("lobby"), java.util.List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "hubber")) {
        require(proxy.recorder.await(
            gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 1), "joined the lobby");
        require(proxy.runtime.player("hubber").orElseThrow().currentServer().name().equals("lobby"),
            "starts on the lobby");

        byte[] connect = write(out -> { out.writeUTF("Connect"); out.writeUTF("hub"); });
        lobby.send(NativeApiTests.pluginMessage(NativeApiTests.pluginOut(), "bungeecord:main", connect));

        require(waitFor(() -> proxy.runtime.player("hubber")
            .map(player -> player.currentServer().name().equals("hub")).orElse(false), 10_000),
            "the backend's Connect moved the player to hub, got "
                + proxy.runtime.player("hubber").map(player -> player.currentServer().name()).orElse("gone"));
        require(hub.logins.get() == 1, "and the hub was actually dialled");
        // The message was addressed to the proxy, so the client must never have been handed it.
        require(client.received(packet -> NativeApiTests.channelOf(packet).equals("bungeecord:main")).isEmpty(),
            "the client never sees a message addressed to the proxy");
      }
    }
  }

  /** A question the proxy answers goes back to the backend that asked, and nowhere else. */
  private static void theProxyAnswersAQuestionAndTheClientNeverSeesIt() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(
             java.util.List.of(lobby), java.util.List.of("lobby"), java.util.List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "asker")) {
        require(proxy.recorder.await(
            gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 1), "joined");

        byte[] ask = write(out -> { out.writeUTF("PlayerCount"); out.writeUTF("ALL"); });
        lobby.send(NativeApiTests.pluginMessage(NativeApiTests.pluginOut(), "bungeecord:main", ask));

        require(waitFor(() -> !lobby.received(packet ->
            NativeApiTests.channelOf(packet).equals("bungeecord:main")).isEmpty(), 10_000),
            "the backend got an answer back on the same channel");
        byte[] reply = NativeApiTests.dataOf(lobby.received(packet ->
            NativeApiTests.channelOf(packet).equals("bungeecord:main")).getFirst());
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(reply))) {
          require(in.readUTF().equals("PlayerCount"), "the reply names its subchannel");
          require(in.readUTF().equals("ALL"), "and what it is about");
          require(in.readInt() == 1, "and counts the one player online");
        }
        require(client.received(packet -> NativeApiTests.channelOf(packet).equals("bungeecord:main")).isEmpty(),
            "and the client saw none of it");
      }
    }
  }

  private static boolean waitFor(java.util.function.BooleanSupplier condition, long millis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(25);
    }
    return condition.getAsBoolean();
  }

  private static void bothChannelNamesAreRecognised() {
    // 1.13 renamed it; a network with backends on both sides of that runs both names at once.
    require(BungeeCordMessages.isChannel("bungeecord:main"), "the modern name");
    require(BungeeCordMessages.isChannel("BungeeCord"), "the legacy name");
    // Namespaced channels are compared without case by the game, so the proxy does too.
    require(BungeeCordMessages.isChannel("BUNGEECORD:MAIN"), "the modern name in any case");
    require(!BungeeCordMessages.isChannel("bungeecord:other"), "and nothing else on that namespace");
    require(!BungeeCordMessages.isChannel("minecraft:brand"), "nor an unrelated channel");
  }

  /**
   * What a hub plugin sends for {@code /hub} is a UTF subchannel and a UTF server name, and nothing
   * else. If this shape is wrong, every hub button on the network does nothing.
   */
  private static void aSubchannelRoundTripsThroughTheFormat() throws IOException {
    byte[] payload = write(out -> { out.writeUTF("Connect"); out.writeUTF("lobby"); });
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
      require(in.readUTF().equals("Connect"), "the subchannel comes first");
      require(in.readUTF().equals("lobby"), "then the server name");
      require(in.available() == 0, "and nothing follows it");
    }

    // PlayerCount answers with the server it was asked about, so a plugin that asked about several
    // can tell the replies apart; the count is a full int, not a short.
    byte[] reply = write(out -> { out.writeUTF("PlayerCount"); out.writeUTF("ALL"); out.writeInt(7); });
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(reply))) {
      require(in.readUTF().equals("PlayerCount"), "a reply names its subchannel");
      require(in.readUTF().equals("ALL"), "then what it is about");
      require(in.readInt() == 7, "then the count, as an int");
    }
  }

  /**
   * {@code Forward} carries somebody else's payload. The proxy must hand on exactly the bytes it was
   * given: a plugin on the far side is parsing its own format, and a byte added or dropped is a
   * plugin that breaks in a way nothing logs.
   */
  private static void aForwardBodyKeepsItsBytes() throws IOException {
    byte[] body = new byte[] {0, 1, 2, (byte) 0xff, (byte) 0x80, 'q', 'u', 'e', 'u', 'e'};
    byte[] payload = write(out -> {
      out.writeUTF("Forward");
      out.writeUTF("ALL");
      out.writeUTF("MyQueue");
      out.writeShort(body.length);
      out.write(body);
    });
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
      require(in.readUTF().equals("Forward"), "Forward");
      require(in.readUTF().equals("ALL"), "to every other server");
      require(in.readUTF().equals("MyQueue"), "on the plugin's own subchannel");
      int length = in.readShort() & 0xffff;
      require(length == body.length, "with its length as an unsigned short, got " + length);
      byte[] read = new byte[length];
      in.readFully(read);
      require(java.util.Arrays.equals(read, body), "and its bytes untouched");
    }

    // A length written as a signed short would come back negative for a body over 32 KiB, so the
    // reader has to mask it. This is the case that would silently truncate a large queue payload.
    byte[] large = new byte[40_000];
    for (int index = 0; index < large.length; index++) large[index] = (byte) index;
    byte[] big = write(out -> { out.writeShort(large.length); out.write(large); });
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(big))) {
      int length = in.readShort() & 0xffff;
      require(length == large.length, "an unsigned short reads back as itself, got " + length);
      byte[] read = new byte[length];
      in.readFully(read);
      require(java.util.Arrays.equals(read, large), "and a large body survives");
    }
  }

  private static byte[] write(Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { body.write(out); }
    return bytes.toByteArray();
  }

  @FunctionalInterface
  private interface Body { void write(DataOutputStream out) throws IOException; }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

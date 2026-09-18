// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.DISCONNECT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.require;

import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A kicked player is shown why. The proxy wrote the disconnect and closed the socket at once, and a
 * socket closed with bytes of the client's still unread -- a playing client is always sending -- is
 * reset rather than ended. A client that takes the reset before it has read the disconnect loses it,
 * and the player saw "Connection reset" instead of the reason.
 */
public final class KickDeliveryTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aKickReachesAClientThatIsStillSending();
    System.out.println("KickDeliveryTests OK");
  }

  private static void aKickReachesAClientThatIsStillSending() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      for (int round = 0; round < 20; round++) {
        String name = "chatty" + round;
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
          socket.setSoTimeout(10_000);
          MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
          MinecraftFrames.write(socket.getOutputStream(), packet(0, output -> MinecraftOutput.string(output, name)));
          InputStream in = socket.getInputStream();
          while (MinecraftFrames.read(in, 1 << 20)[0] != 0x01) { }                       // up to Join Game
          require(proxy.recorder.await(PlayerServerConnectedEvent.class, round + 1), name + " joined");
          Player player = proxy.runtime.player(name).orElseThrow();
          // The client keeps sending, as a playing one does: a flying packet at a time, flat out.
          AtomicBoolean sending = new AtomicBoolean(true);
          byte[] flying = packet(0x03, output -> output.writeBoolean(true));
          Thread sender = Thread.ofPlatform().daemon().start(() -> {
            try {
              OutputStream out = socket.getOutputStream();
              while (sending.get()) MinecraftFrames.write(out, flying);
            } catch (IOException closed) { }
          });
          Thread.sleep(20);
          player.disconnect("Kicked for testing, round " + round);
          String reason = null;
          try {
            while (reason == null) {
              byte[] frame = MinecraftFrames.read(in, 1 << 20);
              if (frame[0] == DISCONNECT_OUT) reason = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1)), 1 << 16);
            }
          } catch (IOException lost) {
            throw new AssertionError("round " + round + ": the kick never arrived, the connection ended with " + lost);
          } finally {
            sending.set(false);
          }
          require(reason.contains("Kicked for testing, round " + round), "round " + round + " shows the reason, got " + reason);
          sender.join(5_000);
        }
      }
    }
  }
}

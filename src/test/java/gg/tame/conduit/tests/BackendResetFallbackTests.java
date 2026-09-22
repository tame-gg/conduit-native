// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import java.net.Socket;
import java.util.List;

/**
 * A backend that resets its connection is fallen back from, as one that closes it cleanly is.
 *
 * <p>A crashed or killed server resets its connections, and so does one that closes with bytes
 * from Conduit still unread. Conduit used to end the session on a reset and fall back only on a
 * clean close, so a player whose server died hard was dropped instead of moved.
 */
public final class BackendResetFallbackTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aResetBackendIsFallenBackFrom();
    System.out.println("BackendResetFallbackTests OK");
  }

  private static void aResetBackendIsFallenBackFrom() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Backend survival = new NativeApiTests.Backend("survival");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby, survival), List.of("lobby"), List.of("survival"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "reset")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 1), "joined");
        require(proxy.runtime.player("reset").orElseThrow().currentServer().name().equals("lobby"), "starts on the lobby");

        // A zero linger turns the close into a reset, whatever is or is not buffered.
        var field = NativeApiTests.Backend.class.getDeclaredField("current");
        field.setAccessible(true);
        Socket socket = (Socket) field.get(lobby);
        socket.setSoLinger(true, 0);
        socket.close();

        require(waitFor(() -> proxy.runtime.player("reset")
                .map(player -> player.currentServer().name().equals("survival")).orElse(false), 15_000),
            "the player was moved to the fallback, got "
                + proxy.runtime.player("reset").map(player -> player.currentServer().name()).orElse("dropped"));
      }
    }
  }
}

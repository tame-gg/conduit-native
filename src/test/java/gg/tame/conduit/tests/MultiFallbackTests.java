// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.protocol.MinecraftOutput;
import java.util.List;

/**
 * A backend going away under several players at once: every one of them reaches the fallback, not
 * only the first. Both ways a server stops -- kicking everyone with "Server closed", and the socket
 * simply going -- with three 1.8.9 clients on loopback.
 */
public final class MultiFallbackTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    everyPlayerOnADyingServerReachesTheFallback(true);
    everyPlayerOnADyingServerReachesTheFallback(false);
    System.out.println("MultiFallbackTests OK");
  }

  private static void everyPlayerOnADyingServerReachesTheFallback(boolean kicked) throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Backend survival = new NativeApiTests.Backend("survival");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby", "survival"))) {
      try (NativeApiTests.Client a = NativeApiTests.Client.join(proxy.port(), "alpha");
           NativeApiTests.Client b = NativeApiTests.Client.join(proxy.port(), "bravo");
           NativeApiTests.Client c = NativeApiTests.Client.join(proxy.port(), "charlie")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 3), "all three joined lobby");
        if (kicked) {
          // 1.8.9 Play Disconnect (0x40) with a JSON reason, what a stopping server sends everyone.
          lobby.sendAll(NativeApiTests.packet(0x40, output -> MinecraftOutput.string(output, "{\"text\":\"Server closed\"}")));
        }
        lobby.close();
        for (String name : List.of("alpha", "bravo", "charlie")) {
          require(waitFor(() -> proxy.runtime.playerManager().getByUsername(name)
              .map(player -> "survival".equalsIgnoreCase(player.currentBackend())).orElse(false), 15_000),
              name + " reached survival after lobby " + (kicked ? "kicked everyone" : "went away") + "; on "
                  + proxy.runtime.playerManager().getByUsername(name).map(p -> p.currentBackend()).orElse("<gone>"));
        }
      }
    }
  }
}

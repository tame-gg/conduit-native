// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

/**
 * Conduit sends a backend with no Configuration phase nothing of its own before that backend's Join Game.
 *
 * <p>Such a server switches its decoder to Play when it sends Join Game, not when it sends Login
 * Success. A freshly started vanilla 1.19 or 1.19.2 server takes long enough over its first player
 * for the proxy's channel registration to land in between, read it as Login packet 12 (13 on
 * 1.19.2) and close with "Index 12 out of bounds for length 3"; every later join was fast enough.
 * This backend is that slow server on every join.
 */
public final class ColdBackendJoinTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    nothingReachesTheBackendBeforeItsJoinGame();
    System.out.println("ColdBackendJoinTests OK");
  }

  private static void nothingReachesTheBackendBeforeItsJoinGame() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(
             java.util.List.of(lobby), java.util.List.of("lobby"), java.util.List.of("lobby"))) {
      lobby.joinGameDelayMillis = 1_500;
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "coldjoin")) {
        boolean registered = lobby.await(packet -> {
          try { return NativeApiTests.channelOf(packet).equals("REGISTER"); } catch (RuntimeException other) { return false; }
        });
        require(lobby.beforeJoinGame.isEmpty(), "nothing reached the backend before its Join Game, got "
            + lobby.beforeJoinGame.size() + " packet(s)");
        require(registered, "the channels are still registered, once the backend is in Play");
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Every backend a player joins is told the connection listens on {@code bungeecord:main}.
 *
 * <p>A Paper or Spigot backend sends a plugin message only on a channel the connection registered,
 * and a vanilla client never registers this one. Without the proxy's own registration, a hub
 * plugin's server selector (DeluxeHub's {@code [PROXY]} action) was dropped on the backend and
 * never reached Conduit. A scripted backend sends whatever it is told, which is how that hid.
 */
public final class BungeeCordRegistrationTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    eachBackendJoinedIsToldTheChannel();
    System.out.println("BungeeCordRegistrationTests OK");
  }

  private static void eachBackendJoinedIsToldTheChannel() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Backend hub = new NativeApiTests.Backend("hub");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(
             java.util.List.of(lobby, hub), java.util.List.of("lobby"), java.util.List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "registrant")) {
        require(lobby.await(registersBungeeCord()), "the first backend is told the connection listens on BungeeCord");

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) { out.writeUTF("Connect"); out.writeUTF("hub"); }
        lobby.send(NativeApiTests.pluginMessage(NativeApiTests.pluginOut(), "bungeecord:main", bytes.toByteArray()));
        require(hub.await(registersBungeeCord()), "and so is the backend a switch lands on");
      }
    }
  }

  /**
   * The scripted client is 1.8, so the backend is told in that release's names: {@code REGISTER}
   * and {@code BungeeCord}. From 1.13 they are {@code minecraft:register} and {@code bungeecord:main}.
   */
  private static Predicate<byte[]> registersBungeeCord() {
    return packet -> {
      try {
        if (!NativeApiTests.channelOf(packet).equals("REGISTER")) return false;
        String names = new String(NativeApiTests.dataOf(packet), StandardCharsets.UTF_8);
        return Arrays.asList(names.split("\0")).contains("BungeeCord");
      } catch (RuntimeException notAPluginMessage) {
        return false;
      }
    };
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * A channel a plugin listens on ({@code ConduitProxy#listenOnChannel}) is registered with the
 * backends, as the BungeeCord channel is.
 *
 * <p>A Paper backend sends only on channels the connection registered. ajQueue's backend half
 * reported "ajQueue must also be installed on the proxy" while it was, because its messages to the
 * proxy half were dropped on the backend: the Velocity plugin had registered the channel with the
 * proxy, and nothing told the backend.
 */
public final class ProxyChannelRegistrationTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aListenedChannelReachesTheBackend();
    System.out.println("ProxyChannelRegistrationTests OK");
  }

  private static void aListenedChannelReachesTheBackend() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      proxy.runtime.listenOnChannel("test:early");
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "listener")) {
        require(lobby.await(registers("test:early")), "a channel listened on before the join is registered with the backend joined");
        proxy.runtime.listenOnChannel("test:late");
        require(lobby.await(registers("test:late")), "and one listened on later reaches the backend the player is already on");
      }
    }
  }

  /** A registration naming this channel, in either release's register channel. */
  private static Predicate<byte[]> registers(String channel) {
    return packet -> {
      try {
        String on = NativeApiTests.channelOf(packet);
        if (!on.equals("REGISTER") && !on.equals("minecraft:register")) return false;
        String names = new String(NativeApiTests.dataOf(packet), StandardCharsets.UTF_8);
        return Arrays.asList(names.split("\0")).contains(channel);
      } catch (RuntimeException notAPluginMessage) {
        return false;
      }
    };
  }
}

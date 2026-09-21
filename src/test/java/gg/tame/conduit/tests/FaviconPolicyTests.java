// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.StatusSettings;
import gg.tame.conduit.config.StatusSettings.FaviconPolicy;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code status.favicon-policy = "proxy-only"}: whatever a ping listener leaves in the answer, the
 * icon a client is sent is the operator's own, or none.
 *
 * <p>The default, {@code "plugins"}, is what Conduit has always done and is covered where it always
 * was, by {@code NativeApiTests}.
 */
public final class FaviconPolicyTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final String PROXY_ICON = "data:image/png;base64,UFJPWFk=";
  /** What a plugin that pings a backend and hands the answer on would set. */
  private static final String BACKEND_ICON = StatusIsolationTests.BACKEND_ICON;
  private static final String CUSTOM_ICON = "data:image/png;base64,Q1VTVE9N";

  public static void run() throws Exception {
    theProxysIconReplacesWhateverAListenerSet();
    withNoIconOfItsOwnTheAnswerCarriesNone();
    everythingButTheIconIsStillTheListenersToChange();
    theSettingIsReadAndAnUnknownValueIsRefused();
    System.out.println("FaviconPolicyTests OK");
  }

  /** A backend's icon and a plugin's own icon meet the same end. */
  private static void theProxysIconReplacesWhateverAListenerSet() throws Exception {
    for (String set : List.of(BACKEND_ICON, CUSTOM_ICON)) {
      try (Backend lobby = new Backend("lobby");
           Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"),
               new StatusSettings(Text.of("Conduit"), 100, Optional.of(PROXY_ICON), FaviconPolicy.PROXY_ONLY))) {
        proxy.recorder.hook = event -> {
          if (event instanceof ServerListPingEvent ping) ping.setFavicon(Optional.of(set));
        };
        String json = ping(proxy.port(), "localhost");
        require(json.contains("\"favicon\":\"" + PROXY_ICON + "\""), "the proxy's icon, got " + json);
        require(!json.contains(set), "and not the one the listener set, got " + json);
      }
    }
  }

  private static void withNoIconOfItsOwnTheAnswerCarriesNone() throws Exception {
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"),
             new StatusSettings(Text.of("Conduit"), 100, Optional.empty(), FaviconPolicy.PROXY_ONLY))) {
      proxy.recorder.hook = event -> {
        if (event instanceof ServerListPingEvent ping) ping.setFavicon(Optional.of(BACKEND_ICON));
      };
      String json = ping(proxy.port(), "localhost");
      require(!json.contains("favicon"), "no icon at all where there is no icon file, got " + json);
    }
  }

  /** Only the icon is taken back: the rest of the answer is still whatever the listener made it. */
  private static void everythingButTheIconIsStillTheListenersToChange() throws Exception {
    java.util.UUID ghost = new java.util.UUID(3, 4);
    try (Backend lobby = new Backend("lobby");
         Fixture proxy = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"),
             new StatusSettings(Text.of("Conduit"), 100, Optional.of(PROXY_ICON), FaviconPolicy.PROXY_ONLY))) {
      proxy.recorder.hook = event -> {
        if (event instanceof ServerListPingEvent ping) {
          ping.setDescription(Text.of("Rewritten"));
          ping.setMaxPlayers(7);
          ping.setOnlinePlayers(3);
          ping.setSamplePlayers(List.of(new ServerListPingEvent.SamplePlayer("ghost", ghost)));
          ping.setVersionName("Custom");
          ping.setVersionProtocol(9999);
          ping.setFavicon(Optional.of(CUSTOM_ICON));
        }
      };
      String json = ping(proxy.port(), "localhost");
      require(json.contains("\"name\":\"Custom\",\"protocol\":9999"), "the listener's version, got " + json);
      require(json.contains("\"max\":7,\"online\":3"), "the listener's counts, got " + json);
      require(json.contains("\"name\":\"ghost\""), "the listener's sample, got " + json);
      require(json.contains("\"description\":{\"text\":\"Rewritten\"}"), "the listener's MOTD, got " + json);
      require(json.contains("\"favicon\":\"" + PROXY_ICON + "\"") && !json.contains(CUSTOM_ICON),
          "and the proxy's icon regardless, got " + json);
    }
  }

  private static void theSettingIsReadAndAnUnknownValueIsRefused() throws Exception {
    require(FaviconPolicy.parse("plugins") == FaviconPolicy.PLUGINS, "plugins");
    require(FaviconPolicy.parse("PROXY-ONLY") == FaviconPolicy.PROXY_ONLY, "case does not matter");
    require(StatusSettings.defaults().faviconPolicy() == FaviconPolicy.PLUGINS, "the default leaves plugins alone");
    Path directory = TempFiles.dir("favicon-policy");
    Path file = directory.resolve("conduit.toml");
    Files.writeString(file, base() + "[status]\nfavicon-policy = \"proxy-only\"\n");
    ConduitConfiguration configuration = ConfigurationLoader.load(file);
    require(configuration.status().faviconPolicy() == FaviconPolicy.PROXY_ONLY, "read from the file");
    Files.writeString(file, base() + "[status]\nfavicon-policy = \"nobody\"\n");
    try {
      ConfigurationLoader.load(file);
      throw new AssertionError("an unknown policy must be refused at start");
    } catch (IllegalArgumentException refused) {
      require(refused.getMessage().contains("status.favicon-policy") && refused.getMessage().contains("proxy-only"),
          "and say what is allowed, got " + refused.getMessage());
    }
    Files.writeString(file, base());
    require(ConfigurationLoader.load(file).status().faviconPolicy() == FaviconPolicy.PLUGINS,
        "an unset policy is the old behaviour");
  }

  /** The status JSON a vanilla client is sent; NativeApiTests keeps its own copy of this. */
  private static String ping(int port, String host) throws Exception {
    try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      gg.tame.conduit.protocol.MinecraftFrames.write(socket.getOutputStream(),
          new gg.tame.conduit.protocol.Handshake(47, host, 25565, 1).encode());
      gg.tame.conduit.protocol.MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
      byte[] response = gg.tame.conduit.protocol.MinecraftFrames.read(socket.getInputStream(), 1 << 20);
      require(response[0] == 0, "a status response, got id " + response[0]);
      String json = gg.tame.conduit.protocol.MinecraftInput.string(new java.io.DataInputStream(
          new java.io.ByteArrayInputStream(java.util.Arrays.copyOfRange(response, 1, response.length))), 1 << 16);
      // Finished properly, so the proxy logs a closed exchange rather than a truncated VarInt.
      gg.tame.conduit.protocol.MinecraftFrames.write(socket.getOutputStream(),
          java.nio.ByteBuffer.allocate(9).put((byte) 1).putLong(42).array());
      byte[] pong = gg.tame.conduit.protocol.MinecraftFrames.read(socket.getInputStream(), 64);
      require(pong[0] == 1 && java.nio.ByteBuffer.wrap(pong, 1, 8).getLong() == 42, "the pong echoes the nonce");
      return json;
    }
  }

  private static String base() {
    return "[listener]\nhost = \"127.0.0.1\"\nport = 25565\nmax-frame-bytes = 2097152\n"
        + "[forwarding]\nmode = \"none\"\n[servers.lobby]\nhost = \"127.0.0.1\"\nport = 25566\n"
        + "[routing]\ninitial = [\"lobby\"]\nfallback = [\"lobby\"]\n";
  }
}

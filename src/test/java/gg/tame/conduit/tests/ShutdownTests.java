// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A plugin that stops the proxy can say why: every player is kicked with its reason instead of the
 * configured shutdown message, natively and through Velocity's {@code ProxyServer.shutdown(Component)}.
 */
public final class ShutdownTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aNativeShutdownReasonReachesPlayers();
    aVelocityShutdownReasonReachesPlayers();
    System.out.println("ShutdownTests OK");
  }

  private static void aNativeShutdownReasonReachesPlayers() throws Exception {
    String reason = joinThenShutDown(TempFiles.dir("conduit-shutdown-native").resolve("plugins"),
        proxy -> proxy.runtime().shutdown(Text.of("Back in five minutes")));
    require(reason.contains("Back in five minutes"), "the plugin's reason is the kick screen, got " + reason);
  }

  private static final String STOPPER = """
      package stopper;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.connection.PostLoginEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import javax.inject.Inject;
      import net.kyori.adventure.text.Component;
      @Plugin(id = "stopper", name = "stopper", version = "1.0")
      public final class Main {
        private final ProxyServer proxy;
        @Inject public Main(ProxyServer proxy) { this.proxy = proxy; }
        @Subscribe public void joined(PostLoginEvent event) { proxy.shutdown(Component.text("Restarting for an update")); }
      }
      """;

  private static void aVelocityShutdownReasonReachesPlayers() throws Exception {
    Path root = TempFiles.dir("conduit-shutdown-velocity");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "stopper.Main", STOPPER, List.of(), true), plugins.resolve("stopper.jar"), null);
    String reason = joinThenShutDown(plugins, proxy -> { });
    require(reason.contains("Restarting for an update"), "the Velocity plugin's reason is the kick screen, got " + reason);
  }

  /** Joins a 1.8 player, runs {@code stop}, and returns the reason of the disconnect the player is sent. */
  private static String joinThenShutDown(Path plugins, Consumer<MinecraftProxy> stop) throws Exception {
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread.ofPlatform().daemon().name("shutdown-backend").start(() -> ObservabilityTests.serveBackend(lobby));
      ConduitConfiguration configuration = VelocityCompatTests.configuration(
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))));
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
      Thread serving = Thread.ofPlatform().daemon().name("shutdown-serve").start(() -> { try { proxy.serve(); } catch (IOException ignored) { } });
      try (Socket client = new Socket()) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
          try { client.connect(new InetSocketAddress("127.0.0.1", proxy.port())); break; }
          catch (IOException notYet) { if (System.nanoTime() > deadline) throw notYet; Thread.sleep(20); }
        }
        client.setSoTimeout(15_000);
        MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
        MinecraftFrames.write(client.getOutputStream(), ObservabilityTests.packet(0, out -> MinecraftOutput.string(out, "Metrics")));
        InputStream in = client.getInputStream();
        boolean play = false;
        boolean stopped = false;
        while (true) {
          byte[] packet = MinecraftFrames.read(in, 1 << 20);
          // A plugin stopping the proxy from PostLoginEvent can have the player kicked before Join
          // Game reaches them, or before Login Success does.
          boolean disconnect = play ? packet[0] == 0x40 : packet[0] == 0x00;       // 1.8 Play / Login Disconnect
          if (disconnect) return MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(packet, 1, packet.length - 1)), 1 << 16);
          if (!play && packet[0] == 0x02) play = true;                                // Login Success
          if (play && packet[0] == 0x01 && !stopped) {                                // Join Game
            stopped = true;
            stop.accept(proxy);
          }
        }
      } finally {
        proxy.close();
        serving.join(10_000);
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

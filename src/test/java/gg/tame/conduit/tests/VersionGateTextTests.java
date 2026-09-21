// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.VersionGateSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What a player on the wrong version is shown, in the server list and on the kick screen.
 *
 * <p>Both were sent as plain text while the MOTD beside them took MiniMessage and {@code &} codes,
 * so an operator who wrote {@code "&cThis network only allows Minecraft {versions}."} got the
 * ampersand and the c in front of their message rather than a red line.
 */
public final class VersionGateTextTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final String COLOURED_KICK = "&cThis network only allows Minecraft {versions}.";
  private static final String MINIMESSAGE_KICK = "<red>Only <bold>{versions}</bold> may join.</red>";

  public static void run() throws Exception {
    theServerListEntryForAWrongVersionIsColoured();
    theKickScreenIsColoured();
    System.out.println("VersionGateTextTests OK");
  }

  /** The line under the server name, for a client the gate will not let in. */
  private static void theServerListEntryForAWrongVersionIsColoured() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, COLOURED_KICK)) {
      String json = proxy.ping(47);
      require(json.contains("\"color\":\"red\""), "the & code became a colour, got " + json);
      require(!json.contains("&c"), "and is not shown as written, got " + json);
      require(json.contains("This network only allows Minecraft 1.20.4."),
          "with the allowed version filled in, got " + json);
    }
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, MINIMESSAGE_KICK)) {
      String json = proxy.ping(47);
      require(json.contains("\"color\":\"red\"") && json.contains("\"bold\":true"),
          "MiniMessage too, tags and all, got " + json);
      require(!json.contains("<red>"), "and not the tags themselves, got " + json);
    }
  }

  /** And the screen the client is left on when it tries to join anyway. */
  private static void theKickScreenIsColoured() throws Exception {
    try (Backend lobby = new Backend("lobby"); Proxy proxy = new Proxy(lobby, COLOURED_KICK)) {
      String json = proxy.login(47, "Oldie");
      require(json.contains("\"color\":\"red\""), "the kick screen is coloured, got " + json);
      require(!json.contains("&c"), "not the code as written, got " + json);
      require(json.contains("This network only allows Minecraft 1.20.4."), "and says what may join, got " + json);
    }
  }

  /** A proxy that only lets 1.20.4 in, with the kick message a test gives it. */
  private static final class Proxy implements AutoCloseable {
    private final MinecraftProxy proxy;
    private final Thread serving;
    Proxy(Backend backend, String kickMessage) throws Exception {
      VersionGateSettings gate = new VersionGateSettings(true, Set.of(765), OptionalInt.empty(), OptionalInt.empty(),
          VersionGateSettings.DEFAULT_PING, kickMessage, kickMessage, false);
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2),
          gate, null, null, null, null, null, null, null);
      int port;
      try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
      List<BackendServer> backends = List.of(backend.server());
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", port), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), backends, List.of("lobby"), List.of("lobby"),
          AuthenticationSettings.offline(), Optional.empty(), ops);
      Path root = TempFiles.dir("version-gate-text");
      proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(),
          root.resolve("plugins"));
      serving = Thread.ofPlatform().daemon().name("version-gate-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }

    /** The status answer, as the multiplayer screen reads it. */
    String ping(int protocol) throws IOException {
      try (Socket socket = open()) {
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(protocol, "127.0.0.1", proxy.port(), 1).encode());
        MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
        return string(MinecraftFrames.read(socket.getInputStream(), 1 << 20));
      }
    }

    /** The Login Disconnect a client on a refused version is sent. */
    String login(int protocol, String name) throws IOException {
      try (Socket socket = open()) {
        MinecraftFrames.write(socket.getOutputStream(), new Handshake(protocol, "127.0.0.1", proxy.port(), 2).encode());
        MinecraftFrames.write(socket.getOutputStream(), NativeApiTests.loginStart(name));
        return string(MinecraftFrames.read(socket.getInputStream(), 1 << 20));
      }
    }

    private Socket open() throws IOException {
      Socket socket = new Socket("127.0.0.1", proxy.port());
      socket.setSoTimeout(10_000);
      return socket;
    }

    /** Both packets carry one string after the id: the status answer, or the disconnect reason. */
    private static String string(byte[] packet) throws IOException {
      return MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(
          Arrays.copyOfRange(packet, 1, packet.length))), 1 << 16);
    }

    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }
}

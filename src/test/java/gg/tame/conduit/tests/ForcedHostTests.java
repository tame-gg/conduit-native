// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.modded.FmlAddressMarkers;
import gg.tame.conduit.routing.ForcedHosts;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.Map;

/**
 * {@code [forced-hosts]}: which backend a hostname sends a player to, and what happens to a
 * hostname that names none.
 */
public final class ForcedHostTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theHostDecidesTheServer();
    anythingButTheNameIsIgnored();
    anUnmatchedHostForcesNothing();
    aHostTwiceIsRefused();
    theFileIsRead();
    aForcedHostRoutesTheJoin();
    System.out.println("ForcedHostTests OK");
  }

  private static void theHostDecidesTheServer() {
    ForcedHosts hosts = ForcedHosts.of(Map.of("lobby.example.com", List.of("lobby"),
        "pvp.example.com", List.of("pvp", "lobby")));
    require(hosts.match("lobby.example.com").equals(List.of("lobby")), "one server, got " + hosts.match("lobby.example.com"));
    require(hosts.match("pvp.example.com").equals(List.of("pvp", "lobby")), "a list in order, got " + hosts.match("pvp.example.com"));
  }

  /** Case, the port the entry named, a trailing root dot and a Forge marker are all the same host. */
  private static void anythingButTheNameIsIgnored() {
    ForcedHosts hosts = ForcedHosts.of(Map.of("Lobby.Example.COM", List.of("lobby"),
        "[::1]", List.of("six"), "2001:db8::1", List.of("bare")));
    for (String written : List.of("lobby.example.com", "LOBBY.example.com", "lobby.example.com:25565",
        "lobby.example.com.", "lobby.example.com.:25565",
        FmlAddressMarkers.append("lobby.example.com", FmlAddressMarkers.MarkerKind.FML3),
        FmlAddressMarkers.append("lobby.example.com", FmlAddressMarkers.MarkerKind.FORGE))) {
      require(hosts.match(written).equals(List.of("lobby")), "the same host however it is written: " + written.replace('\0', '.'));
    }
    require(hosts.match("[::1]:25565").equals(List.of("six")), "a bracketed IPv6 literal keeps its colons");
    require(hosts.match("2001:db8::1").equals(List.of("bare")), "an unbracketed one is not cut at its first colon");
  }

  private static void anUnmatchedHostForcesNothing() {
    ForcedHosts hosts = ForcedHosts.of(Map.of("lobby.example.com", List.of("lobby")));
    require(hosts.match("play.example.com").isEmpty(), "a host nothing matches forces nothing");
    require(hosts.match("").isEmpty(), "and so does no host at all");
    require(hosts.match(null).isEmpty(), "and so does none");
    require(ForcedHosts.none().match("lobby.example.com").isEmpty(), "with no forced hosts, nothing is forced");
    require(ForcedHosts.none().isEmpty() && ForcedHosts.of(Map.of()).isEmpty(), "an empty table is empty");
  }

  private static void aHostTwiceIsRefused() {
    java.util.Map<String, List<String>> twice = new java.util.LinkedHashMap<>();
    twice.put("lobby.example.com", List.of("lobby"));
    twice.put("LOBBY.example.com.", List.of("other"));
    try {
      ForcedHosts.of(twice);
      throw new AssertionError("two spellings of one host must be refused");
    } catch (IllegalArgumentException expected) {
      require(expected.getMessage().contains("same host"), "named as a duplicate, got " + expected.getMessage());
    }
    try {
      ForcedHosts.of(Map.of("lobby.example.com", List.of()));
      throw new AssertionError("a host naming no server must be refused");
    } catch (IllegalArgumentException expected) {
      require(expected.getMessage().contains("at least one server"), "said so, got " + expected.getMessage());
    }
  }

  /** Read from conduit.toml, as one name or as a list, and a host naming an unknown server starts anyway. */
  private static void theFileIsRead() throws Exception {
    Path directory = TempFiles.dir("forced-hosts");
    Path file = directory.resolve("conduit.toml");
    Files.writeString(file, """
        [listener]
        host = "127.0.0.1"
        port = 25565
        max-frame-bytes = 2097152

        [forwarding]
        mode = "none"

        [servers.lobby]
        host = "127.0.0.1"
        port = 25566

        [servers.pvp]
        host = "127.0.0.1"
        port = 25567

        [routing]
        initial = ["lobby"]
        fallback = ["lobby"]

        [forced-hosts]
        "lobby.example.com" = "lobby"
        "pvp.example.com" = ["pvp", "lobby"]
        "typo.example.com" = "nosuchserver"
        """);
    ConduitConfiguration configuration = ConfigurationLoader.load(file);
    ForcedHosts hosts = configuration.forcedHosts();
    require(hosts.match("lobby.example.com").equals(List.of("lobby")), "one name, got " + hosts.match("lobby.example.com"));
    require(hosts.match("pvp.example.com").equals(List.of("pvp", "lobby")), "a list, got " + hosts.match("pvp.example.com"));
    // Warned about at start, not refused: the player still lands somewhere.
    require(hosts.match("typo.example.com").equals(List.of("nosuchserver")), "an unknown server is kept and warned about");
    require(hosts.match("other.example.com").isEmpty(), "and everything else falls through");
  }

  /**
   * The whole way through: a player whose server list entry says pvp.example.com joins the pvp
   * backend, and one who typed anything else follows routing.initial to the lobby.
   */
  private static void aForcedHostRoutesTheJoin() throws Exception {
    try (Backend lobby = new Backend("lobby"); Backend pvp = new Backend("pvp");
         Proxy proxy = new Proxy(List.of(lobby.server(), pvp.server()),
             Map.of("pvp.example.com", List.of("pvp")))) {
      try (Client forced = Client.join(proxy.port(), "Forced", "pvp.example.com")) {
        require(NativeApiTests.waitFor(() -> pvp.logins.get() == 1, 10_000), "the forced host joins pvp");
        require(lobby.logins.get() == 0, "and never touches the lobby");
      }
      try (Client plain = Client.join(proxy.port(), "Plain", "play.example.com")) {
        require(NativeApiTests.waitFor(() -> lobby.logins.get() == 1, 10_000), "an unmatched host falls through to routing.initial");
        require(pvp.logins.get() == 1, "and does not reach the forced backend");
      }
    }
  }

  /** The proxy under test, with the forced hosts a test gives it and nothing else unusual. */
  private static final class Proxy implements AutoCloseable {
    private final MinecraftProxy proxy;
    private final Thread serving;
    Proxy(List<BackendServer> backends, Map<String, List<String>> forced) throws Exception {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2),
          null, null, null, null, null, null);
      int port;
      try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", port), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), backends, List.of("lobby"), List.of("lobby"),
          AuthenticationSettings.offline(), Optional.empty(), ops, false, ForcedHosts.of(forced));
      Path root = TempFiles.dir("forced-hosts-proxy");
      proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(),
          root.resolve("plugins"));
      serving = Thread.ofPlatform().daemon().name("forced-hosts-serve").start(() -> {
        try { proxy.serve(); } catch (Exception ignored) { }
      });
    }
    int port() throws IOException { return proxy.port(); }
    @Override public void close() throws Exception {
      proxy.close();
      serving.join(10_000);
    }
  }

  /** A 1.8.9 client that writes the address the player typed, as its server list entry holds it. */
  private static final class Client implements AutoCloseable {
    private final Socket socket;
    private Client(Socket socket) { this.socket = socket; }
    static Client join(int port, String name, String host) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      socket.setSoTimeout(15_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, host, 25565, 2).encode());
      MinecraftFrames.write(socket.getOutputStream(), NativeApiTests.loginStart(name));
      return new Client(socket);
    }
    @Override public void close() throws IOException { socket.close(); }
  }
}

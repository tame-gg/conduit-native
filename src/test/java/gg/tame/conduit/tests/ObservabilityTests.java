// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.MetricsSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The metrics Conduit exposes: read from its configuration, served in Prometheus' text format only
 * when asked for, carrying counts and server names but nothing about players, addresses or secrets.
 */
public final class ObservabilityTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theMetricsAddressComesFromTheConfig();
    aSettingNothingReadsIsNamed();
    theEndpointServesCountsAndNothingPrivate();
    System.out.println("ObservabilityTests OK");
  }

  private static final String BASE_CONFIG = """
      [listener]
      host = "127.0.0.1"
      port = 25565
      max-frame-bytes = 1048576
      [forwarding]
      mode = "none"
      [authentication]
      mode = "offline"
      [servers.lobby]
      host = "127.0.0.1"
      port = 25566
      [routing]
      initial = ["lobby"]
      fallback = ["lobby"]
      """;

  private static void theMetricsAddressComesFromTheConfig() throws Exception {
    Path root = TempFiles.dir("conduit-metrics-config");
    Path plain = Files.writeString(root.resolve("plain.toml"), BASE_CONFIG);
    require(ConfigurationLoader.load(plain).ops().metrics().prometheusAddress().isEmpty(), "metrics are off unless configured");
    Path on = Files.writeString(root.resolve("on.toml"), BASE_CONFIG + "[metrics]\nprometheus-address = \"127.0.0.1:9225\"\n");
    require(ConfigurationLoader.load(on).ops().metrics().prometheusAddress().orElseThrow().getPort() == 9225, "the address is read");
    Path bad = Files.writeString(root.resolve("bad.toml"), BASE_CONFIG + "[metrics]\nprometheus-address = \"nope\"\n");
    try { ConfigurationLoader.load(bad); throw new AssertionError("an address without a port was accepted"); }
    catch (IllegalArgumentException expected) { }
  }

  /**
   * A misspelt setting was silently ignored and its default used in its place. It is now named in a
   * warning, and every configuration shipped in config/ is read without one.
   */
  private static void aSettingNothingReadsIsNamed() throws Exception {
    Path root = TempFiles.dir("conduit-config-unknown");
    Path typo = Files.writeString(root.resolve("typo.toml"), BASE_CONFIG + "[health]\ninterval_ms = 5000\n[helth]\nenabled = false\n");
    String warnings = stderrOf(() -> ConfigurationLoader.load(typo));
    require(warnings.contains("Unknown setting health.interval_ms in typo.toml is ignored")
        && warnings.contains("Unknown setting helth.enabled in typo.toml is ignored"), "each unread setting is named:\n" + warnings);
    require(!warnings.contains(root.toString()), "by file name, not its path");
    try (var shipped = Files.list(Path.of("config"))) {
      for (Path config : shipped.filter(file -> file.toString().endsWith(".toml")).toList()) {
        String shippedWarnings = stderrOf(() -> ConfigurationLoader.load(config));
        require(!shippedWarnings.contains("Unknown setting"), config.getFileName() + " has settings Conduit does not read:\n" + shippedWarnings);
      }
    }
  }

  private interface Load { Object run() throws Exception; }

  private static String stderrOf(Load load) throws Exception {
    java.io.PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try { load.run(); } finally { System.setErr(original); }
    return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * One player joins through a first server that refuses and lands on the lobby, and a scrape then
   * shows them, the refused connection and the lobby's count, and names neither the player nor any
   * address. Anything but GET or HEAD is refused, and the endpoint goes with the proxy.
   */
  private static void theEndpointServesCountsAndNothingPrivate() throws Exception {
    int metricsPort = reservePort();
    int deadPort = reservePort();
    long failuresBefore = gg.tame.conduit.metrics.ConduitMetrics.current().backendConnectFailures();
    try (ServerSocket lobby = new ServerSocket(0)) {
      Thread.ofPlatform().daemon().name("metrics-backend").start(() -> serveBackend(lobby));
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2),
          null, null, null, null, null, null, new MetricsSettings(Optional.of(new InetSocketAddress("127.0.0.1", metricsPort))));
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("dead", new InetSocketAddress("127.0.0.1", deadPort)),
              new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort()))),
          List.of("dead", "lobby"), List.of("lobby"), AuthenticationSettings.offline(), Optional.empty(), ops);
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(),
          TempFiles.dir("conduit-metrics").resolve("plugins"));
      Thread serving = Thread.ofPlatform().daemon().name("metrics-serve").start(() -> { try { proxy.serve(); } catch (IOException ignored) { } });
      HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
      URI endpoint = URI.create("http://127.0.0.1:" + metricsPort + "/metrics");
      try (Socket client = new Socket()) {
        require(await(() -> proxy.runtime().metricsEndpoint().isPresent()), "the endpoint is bound once the proxy starts");
        client.connect(new InetSocketAddress("127.0.0.1", proxy.port()));
        client.setSoTimeout(10_000);
        long asked = System.nanoTime();
        MinecraftFrames.write(client.getOutputStream(), new Handshake(47, "localhost", 25565, 2).encode());
        MinecraftFrames.write(client.getOutputStream(), packet(0, out -> MinecraftOutput.string(out, "Metrics")));
        InputStream in = client.getInputStream();
        boolean joined = false;
        for (int i = 0; i < 10 && !joined; i++) joined = MinecraftFrames.read(in, 1 << 20)[0] == 0x01;   // 1.8 Join Game
        require(joined, "the player joined through the refusing first server");
        // A check made while the login is held waited out the whole 3 s read timeout, twice, on every
        // login; a join through two local servers takes a fraction of one.
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asked);
        require(took < 2_500, "the join took " + took + " ms");

        HttpResponse<String> scrape = http.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
        String body = scrape.body();
        require(scrape.statusCode() == 200 && scrape.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"),
            "a scrape is answered in the text format, got " + scrape.statusCode());
        require(value(body, "conduit_players{path=\"direct\"}") == 1, "one DIRECT player:\n" + body);
        require(value(body, "conduit_players{path=\"translated\"}") == 0, "no translated player");
        require(value(body, "conduit_players_on_server{server=\"lobby\"}") == 1 && value(body, "conduit_players_on_server{server=\"dead\"}") == 0,
            "the lobby has the player");
        require(value(body, "conduit_backend_connections") >= 1, "the lobby connection is open");
        require(value(body, "conduit_backend_connect_failures_total") >= failuresBefore + 1, "the refused first server was counted");
        require(value(body, "conduit_connections_accepted_total") >= 1 && value(body, "conduit_plugins") == 0, "counts and gauges are there");
        require(body.contains("# TYPE conduit_backend_connects_total counter"), "families are typed");
        require(!body.contains("Metrics") && !body.contains("127.0.0.1"),
            "no player name or address is exposed:\n" + body);
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString("x")).build(),
            HttpResponse.BodyHandlers.ofString());
        require(post.statusCode() == 405, "anything but GET or HEAD is refused, got " + post.statusCode());
      } finally {
        proxy.close();
        serving.join(10_000);
      }
      try {
        http.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
        throw new AssertionError("the endpoint outlived the proxy");
      } catch (IOException closed) { }
    }
  }

  /** A 1.8 backend: takes the login, sends Join Game, then reads until the proxy goes. */
  private static void serveBackend(ServerSocket listener) {
    try {
      while (true) {
        Socket socket = listener.accept();
        Thread.ofPlatform().daemon().start(() -> {
          try (socket) {
            InputStream in = socket.getInputStream();
            MinecraftFrames.read(in, 4096);
            MinecraftFrames.read(in, 4096);
            MinecraftFrames.write(socket.getOutputStream(), packet(2, out -> {
              MinecraftOutput.string(out, UUID.nameUUIDFromBytes("OfflinePlayer:Metrics".getBytes()).toString());
              MinecraftOutput.string(out, "Metrics");
            }));
            MinecraftFrames.write(socket.getOutputStream(), packet(1, out -> {
              out.writeInt(1); out.writeByte(0); out.writeByte(0); out.writeByte(0); out.writeByte(20);
              MinecraftOutput.string(out, "default"); out.writeBoolean(false);
            }));
            while (true) MinecraftFrames.read(in, 1 << 20);
          } catch (IOException ended) { }
        });
      }
    } catch (IOException closed) { }
  }

  private interface Body { void write(DataOutputStream out) throws IOException; }

  private static byte[] packet(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      body.write(out);
    }
    return bytes.toByteArray();
  }

  private static double value(String body, String series) {
    Matcher match = Pattern.compile("^" + Pattern.quote(series) + " (\\S+)$", Pattern.MULTILINE).matcher(body);
    if (!match.find()) throw new AssertionError("no series " + series + " in:\n" + body);
    return Double.parseDouble(match.group(1));
  }

  private static int reservePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

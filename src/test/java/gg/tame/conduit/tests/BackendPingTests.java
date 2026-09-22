// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.reservePort;
import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.plugin.ConduitPlugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.server.RegisteredServer;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.health.BackendHealth;
import gg.tame.conduit.health.BackendHealthService;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.BackendStatusProbe;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.runtime.ConduitRuntime;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * {@code RegisteredServer.ping()} against scripted backends: the full answer, every way a backend
 * can fail to give one, many pings at once, pings from plugin threads, the Velocity adapter's
 * {@code ServerPing}, and the health probe that shares the code.
 */
public final class BackendPingTests {
  public static void main(String[] arguments) throws Exception { run(); }

  private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final String ICON = "data:image/png;base64,iVBORw0KGgo=";
  /** Players before version, as some servers write it: a regex for the first "name" found Alice. */
  private static final String RICH = "{\"players\":{\"max\":50,\"online\":2,\"sample\":["
      + "{\"name\":\"Alice\",\"id\":\"" + ALICE + "\"},{\"name\":\"not-a-uuid\",\"id\":\"nope\"}]},"
      + "\"version\":{\"name\":\"Paper 1.21.4\",\"protocol\":769},"
      + "\"description\":{\"text\":\"Hello \",\"color\":\"gold\",\"extra\":[{\"text\":\"world\",\"bold\":true}]},"
      + "\"favicon\":\"" + ICON + "\",\"enforcesSecureChat\":true}";
  /** A MOTD with a legacy section-sign colour code, as a plain-string description carries one. */
  private static final String LEGACY = (char) 0xA7 + "aLegacy MOTD";

  public static void run() throws Exception {
    healthProbeStillReadsVersionAndCounts();
    chatReportSafetyIsClaimedOnlyWhenEveryBackendClaimsIt();
    Path root = TempFiles.dir("backend-ping");
    try (Backend unused = new Backend(json(RICH));
         ConduitRuntime runtime = new ConduitRuntime(VelocityCompatTests.configuration(List.of(unused.server("lobby"))), root.resolve("plugins"), root)) {
      theWholeAnswerComesBack(runtime);
      everyFailureIsAnOfflineStatus(runtime);
      manyPingsRunAtOnce(runtime);
      aFloodIsRefusedNotQueuedForever(runtime);
      pluginThreadsPingWithoutBlocking(runtime);
    }
    velocityPluginsGetAServerPing();
    System.out.println("BackendPingTests OK");
  }

  private static void theWholeAnswerComesBack(ConduitRuntime runtime) throws Exception {
    try (Backend rich = new Backend(json(RICH))) {
      RegisteredServer server = runtime.servers().register("rich", rich.address());
      ServerStatus status = server.ping().get(5, TimeUnit.SECONDS);
      require(status.online() && status.name().equals("rich"), "online, got " + status);
      require(status.protocol().getAsInt() == 769 && status.versionName().equals("Paper 1.21.4"), "version, got " + status);
      require(status.onlinePlayers().getAsInt() == 2 && status.maxPlayers().getAsInt() == 50, "counts, got " + status);
      require(status.samplePlayers().equals(List.of(new ServerListPingEvent.SamplePlayer("Alice", ALICE))),
          "the sample, without the entry whose id is not a UUID, got " + status.samplePlayers());
      require(status.description().equals(Text.of("Hello ").color(TextColor.GOLD).append(Text.of("world").bold())),
          "the description as Text, colour and all, got " + status.description());
      require(status.favicon().orElseThrow().equals(ICON), "the favicon, got " + status.favicon());
      require(status.latencyMillis().isPresent(), "a round trip, got " + status);
      Handshake asked = rich.handshakes.poll();
      require(asked.protocolVersion() == -1 && asked.requestedHost().equals("127.0.0.1") && asked.requestedPort() == rich.address().getPort()
          && asked.nextState() == 1, "by default the handshake names no version and the configured host, got " + asked);

      ServerStatus chosen = server.ping(47, "play.example.net", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
      require(chosen.online(), "online with options too");
      asked = rich.handshakes.poll();
      require(asked.protocolVersion() == 47 && asked.requestedHost().equals("play.example.net"), "the options reach the handshake, got " + asked);
      require(!server.status().favicon().isPresent() && server.status().description().equals(Text.empty()),
          "the cached status is left as it was: " + server.status());
      runtime.servers().unregister("rich");
    }
    // A plain-string description, as older servers send, and an icon no client would draw.
    try (Backend plain = new Backend(json("{\"version\":{\"name\":\"old\",\"protocol\":47},\"description\":\"" + LEGACY + "\","
        + "\"favicon\":\"https://example.invalid/icon.png\"}"))) {
      ServerStatus status = runtime.servers().register("plain", plain.address()).ping().get(5, TimeUnit.SECONDS);
      require(status.online() && status.description().equals(Text.of(LEGACY)), "the string as it came, got " + status.description());
      require(status.favicon().isEmpty() && status.samplePlayers().isEmpty() && status.onlinePlayers().isEmpty(),
          "no icon, sample or counts where the backend gave none, got " + status);
      runtime.servers().unregister("plain");
    }
  }

  private static void everyFailureIsAnOfflineStatus(ConduitRuntime runtime) throws Exception {
    // Windows takes about two seconds to give up on a refused loopback connection; the timeout comes first.
    offline(runtime, "unreachable", null, Duration.ofSeconds(1), 3_000);
    // Under the 1500 ms health timeout: the requested one is what counts.
    offline(runtime, "silent", SILENT, Duration.ofMillis(500), 1_400);
    offline(runtime, "malformed", json("{\"version\":{\"name\":"), Duration.ofSeconds(5), 3_000);
    offline(runtime, "not an object", json("[1,2,3]"), Duration.ofSeconds(5), 3_000);
    offline(runtime, "no protocol", json("{\"version\":{\"name\":\"x\"},\"players\":{\"max\":1,\"online\":0}}"), Duration.ofSeconds(5), 3_000);
    offline(runtime, "wrong packet", socket -> MinecraftFrames.write(socket.getOutputStream(), new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 42}),
        Duration.ofSeconds(5), 3_000);
    offline(runtime, "closed mid-answer", socket -> {
      OutputStream out = socket.getOutputStream();
      out.write(new byte[] {(byte) 0xE8, 0x07});                 // a 1000-byte frame
      out.write(new byte[] {0, 20, '{', '"', 'v'});
      out.flush();
    }, Duration.ofSeconds(5), 3_000);
    offline(runtime, "oversized", socket -> {
      OutputStream out = socket.getOutputStream();
      out.write(new byte[] {(byte) 0x80, (byte) 0x80, 0x10}); // a 256 KiB frame, then nothing
      out.flush();
      socket.getInputStream().read();
    }, Duration.ofSeconds(5), 3_000);
    // A read timeout restarts with every byte; the deadline does not.
    offline(runtime, "trickling", socket -> {
      OutputStream out = socket.getOutputStream();
      out.write(100);
      for (int sent = 0; sent < 100; sent++) { out.write(0); out.flush(); Thread.sleep(150); }
    }, Duration.ofMillis(600), 1_400);
    // Deeper than any stack: the parser's recursion is contained, and the ping still completes.
    String deep = "{\"version\":{\"name\":\"x\",\"protocol\":47},\"description\":" + "[".repeat(40_000) + "]".repeat(40_000) + "}";
    try (Backend nested = new Backend(json(deep))) {
      ServerStatus status = runtime.servers().register("nested", nested.address()).ping().get(10, TimeUnit.SECONDS);
      require(status != null, "a status either way");
      runtime.servers().unregister("nested");
    }
  }

  private static void offline(ConduitRuntime runtime, String name, Answer answer, Duration timeout, long withinMillis) throws Exception {
    Backend backend = answer == null ? null : new Backend(answer);
    try {
      InetSocketAddress address = backend == null ? new InetSocketAddress("127.0.0.1", reservePort()) : backend.address();
      RegisteredServer server = runtime.servers().register(name.replace(' ', '-'), address);
      long started = System.nanoTime();
      CompletableFuture<ServerStatus> ping = server.ping(-1, null, timeout);
      ServerStatus status = ping.get(10, TimeUnit.SECONDS);
      long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      require(!ping.isCompletedExceptionally() && !status.online(), name + ": an offline status, not an exception, got " + status);
      require(took < withinMillis, name + ": answered in " + took + " ms, not within " + withinMillis);
      runtime.servers().unregister(server.getName());
    } finally {
      if (backend != null) backend.close();
    }
  }

  /** 24 pings to a backend that takes half a second each: one after another would take twelve. */
  private static void manyPingsRunAtOnce(ConduitRuntime runtime) throws Exception {
    try (Backend slow = new Backend(socket -> { Thread.sleep(500); json(RICH).serve(socket); })) {
      RegisteredServer server = runtime.servers().register("slow", slow.address());
      long started = System.nanoTime();
      List<CompletableFuture<ServerStatus>> pings = new ArrayList<>();
      for (int i = 0; i < 24; i++) pings.add(server.ping(-1, null, Duration.ofSeconds(10)));
      CompletableFuture.allOf(pings.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
      long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      require(pings.stream().allMatch(ping -> ping.join().online()), "every ping answered");
      require(took < 4_000 && slow.peak.get() >= 20, "concurrently: " + took + " ms, at most " + slow.peak.get() + " at once");
      runtime.servers().unregister("slow");
    }
  }

  /**
   * More pings to a silent backend than there are threads and queue: the excess is answered offline
   * at once, and the queued ones fail when their time is up instead of each waiting out its own.
   */
  private static void aFloodIsRefusedNotQueuedForever(ConduitRuntime runtime) throws Exception {
    try (Backend silent = new Backend(SILENT)) {
      RegisteredServer server = runtime.servers().register("flooded", silent.address());
      long started = System.nanoTime();
      List<CompletableFuture<ServerStatus>> pings = new ArrayList<>();
      for (int i = 0; i < 1_100; i++) pings.add(server.ping(-1, null, Duration.ofMillis(400)));
      long refused = pings.stream().filter(CompletableFuture::isDone).count();
      CompletableFuture.allOf(pings.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
      long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      require(refused >= 1, "the pings beyond the bound are answered at once");
      require(pings.stream().noneMatch(ping -> ping.join().online()), "all offline");
      require(took < 4_000, "the whole flood is done in " + took + " ms");
      require(silent.peak.get() <= 32, "never more than 32 backend connections at once, saw " + silent.peak.get());
      runtime.servers().unregister("flooded");
    }
  }

  /** A plugin's task asks; the call returns at once and the answer comes on a thread of Conduit's. */
  private static void pluginThreadsPingWithoutBlocking(ConduitRuntime runtime) throws Exception {
    ConduitPlugin plugin = new ConduitPlugin() { };
    plugin.attach(new PluginDescription("pinger", "pinger", "1", "Main", 1, List.of()), runtime, Logger.getLogger("pinger"), null, runtime.scheduler());
    try (Backend silent = new Backend(SILENT); Backend rich = new Backend(json(RICH))) {
      RegisteredServer quiet = runtime.servers().register("quiet", silent.address());
      RegisteredServer answering = runtime.servers().register("answering", rich.address());
      CompletableFuture<String> report = new CompletableFuture<>();
      runtime.scheduler().buildTask(plugin, () -> {
        try {
          String task = Thread.currentThread().getName();
          long started = System.nanoTime();
          CompletableFuture<ServerStatus> waiting = quiet.ping(-1, null, Duration.ofSeconds(2));
          long call = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
          CompletableFuture<String> where = new CompletableFuture<>();
          ServerStatus status = answering.ping().whenComplete((done, failed) -> where.complete(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
          report.complete(task + "|" + (call < 100) + "|" + status.online() + "|" + !where.get(5, TimeUnit.SECONDS).equals(task)
              + "|" + !waiting.get(5, TimeUnit.SECONDS).online());
        } catch (Exception failed) {
          report.completeExceptionally(failed);
        }
      }).schedule();
      String seen = report.get(10, TimeUnit.SECONDS);
      require(seen.startsWith("conduit-plugin-pinger-") && seen.endsWith("|true|true|true|true"),
          "from the plugin's own thread, returning at once, answered elsewhere, got " + seen);
      runtime.servers().unregister("quiet");
      runtime.servers().unregister("answering");
    } finally {
      runtime.scheduler().cancel(plugin);
    }
  }

  /** What the health checks and routing read is unchanged: version and counts, healthy or not. */
  private static void healthProbeStillReadsVersionAndCounts() throws Exception {
    var parsed = BackendStatusProbe.parse(RICH).orElseThrow();
    require(parsed.protocol() == 769 && parsed.name().equals("Paper 1.21.4") && parsed.onlinePlayers() == 2 && parsed.maxPlayers() == 50,
        "the version's own name, not the first name in the answer, got " + parsed);
    require(BackendStatusProbe.parse("{\"description\":\"no version\"}").isEmpty() && BackendStatusProbe.parse("not json").isEmpty(),
        "no protocol, no advertisement");
    try (Backend rich = new Backend(json(RICH))) {
      BackendServer up = rich.server("up");
      BackendServer down = new BackendServer("down", new InetSocketAddress("127.0.0.1", reservePort()));
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(up, down));
      try (BackendHealthService health = new BackendHealthService(new ServerRegistry(configuration), new HealthSettings(true, 10_000, 1_000, 1, 1))) {
        health.probeOnce();
        require(health.snapshot("up").health() == BackendHealth.HEALTHY && health.snapshot("down").health() == BackendHealth.UNHEALTHY,
            "healthy and unhealthy as before, got " + health.all());
        var advertised = health.advertisement("up").orElseThrow();
        require(advertised.protocol() == 769 && advertised.name().equals("Paper 1.21.4") && advertised.onlinePlayers() == 2,
            "the advertisement routing reads, got " + advertised);
        ServerStatus cached = health.toServerStatus("up");
        require(cached.online() && cached.maxPlayers().getAsInt() == 50 && cached.favicon().isEmpty(), "the cached status as before, got " + cached);
        require(!health.toServerStatus("down").online(), "down is offline");
        Handshake asked = rich.handshakes.poll();
        require(asked.protocolVersion() == -1 && asked.requestedHost().equals("127.0.0.1"), "the health probe's handshake is unchanged, got " + asked);
      }
    }
  }

  /**
   * No Chat Reports clients mark a server safe from "preventsChatReports" in its answer. The proxy
   * passes signed chat on as it comes, so it may say so only when every backend says so itself: one
   * that does not is somewhere a player could be reported under a badge that told them otherwise.
   */
  private static void chatReportSafetyIsClaimedOnlyWhenEveryBackendClaimsIt() throws Exception {
    String safe = "{\"version\":{\"name\":\"Fabric 1.21.4\",\"protocol\":769},\"description\":\"\",\"preventsChatReports\":true}";
    require(BackendStatusProbe.parse(safe).orElseThrow().preventsChatReports(), "the field is read");
    require(!BackendStatusProbe.parse(RICH).orElseThrow().preventsChatReports(), "and absent is not safe");

    try (Backend ncr = new Backend(json(safe)); Backend plain = new Backend(json(RICH)); Backend ncr2 = new Backend(json(safe))) {
      HealthSettings settings = new HealthSettings(true, 10_000, 1_000, 1, 1);
      BackendServer never = new BackendServer("never", new InetSocketAddress("127.0.0.1", reservePort()));
      try (BackendHealthService mixed = new BackendHealthService(new ServerRegistry(
          VelocityCompatTests.configuration(List.of(ncr.server("a"), plain.server("b")))), settings);
           BackendHealthService allSafe = new BackendHealthService(new ServerRegistry(
          VelocityCompatTests.configuration(List.of(ncr.server("a"), ncr2.server("b")))), settings);
           BackendHealthService unanswered = new BackendHealthService(new ServerRegistry(
          VelocityCompatTests.configuration(List.of(ncr.server("a"), never))), settings)) {
        require(!allSafe.everyBackendPreventsChatReports(), "nothing is claimed before any backend has answered");
        mixed.probeOnce(); allSafe.probeOnce(); unanswered.probeOnce();
        require(!mixed.everyBackendPreventsChatReports(), "one backend without it is enough to say nothing");
        require(!unanswered.everyBackendPreventsChatReports(), "nor is a backend that has never answered taken on trust");
        require(allSafe.everyBackendPreventsChatReports(), "every backend saying it is");
      }
    }

    ServerListPingEvent ping = new ServerListPingEvent(new InetSocketAddress("127.0.0.1", 1), java.util.Optional.empty(), 25565, 769,
        Text.of("motd"), 20, 0, List.of(), "Conduit", 769, java.util.Optional.empty());
    var table = gg.tame.conduit.protocol.ProtocolDefinition.forVersion(765);
    require(answerOf(StatusResponder.response(table, new byte[] {0x00}, ping, true)).get("preventsChatReports") == Boolean.TRUE,
        "the proxy's own answer carries it when claimed");
    require(!answerOf(StatusResponder.response(table, new byte[] {0x00}, ping, false)).containsKey("preventsChatReports"),
        "and leaves it out otherwise");
  }

  private static java.util.Map<?, ?> answerOf(byte[] packet) throws Exception {
    String json = gg.tame.conduit.protocol.MinecraftInput.string(
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet, 1, packet.length - 1)), 1 << 16);
    return (java.util.Map<?, ?>) gg.tame.conduit.protocol.text.ComponentCodec.parseJson(json);
  }

  // ---------------------------------------------------------------- Velocity

  private static final String VPING = """
      package vping;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.network.ProtocolVersion;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.server.PingOptions;
      import com.velocitypowered.api.proxy.server.RegisteredServer;
      import com.velocitypowered.api.proxy.server.ServerInfo;
      import com.velocitypowered.api.proxy.server.ServerPing;
      import com.velocitypowered.api.util.Favicon;
      import java.net.InetSocketAddress;
      import java.time.Duration;
      import javax.inject.Inject;
      import net.kyori.adventure.text.format.NamedTextColor;
      import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

      @Plugin(id = "vping", name = "VPing", version = "1.0")
      public final class VPing {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
        private final ProxyServer proxy;
        @Inject public VPing(ProxyServer proxy) { this.proxy = proxy; }

        @Subscribe public void init(ProxyInitializeEvent event) {
          proxy.getScheduler().buildTask(this, () -> {
            RegisteredServer rich = proxy.getServer("rich").orElseThrow();
            rich.ping().whenComplete((ping, failed) -> signal("default:" + describe(ping, failed)));
            rich.ping(PingOptions.builder().version(ProtocolVersion.MINECRAFT_1_20_3).virtualHost("velocity.example")
                .timeout(Duration.ofSeconds(3)).build()).whenComplete((ping, failed) -> signal("options:" + describe(ping, failed)));
            RegisteredServer dead = proxy.registerServer(new ServerInfo("dead", new InetSocketAddress("127.0.0.1", Integer.getInteger("vping.dead"))));
            dead.ping().whenComplete((ping, failed) -> signal("dead:" + (failed == null ? "answered" : failed.getCause().getClass().getSimpleName())));
            long asked = System.nanoTime();
            proxy.getServer("silent").orElseThrow().ping(PingOptions.builder().timeout(Duration.ofMillis(300)).build())
                .whenComplete((ping, failed) -> signal("silent:" + (failed != null) + ":" + ((System.nanoTime() - asked) / 1_000_000 < 3_000)));
          }).schedule();
        }

        static String describe(ServerPing ping, Throwable failed) {
          if (failed != null) return "failed " + failed;
          ServerPing.Players players = ping.getPlayers().orElseThrow();
          return ping.getVersion().getProtocol() + "|" + ping.getVersion().getName() + "|" + players.getOnline() + "/" + players.getMax()
              + "|" + players.getSample().stream().map(p -> p.getName() + "=" + p.getId()).toList()
              + "|" + PlainTextComponentSerializer.plainText().serialize(ping.getDescriptionComponent())
              + "|" + (ping.getDescriptionComponent().color() == NamedTextColor.GOLD)
              + "|" + ping.getFavicon().map(Favicon::getBase64Url).orElse("none") + "|" + ping.getModinfo().isPresent()
              + "|" + Thread.currentThread().getName().startsWith("conduit-velocity-");
        }
      }
      """;

  private static void velocityPluginsGetAServerPing() throws Exception {
    VelocityCompatTests.installSignals();
    Path root = TempFiles.dir("backend-ping-velocity");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    VelocityCompatTests.jar(VelocityCompatTests.compile(root, "vping.VPing", VPING, List.of(), true), plugins.resolve("VPing.jar"), null);
    System.setProperty("vping.dead", Integer.toString(reservePort()));
    try (Backend rich = new Backend(json(RICH)); Backend silent = new Backend(SILENT)) {
      ConduitConfiguration configuration = VelocityCompatTests.configuration(List.of(rich.server("rich"), silent.server("silent")));
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
      Thread serving = Thread.ofPlatform().daemon().name("backend-ping-serve").start(() -> { try { proxy.serve(); } catch (IOException ignored) { } });
      try {
        String answer = "769|Paper 1.21.4|2/50|[Alice=" + ALICE + "]|Hello world|true|" + ICON + "|false|true";
        VelocityCompatTests.awaitSignal("default:" + answer);
        VelocityCompatTests.awaitSignal("options:" + answer);
        VelocityCompatTests.awaitSignal("dead:IOException");
        VelocityCompatTests.awaitSignal("silent:true:true");
        List<Handshake> asked = List.copyOf(rich.handshakes);
        require(asked.stream().anyMatch(h -> h.protocolVersion() == -1 && h.requestedHost().equals("127.0.0.1"))
            && asked.stream().anyMatch(h -> h.protocolVersion() == 765 && h.requestedHost().equals("velocity.example")),
            "PingOptions' version and virtual host reach the backend, got " + asked);
      } finally {
        proxy.close();
        serving.join(10_000);
      }
    }
  }

  // ---------------------------------------------------------------- scripted backends

  /** What a backend does once it has read a status handshake and request. */
  private interface Answer { void serve(Socket socket) throws Exception; }

  private static final Answer SILENT = socket -> socket.getInputStream().read();

  private static Answer json(String json) {
    return socket -> {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(out, 0);
        MinecraftOutput.string(out, json);
      }
      MinecraftFrames.write(socket.getOutputStream(), bytes.toByteArray());
    };
  }

  /** Answers every status request its own way, recording the handshakes and how many were open at once. */
  private static final class Backend implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 200);
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final Queue<Handshake> handshakes = new ConcurrentLinkedQueue<>();
    final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();

    Backend(Answer answer) throws IOException {
      Thread.ofPlatform().daemon().name("ping-backend").start(() -> {
        while (!listener.isClosed()) {
          Socket socket;
          try { socket = listener.accept(); } catch (IOException closed) { return; }
          sockets.add(socket);
          Thread.ofPlatform().daemon().name("ping-backend-session").start(() -> {
            peak.accumulateAndGet(open.incrementAndGet(), Math::max);
            try (socket) {
              handshakes.add(Handshake.decode(MinecraftFrames.read(socket.getInputStream(), 4096)));
              MinecraftFrames.read(socket.getInputStream(), 16);
              answer.serve(socket);
            } catch (Exception ended) {
            } finally {
              open.decrementAndGet();
            }
          });
        }
      });
    }
    InetSocketAddress address() { return new InetSocketAddress("127.0.0.1", listener.getLocalPort()); }
    BackendServer server(String name) { return new BackendServer(name, address()); }
    @Override public void close() throws IOException {
      listener.close();
      for (Socket socket : sockets) socket.close();
    }
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import com.sun.net.httpserver.HttpServer;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.ops.ConduitUpdateCheck;
import gg.tame.conduit.security.AttackModeService;
import gg.tame.conduit.security.BotFilter;
import gg.tame.conduit.security.ConnectionThrottle;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Automatic attack mode, the Conduit release notice, and the uptime and memory gauges. */
public final class OpsExtrasTests {
  private OpsExtrasTests() {}

  public static void main(String[] args) throws Exception { run(); }

  public static void run() throws Exception {
    automaticAttackMode();
    knownSourcesOnly();
    versionOrder();
    updateCheckAgainstALocalServer();
    newKeysLoad();
    gaugesAreScraped();
    System.out.println("OpsExtrasTests passed.");
  }

  private static AttackModeService attack(int tripPerSecond, boolean knownOnly) {
    ConnectionThrottle throttle = new ConnectionThrottle(SecuritySettings.ThrottleSettings.defaults());
    BotFilter filter = new BotFilter(SecuritySettings.BotFilterSettings.defaults());
    return new AttackModeService(throttle, filter, new SecuritySettings.AttackModeSettings(8, 3, tripPerSecond, knownOnly));
  }

  private static void automaticAttackMode() {
    AttackModeService mode = attack(5, false);
    long t = 100_000_000_000L;
    for (int i = 0; i < 5; i++) mode.recordConnection(t + i);
    require(!mode.isActive(), "five in a second is the limit, not over it");
    mode.recordConnection(t + 10);
    require(mode.isActive() && mode.isAutomatic(), "the sixth trips it");
    mode.recordConnection(t + 30_000_000_000L);
    require(mode.isActive(), "half a minute later it is still on");
    mode.recordConnection(t + 61_000_000_000L);
    require(!mode.isActive() && !mode.isAutomatic(), "a quiet minute lifts it");

    AttackModeService off = attack(0, false);
    for (int i = 0; i < 1_000; i++) off.recordConnection(t + i);
    require(!off.isActive(), "0 never trips");

    AttackModeService manual = attack(5, false);
    require(manual.enable(), "switched on by hand");
    manual.recordConnection(t + 120_000_000_000L);
    require(manual.isActive() && !manual.isAutomatic(), "a quiet rate never lifts what a command switched on");
  }

  private static void knownSourcesOnly() throws Exception {
    AttackModeService mode = attack(0, true);
    InetAddress stranger = InetAddress.getByName("203.0.113.5");
    InetAddress regular = InetAddress.getByName("2001:db8:1:2::1");
    require(mode.admitsLogin(stranger), "anyone may log in while attack mode is off");
    mode.recordLogin(regular);
    mode.enable();
    require(!mode.admitsLogin(stranger), "a new source is turned away during an attack");
    require(mode.admitsLogin(InetAddress.getByName("2001:db8:1:2::77")), "a returning /64 is let in");
    AttackModeService open = attack(0, false);
    open.enable();
    require(open.admitsLogin(stranger), "known-sources-only = false leaves logins alone");
  }

  private static void versionOrder() {
    require(ConduitUpdateCheck.compare("v0.9.9", "0.9.8") > 0, "patch");
    require(ConduitUpdateCheck.compare("0.10.0", "0.9.8") > 0, "numeric, not text");
    require(ConduitUpdateCheck.compare("1.0", "1.0.0") == 0, "missing parts are zero");
    require(ConduitUpdateCheck.compare("1.0.0", "1.0.0-rc.1") > 0, "a release outranks its pre-release");
    require(ConduitUpdateCheck.compare("1.0.0+abc", "1.0.0") == 0, "build metadata is ignored");
    require(ConduitUpdateCheck.compare("0.9.7", "0.9.8") < 0, "older");
  }

  private static void updateCheckAgainstALocalServer() throws Exception {
    String[] body = {"{\"tag_name\":\"v99.0.0\",\"name\":\"x\"}"};
    int[] status = {200};
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/latest", exchange -> {
      byte[] bytes = body[0].getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status[0], bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/latest";
      require(ConduitUpdateCheck.newer(url, "0.9.8", 2_000).equals(Optional.of("v99.0.0")), "a newer release is reported");
      body[0] = "{\"tag_name\": \"v0.9.8\"}";
      require(ConduitUpdateCheck.newer(url, "0.9.8", 2_000).isEmpty(), "the same release is not");
      status[0] = 404;
      try {
        ConduitUpdateCheck.newer(url, "0.9.8", 2_000);
        throw new AssertionError("a 404 is a failed check");
      } catch (IOException expected) { }
    } finally {
      server.stop(0);
    }
  }

  private static void newKeysLoad() throws Exception {
    Path directory = TempFiles.dir("conduit-ops-extras");
    Path file = directory.resolve("conduit.toml");
    gg.tame.conduit.config.ConfigBootstrap.ensure(file, true);
    ConduitConfiguration defaults = ConfigurationLoader.load(file);
    require(defaults.ops().updates().conduit(), "the release notice is on by default");
    require(defaults.ops().security().attackMode().autoTripPerSecond() == 0 && !defaults.ops().security().attackMode().knownSourcesOnly(),
        "automatic attack mode is off by default");
    Files.writeString(file, Files.readString(file) + "\n[security.attack-mode]\nauto-trip-per-second = 250\nknown-sources-only = true\n[updates]\nconduit = false\n");
    ConduitConfiguration set = ConfigurationLoader.load(file);
    require(!set.ops().updates().conduit(), "updates.conduit is read");
    require(set.ops().security().attackMode().autoTripPerSecond() == 250 && set.ops().security().attackMode().knownSourcesOnly(),
        "the attack-mode keys are read");
  }

  private static void gaugesAreScraped() throws Exception {
    OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2),
        null, null, null, null, null, null, null);
    ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", 25565), 1 << 20,
        ForwardingMode.NONE, Optional.empty(), List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 25566))),
        List.of("lobby"), List.of("lobby"), AuthenticationSettings.offline(), Optional.empty(), ops);
    Path root = TempFiles.dir("conduit-ops-extras-metrics");
    try (var runtime = new gg.tame.conduit.runtime.ConduitRuntime(configuration, root.resolve("plugins"), root)) {
      String body = gg.tame.conduit.metrics.PrometheusEndpoint.render(runtime);
      require(body.contains("\nconduit_attack_mode_active 0\n"), "attack mode gauge:\n" + body);
      require(body.contains("\nconduit_uptime_seconds "), "uptime gauge");
      require(body.contains("\nconduit_jvm_memory_used_bytes ") && body.contains("\nconduit_jvm_memory_max_bytes "), "memory gauges");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

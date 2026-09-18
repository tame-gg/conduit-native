package gg.tame.conduit.tests;

import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.ConfigMigrator;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.VersionGateSettings;
import gg.tame.conduit.health.BackendHealth;
import gg.tame.conduit.health.BackendHealthService;
import gg.tame.conduit.ops.MaintenanceService;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.version.VersionGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/** Phase 11 — Ops: maintenance, health hysteresis, drain, version gate, reload, doctor. */
public final class Phase11OpsTests {
  private Phase11OpsTests() {}

  public static void run() throws Exception {
    maintenancePersistenceAndBypass();
    healthHysteresis();
    drainAndFallback();
    versionGate();
    configMigrationAndReload();
    doctorAndDiagnosticsCommands();
    System.out.println("Phase11OpsTests passed.");
  }

  private static void maintenancePersistenceAndBypass() throws Exception {
    Path dir = TempFiles.dir("conduit-maint");
    Path flag = dir.resolve("maintenance.flag");
    var settings = gg.tame.conduit.config.MaintenanceSettings.defaults().withAllowlist(List.of("Kyle"));
    MaintenanceService service = new MaintenanceService(dir, settings);
    require(!service.isActive(), "default inactive");
    service.enable();
    require(service.isActive(), "enabled");
    require(Files.isRegularFile(flag), "flag written");
    require(service.allows("Kyle", false), "allowlist bypass");
    require(!service.allows("Steve", false), "non-allowlist denied");
    require(service.allows("Steve", true), "permission bypass");
    service.disable();
    require(!service.isActive(), "disabled");
    require(!Files.isRegularFile(flag), "flag removed");
    Files.writeString(flag, "active\n");
    MaintenanceService restored = new MaintenanceService(dir, settings);
    require(restored.isActive(), "restored from flag");
  }

  private static void healthHysteresis() throws Exception {
    Path config = TempFiles.file("conduit-health", ".toml");
    Files.writeString(config, baseToml());
    ServerRegistry registry = new ServerRegistry(ConfigurationLoader.load(config));
    BackendHealthService health = new BackendHealthService(registry, new HealthSettings(true, 10_000, 1_500, 3, 2));
    require(health.snapshot("lobby").health() == BackendHealth.UNKNOWN, "start unknown");
    health.applyProbeResult("lobby", false);
    health.applyProbeResult("lobby", false);
    require(health.snapshot("lobby").health() == BackendHealth.UNKNOWN, "two failures still unknown/not unhealthy");
    health.applyProbeResult("lobby", false);
    require(health.snapshot("lobby").health() == BackendHealth.UNHEALTHY, "three failures unhealthy");
    health.applyProbeResult("lobby", true);
    require(health.snapshot("lobby").health() == BackendHealth.UNHEALTHY, "one success still unhealthy");
    health.applyProbeResult("lobby", true);
    require(health.snapshot("lobby").health() == BackendHealth.HEALTHY, "two successes healthy");
  }

  private static void drainAndFallback() throws Exception {
    Path config = TempFiles.file("conduit-drain", ".toml");
    Files.writeString(config, baseToml());
    var loaded = ConfigurationLoader.load(config);
    BackendHealthService health = new BackendHealthService(new ServerRegistry(loaded), HealthSettings.defaults());
    health.applyProbeResult("lobby", true);
    health.applyProbeResult("lobby", true);
    health.applyProbeResult("survival", true);
    health.applyProbeResult("survival", true);
    BackendSelector selector = new BackendSelector(loaded, health);
    require(health.drain("survival"), "drain survival");
    require(health.isDraining("survival"), "draining flag");
    List<gg.tame.conduit.config.BackendServer> fallback = selector.fallback("lobby", new HashSet<>(), 765, false);
    require(fallback.stream().noneMatch(s -> s.name().equalsIgnoreCase("survival")), "draining excluded");
    fallback = selector.fallback("lobby", new HashSet<>(), 765, true);
    require(fallback.stream().anyMatch(s -> s.name().equalsIgnoreCase("survival")), "bypass may include draining");
    health.applyProbeResult("survival", false);
    health.applyProbeResult("survival", false);
    health.applyProbeResult("survival", false);
    health.undrain("survival");
    fallback = selector.fallback("lobby", Set.of(), 765, false);
    require(fallback.stream().noneMatch(s -> s.name().equalsIgnoreCase("survival")), "unhealthy excluded");
  }

  private static void versionGate() {
    VersionGate gate = new VersionGate(new VersionGateSettings(true, Set.of(765), OptionalInt.empty(), OptionalInt.empty(),
        VersionGateSettings.DEFAULT_PING, VersionGateSettings.DEFAULT_KICK, VersionGateSettings.DEFAULT_KICK_RANGE, true));
    require(gate.allows(765), "allow listed");
    require(!gate.allows(776), "reject unlisted");
    require(gate.kickMessage().contains("1.20.4"), "kick mentions version");
    VersionGate range = new VersionGate(new VersionGateSettings(true, Set.of(), OptionalInt.of(765), OptionalInt.of(776),
        VersionGateSettings.DEFAULT_PING, VersionGateSettings.DEFAULT_KICK, VersionGateSettings.DEFAULT_KICK_RANGE, false));
    require(range.allows(765) && range.allows(776), "range allows bounds");
    require(!range.allows(763), "range rejects below");
  }

  private static void configMigrationAndReload() throws Exception {
    Path config = TempFiles.file("conduit-migrate", ".toml");
    Files.writeString(config, baseToml());
    var result = ConfigMigrator.migrate(config);
    require(result.changed(), "migration added keys");
    require(result.addedKeys().contains("ops.schema-version"), "schema key");
    var loaded = ConfigurationLoader.load(config);
    require(loaded.health().failureThreshold() == 3, "health defaults loaded");
    Path plugins = TempFiles.dir("conduit-plugins-ops");
    ConduitRuntime runtime = new ConduitRuntime(loaded, plugins, config.getParent());
    runtime.bindConfigPath(config);
    Files.writeString(config, Files.readString(config) + "\n[listener]\n# keep\n");
    // invalid partial append shouldn't happen — instead change health threshold live
    String body = Files.readString(config);
    body = body.replace("failure-threshold = 3", "failure-threshold = 4");
    Files.writeString(config, body);
    var reload = runtime.reload();
    require(reload.applied(), "reload applied");
    require(runtime.health().settings().failureThreshold() == 4, "live health threshold");
    require(reload.error() == null, "no reload error");
    runtime.close();
  }

  private static void doctorAndDiagnosticsCommands() throws Exception {
    Path config = TempFiles.file("conduit-doc", ".toml");
    Files.writeString(config, baseToml());
    var loaded = ConfigurationLoader.load(config);
    Path plugins = TempFiles.dir("conduit-plugins-doc");
    ConduitRuntime runtime = new ConduitRuntime(loaded, plugins, config.getParent());
    runtime.bindConfigPath(config);
    CoreCommands.register(runtime);
    AdminSource admin = new AdminSource("Op", "lobby", Set.of(
        Permissions.CONDUIT_ADMIN, Permissions.CONDUIT_INFO, Permissions.HEALTH, Permissions.DOCTOR,
        Permissions.DIAGNOSTICS, Permissions.MAINTENANCE, Permissions.DRAIN, Permissions.RELOAD));
    runtime.commandManager().dispatch(admin, "/conduit doctor");
    require(admin.messages.stream().anyMatch(line -> line.contains("OK") || line.contains("WARNING")), "doctor output");
    admin.messages.clear();
    runtime.commandManager().dispatch(admin, "/conduit diagnostics");
    require(admin.messages.stream().anyMatch(line -> line.contains("Conduit diagnostics")), "diagnostics heading");
    require(admin.messages.stream().noneMatch(line -> line.toLowerCase().contains("secret")), "no secrets");
    admin.messages.clear();
    runtime.commandManager().dispatch(admin, "/conduit maintenance on");
    require(runtime.maintenance().isActive(), "maintenance on");
    runtime.commandManager().dispatch(admin, "/conduit maintenance status");
    require(admin.messages.stream().anyMatch(line -> line.contains("ON")), "status on");
    runtime.commandManager().dispatch(admin, "/conduit drain survival");
    require(runtime.health().isDraining("survival"), "drain command");
    runtime.commandManager().dispatch(admin, "/conduit undrain survival");
    require(!runtime.health().isDraining("survival"), "undrain command");
    runtime.commandManager().dispatch(admin, "/conduit health");
    require(admin.messages.stream().anyMatch(line -> line.contains("Conduit backend health")), "health heading");
    runtime.close();
  }

  private static String baseToml() {
    return """
        [listener]
        host="127.0.0.1"
        port=25565
        max-frame-bytes=64
        [forwarding]
        mode="none"
        [servers.lobby]
        host="127.0.0.1"
        port=1
        [servers.survival]
        host="127.0.0.1"
        port=2
        [routing]
        initial=["lobby"]
        fallback=["lobby","survival"]
        """;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class AdminSource implements CommandSource {
    private final String username;
    private final String backend;
    private final Set<String> permissions;
    private final List<String> messages = new ArrayList<>();
    private AdminSource(String username, String backend, Set<String> permissions) {
      this.username = username; this.backend = backend; this.permissions = permissions;
    }
    @Override public String username() { return username; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public String currentBackend() { return backend; }
  }
}

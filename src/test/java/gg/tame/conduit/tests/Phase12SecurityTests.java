package gg.tame.conduit.tests;

import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.security.BotFilter;
import gg.tame.conduit.security.ChannelGuard;
import gg.tame.conduit.security.ConnectionThrottle;
import gg.tame.conduit.security.SourceKey;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phase 12 — Security: throttle, bot filter, channel guard, attack mode. */
public final class Phase12SecurityTests {
  private Phase12SecurityTests() {}

  public static void run() throws Exception {
    sourceKeyGrouping();
    connectionThrottle();
    botFilter();
    channelGuard();
    attackModeCommands();
    System.out.println("Phase12SecurityTests passed.");
  }

  private static void sourceKeyGrouping() throws Exception {
    InetAddress a = InetAddress.getByName("203.0.113.10");
    InetAddress b = InetAddress.getByName("203.0.113.11");
    require(SourceKey.of(a, 32, 64).equals(SourceKey.of(a, 32, 64)), "same ipv4 host");
    require(!SourceKey.of(a, 32, 64).equals(SourceKey.of(b, 32, 64)), "distinct /32");
    require(SourceKey.of(a, 24, 64).equals(SourceKey.of(b, 24, 64)), "same /24");
    InetAddress v6a = InetAddress.getByName("2001:db8:1:2::1");
    InetAddress v6b = InetAddress.getByName("2001:db8:1:2::99");
    InetAddress v6c = InetAddress.getByName("2001:db8:1:3::1");
    require(SourceKey.of(v6a, 32, 64).equals(SourceKey.of(v6b, 32, 64)), "ipv6 /64 groups");
    require(!SourceKey.of(v6a, 32, 64).equals(SourceKey.of(v6c, 32, 64)), "ipv6 different /64");
  }

  private static void connectionThrottle() throws Exception {
    ConnectionThrottle throttle = new ConnectionThrottle(new SecuritySettings.ThrottleSettings(true, 3, 1_000, 8, 32, 64, 5_000));
    InetAddress source = InetAddress.getByName("198.51.100.7");
    ConnectionThrottle.LeaseHolder holder = new ConnectionThrottle.LeaseHolder();
    require(throttle.tryAdmit(source, holder) == ConnectionThrottle.Decision.ALLOW, "admit 1");
    throttle.release(holder.lease);
    require(throttle.tryAdmit(source, holder) == ConnectionThrottle.Decision.ALLOW, "admit 2");
    throttle.release(holder.lease);
    require(throttle.tryAdmit(source, holder) == ConnectionThrottle.Decision.ALLOW, "admit 3");
    throttle.release(holder.lease);
    require(throttle.tryAdmit(source, holder) == ConnectionThrottle.Decision.THROTTLED, "admit 4 blocked");
    ConnectionThrottle disabled = new ConnectionThrottle(new SecuritySettings.ThrottleSettings(false, 1, 1_000, 1, 32, 64, 5_000));
    require(disabled.tryAdmit(source, holder) == ConnectionThrottle.Decision.DISABLED, "disabled admits");
  }

  private static void botFilter() throws Exception {
    BotFilter filter = new BotFilter(new SecuritySettings.BotFilterSettings(true, 3, 500, 2_000, 60_000));
    InetAddress source = InetAddress.getByName("198.51.100.8");
    require(!filter.isBlocked(source), "start clean");
    filter.recordStatusPing(source);
    require(!filter.isBlocked(source), "status is legitimate");
    filter.recordSuspicious(source, "idle");
    filter.recordSuspicious(source, "idle");
    require(!filter.isBlocked(source), "below threshold");
    filter.recordSuspicious(source, "idle");
    require(filter.isBlocked(source), "blocked after threshold");
    filter.unblock(source);
    require(!filter.isBlocked(source), "manual unblock");
  }

  private static void channelGuard() {
    Map<String, SecuritySettings.ChannelAction> rules = new LinkedHashMap<>();
    rules.put("wdl:init", SecuritySettings.ChannelAction.DROP);
    rules.put("evil:kick", SecuritySettings.ChannelAction.KICK);
    rules.put("noise:log", SecuritySettings.ChannelAction.LOG);
    ChannelGuard guard = new ChannelGuard(new SecuritySettings.ChannelGuardSettings(true, SecuritySettings.ChannelAction.LOG, rules));
    require(guard.inspect("minecraft:brand", "Kyle") == ChannelGuard.Outcome.ALLOW, "brand allowed");
    require(guard.inspect("unknown:channel", "Kyle") == ChannelGuard.Outcome.ALLOW, "unknown allowed");
    require(guard.inspect("WDL:Init", "Kyle") == ChannelGuard.Outcome.DROP, "normalized drop");
    require(guard.inspect("evil:kick", "Kyle") == ChannelGuard.Outcome.KICK, "kick");
    require(guard.inspect("noise:log", "Kyle") == ChannelGuard.Outcome.ALLOW, "log still allows");
    ChannelGuard off = new ChannelGuard(new SecuritySettings.ChannelGuardSettings(false, SecuritySettings.ChannelAction.DROP, rules));
    require(off.inspect("wdl:init", "Kyle") == ChannelGuard.Outcome.ALLOW, "disabled allows");
  }

  private static void attackModeCommands() throws Exception {
    Path config = TempFiles.file("conduit-sec", ".toml");
    Files.writeString(config, """
        [listener]
        host="127.0.0.1"
        port=25565
        max-frame-bytes=64
        [forwarding]
        mode="none"
        [servers.lobby]
        host="127.0.0.1"
        port=1
        [routing]
        initial=["lobby"]
        fallback=["lobby"]
        """);
    var loaded = ConfigurationLoader.load(config);
    Path plugins = TempFiles.dir("conduit-plugins-sec");
    ConduitRuntime runtime = new ConduitRuntime(loaded, plugins, config.getParent());
    runtime.bindConfigPath(config);
    CoreCommands.register(runtime);
    AdminSource admin = new AdminSource("Op", "lobby", Set.of(
        Permissions.ATTACK, Permissions.CONDUIT_ADMIN, Permissions.CONDUIT_INFO,
        Permissions.DIAGNOSTICS, Permissions.DOCTOR));
    require(runtime.security().throttle().effectiveMaxAttempts() == 40, "normal throttle");
    runtime.commandManager().dispatch(admin, "/conduit attack on");
    require(runtime.security().attackMode().isActive(), "attack on");
    require(runtime.security().throttle().effectiveMaxAttempts() == 8, "attack throttle");
    require(runtime.security().botFilter().effectiveThreshold() == 3, "attack bot threshold");
    admin.messages.clear();
    runtime.commandManager().dispatch(admin, "/conduit attack status");
    require(admin.messages.stream().anyMatch(line -> line.contains("ON")), "status on");
    runtime.commandManager().dispatch(admin, "/conduit attack off");
    require(!runtime.security().attackMode().isActive(), "attack off");
    require(runtime.security().throttle().effectiveMaxAttempts() == 40, "restored throttle");
    admin.messages.clear();
    runtime.commandManager().dispatch(admin, "/conduit doctor");
    require(admin.messages.stream().anyMatch(line -> line.contains("Connection throttle")), "doctor security");
    require(admin.messages.stream().noneMatch(line -> line.toLowerCase().contains("secret")), "no secrets");
    runtime.close();
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

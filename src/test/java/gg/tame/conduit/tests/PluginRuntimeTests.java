// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.scheduler.ScheduledTask;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.scheduler.ConduitScheduler;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The plugin runtime under the lifecycle races the other suites do not reach: plugin code still
 * running when its plugin is disabled, tasks that never return, tasks that fail every run, and
 * dependencies that cannot be satisfied. Plugins are real jars, loaded by the real plugin manager.
 */
public final class PluginRuntimeTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    aDisabledPluginsLingeringTaskCannotRegisterAnything();
    aFailedEnableLeavesNothingRegistered();
    proxyShutdownEndsTasksThatNeverReturn();
    aTaskFailingEveryRunIsLoggedSparingly();
    unloadableDependenciesAreNamed();
    System.out.println("PluginRuntimeTests OK");
  }

  // Plugin code reaches these through the parent class loader, which a native plugin's loader shares.
  public static final List<String> SIGNALS = Collections.synchronizedList(new ArrayList<>());
  public static final CountDownLatch RELEASE_LATE = new CountDownLatch(1);
  public static void signal(String value) { SIGNALS.add(value); }

  /**
   * A task still running when its plugin was disabled registered a listener, a command and a
   * permission provider after the disable had swept them, and all three outlived the plugin: the
   * listener heard every later event and the command stayed typeable, both running code from a
   * closed class loader, and the provider answered every permission check from then on.
   */
  private static void aDisabledPluginsLingeringTaskCannotRegisterAnything() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-late");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    CommandApiTests.buildPluginJar(plugins.resolve("late.jar"), "late", "late.LatePlugin", 1, """
        package late;
        import gg.tame.conduit.api.command.CommandManager;
        import gg.tame.conduit.api.event.Subscribe;
        import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
        import gg.tame.conduit.tests.PluginRuntimeTests;
        public final class LatePlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
          public static final class Ears {
            @Subscribe public void heard(ServerListPingEvent event) { PluginRuntimeTests.signal("late-heard"); }
          }
          @Override public void onEnable() {
            getScheduler().buildTask(this, () -> {
              // Built before the wait: once disabled, the plugin's closed class loader can load no
              // class it has not loaded yet, and a long-running plugin has loaded these long since.
              Runnable[] late = {
                () -> proxy().events().register(this, new Ears()),
                () -> proxy().commands().register(this, CommandManager.Command.builder("late").handler((source, args) -> { }).build()),
                () -> proxy().setPermissionProvider(this, (subject, node) -> false),
                () -> getScheduler().buildTask(this, () -> { }).schedule()
              };
              new Ears();
              CommandManager.Command.builder("warmup").build();
              String[] names = { "listener", "command", "provider", "task" };
              PluginRuntimeTests.signal("late-running");
              try { PluginRuntimeTests.RELEASE_LATE.await(); } catch (InterruptedException stop) { return; }
              for (int i = 0; i < late.length; i++) attempt(names[i], late[i]);
              PluginRuntimeTests.signal("late-done");
            }).schedule();
          }
          private static void attempt(String what, Runnable action) {
            try { action.run(); PluginRuntimeTests.signal("accepted:" + what); }
            catch (IllegalStateException refused) { PluginRuntimeTests.signal("refused:" + what); }
          }
        }
        """);
    SIGNALS.clear();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      require(await(() -> SIGNALS.contains("late-running")), "the plugin's task is running");
      runtime.plugins().disable(runtime.plugins().plugin("late").orElseThrow());
      RELEASE_LATE.countDown();
      require(await(() -> SIGNALS.contains("late-done")), "the lingering task finished, signals " + SIGNALS);
      for (String what : List.of("listener", "command", "provider", "task")) {
        require(SIGNALS.contains("refused:" + what), "a disabled plugin's " + what + " is refused, signals " + SIGNALS);
      }
      require(runtime.commandManager().get("late").isEmpty(), "no command of the disabled plugin is live");
      require(runtime.permissions() instanceof gg.tame.conduit.permission.DefaultPermissionProvider,
          "Conduit's default provider answers again");
      runtime.events().fire(ping());
      require(!SIGNALS.contains("late-heard"), "no listener of the disabled plugin hears events");
    } finally {
      runtime.close();
    }
  }

  /**
   * A plugin that registered a listener, a command, a provider and a repeating task and then threw in
   * onEnable is not enabled, and nothing it registered on the way may stay behind.
   */
  private static void aFailedEnableLeavesNothingRegistered() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-halfway");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    CommandApiTests.buildPluginJar(plugins.resolve("halfway.jar"), "halfway", "halfway.HalfwayPlugin", 1, """
        package halfway;
        import gg.tame.conduit.api.command.CommandManager;
        import gg.tame.conduit.api.event.Subscribe;
        import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
        import gg.tame.conduit.tests.PluginRuntimeTests;
        public final class HalfwayPlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
          public static final class Ears {
            @Subscribe public void heard(ServerListPingEvent event) { PluginRuntimeTests.signal("halfway-heard"); }
          }
          @Override public void onEnable() {
            proxy().events().register(this, new Ears());
            proxy().commands().register(this, CommandManager.Command.builder("halfway").handler((source, args) -> { }).build());
            proxy().setPermissionProvider(this, (subject, node) -> false);
            getScheduler().buildTask(this, () -> PluginRuntimeTests.signal("halfway-tick")).repeat(java.time.Duration.ofMillis(5)).schedule();
            throw new IllegalStateException("halfway through enabling");
          }
        }
        """);
    SIGNALS.clear();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      require(runtime.plugins().plugin("halfway").isEmpty(), "the plugin is not enabled");
      require(runtime.commandManager().get("halfway").isEmpty(), "its command went");
      require(runtime.permissions() instanceof gg.tame.conduit.permission.DefaultPermissionProvider, "its provider went");
      Thread.sleep(50);
      SIGNALS.clear();
      Thread.sleep(100);
      require(!SIGNALS.contains("halfway-tick"), "its task no longer runs, signals " + SIGNALS);
      runtime.events().fire(ping());
      require(!SIGNALS.contains("halfway-heard"), "its listener went");
    } finally {
      runtime.close();
    }
    require(deletable(plugins.resolve("halfway.jar")), "its jar was released");
  }

  /**
   * A disabled plugin's pool was forgotten once its running tasks had been let finish, so a task that
   * never finished -- a loop that sleeps, a wait on a queue nobody fills -- kept its thread after the
   * proxy had shut down, since the shutdown could no longer reach that pool to interrupt it.
   */
  private static void proxyShutdownEndsTasksThatNeverReturn() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-sleeper");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    CommandApiTests.buildPluginJar(plugins.resolve("sleeper.jar"), "sleeper", "sleeper.SleeperPlugin", 1, """
        package sleeper;
        public final class SleeperPlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
          @Override public void onEnable() {
            for (int i = 0; i < 2; i++) {
              getScheduler().buildTask(this, () -> {
                gg.tame.conduit.tests.PluginRuntimeTests.signal("sleeping");
                while (true) {
                  try { Thread.sleep(20); } catch (InterruptedException stop) { return; }
                }
              }).schedule();
            }
          }
        }
        """);
    SIGNALS.clear();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      require(await(() -> List.copyOf(SIGNALS).stream().filter("sleeping"::equals).count() == 2), "both tasks are running");
      require(threads("conduit-plugin-sleeper-") == 2, "on the plugin's own threads");
    } finally {
      runtime.close();
    }
    require(await(() -> threads("conduit-plugin-sleeper-") == 0),
        "no thread of the plugin outlives the shutdown, left " + threads("conduit-plugin-sleeper-"));
  }

  /**
   * A repeating task that failed on every run printed a full stack trace every run: at a short
   * interval that buried the rest of the log. It still runs every time, but the log says it once
   * with the trace, then only counts, at the 2nd, 4th, 8th... failure in a row.
   */
  private static void aTaskFailingEveryRunIsLoggedSparingly() throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    AtomicInteger runs = new AtomicInteger();
    try (ConduitScheduler scheduler = new ConduitScheduler()) {
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      Plugin plugin = new NamedPlugin("flaky");
      ScheduledTask task = scheduler.buildTask(plugin, () -> {
        runs.incrementAndGet();
        throw new IllegalStateException("broken every time");
      }).repeat(Duration.ofMillis(1)).schedule();
      require(await(() -> runs.get() >= 40), "the failing task keeps repeating, ran " + runs.get());
      task.cancel();
      Thread.sleep(50);
    } finally {
      System.setErr(original);
    }
    String log = captured.toString(StandardCharsets.UTF_8);
    long lines = log.lines().filter(line -> line.contains("plugin task failed") && line.contains("flaky")).count();
    long traces = log.lines().filter(line -> line.startsWith("java.lang.IllegalStateException: broken every time")).count();
    int ran = runs.get();
    require(lines <= 1 + Integer.SIZE - Integer.numberOfLeadingZeros(ran), ran + " failures logged in " + lines + " lines");
    require(traces == 1, "one stack trace for the whole run of failures, got " + traces);
    require(log.contains("times in a row: flaky"), "later failures are counted, log:\n" + log);
  }

  /**
   * Plugins whose dependencies could not be satisfied were reported together in one line that said
   * only that they were unresolved: a dependency never installed and two plugins depending on each
   * other read the same. Each is now named with its reason, and an unrelated plugin still loads.
   */
  private static void unloadableDependenciesAreNamed() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-deps");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    CommandApiTests.buildPluginJar(plugins.resolve("needy.jar"), "needy", "needy.NeedyPlugin", 1, empty("needy", "Needy"), "depend: [ghost]\n");
    CommandApiTests.buildPluginJar(plugins.resolve("ping.jar"), "ping", "ping.PingPlugin", 1, empty("ping", "Ping"), "depend: [pong]\n");
    CommandApiTests.buildPluginJar(plugins.resolve("pong.jar"), "pong", "pong.PongPlugin", 1, empty("pong", "Pong"), "depend: [ping]\n");
    CommandApiTests.buildPluginJar(plugins.resolve("waiter.jar"), "waiter", "waiter.WaiterPlugin", 1, empty("waiter", "Waiter"), "depend: [ping]\n");
    CommandApiTests.buildPluginJar(plugins.resolve("fine.jar"), "fine", "fine.FinePlugin", 1, empty("fine", "Fine"));
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      runtime.pluginRuntime().loadAll();
    } finally {
      System.setErr(original);
    }
    String log = captured.toString(StandardCharsets.UTF_8);
    try {
      require(runtime.plugins().plugin("fine").isPresent(), "an unrelated plugin still loads");
      for (String id : List.of("needy", "ping", "pong", "waiter")) require(runtime.plugins().plugin(id).isEmpty(), id + " is not loaded");
      require(log.contains("plugin needy was not loaded: it depends on ghost, which is not installed"), "a missing dependency is named, log:\n" + log);
      require(log.contains("plugin ping was not loaded: its dependencies form a cycle: ping -> pong -> ping"), "a cycle is named, log:\n" + log);
      require(log.contains("plugin pong was not loaded: its dependencies form a cycle: pong -> ping -> pong"), "from both ends, log:\n" + log);
      require(log.contains("plugin waiter was not loaded: it depends on ping, which could not be loaded either"),
          "a plugin waiting on the cycle says so, log:\n" + log);
    } finally {
      runtime.close();
    }
    for (String id : List.of("needy", "ping", "pong", "waiter")) require(deletable(plugins.resolve(id + ".jar")), id + ".jar was released");
  }

  private static String empty(String pkg, String name) {
    return "package " + pkg + ";\npublic final class " + name + "Plugin extends gg.tame.conduit.api.plugin.ConduitPlugin { }\n";
  }

  private static gg.tame.conduit.api.event.proxy.ServerListPingEvent ping() {
    return new gg.tame.conduit.api.event.proxy.ServerListPingEvent(new java.net.InetSocketAddress("127.0.0.1", 1),
        java.util.Optional.empty(), 25565, 47, gg.tame.conduit.api.text.Text.of("motd"), 1, 0, List.of(), "Conduit", 47,
        java.util.Optional.empty());
  }

  private record NamedPlugin(String id) implements Plugin {
    @Override public PluginDescription description() { return new PluginDescription(id, id, "1.0", "Main", 1, List.of()); }
    @Override public gg.tame.conduit.api.ConduitProxy proxy() { return null; }
    @Override public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getLogger("test." + id); }
    @Override public Path dataDirectory() { return Path.of("."); }
    @Override public gg.tame.conduit.api.scheduler.Scheduler getScheduler() { return null; }
    @Override public void onLoad() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
  }

  private static ConduitRuntime runtime(Path plugins, Path root) {
    return new ConduitRuntime(new gg.tame.conduit.config.ConduitConfiguration(
        new java.net.InetSocketAddress("127.0.0.1", 1), 2048, gg.tame.conduit.config.ForwardingMode.NONE,
        java.util.Optional.empty(),
        List.of(new gg.tame.conduit.config.BackendServer("lobby", new java.net.InetSocketAddress("127.0.0.1", 2))),
        List.of("lobby"), List.of()), plugins, root);
  }

  private static long threads(String prefix) {
    return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().startsWith(prefix)).count();
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      Thread.sleep(10);
    }
    return condition.getAsBoolean();
  }

  private static boolean deletable(Path path) {
    try { return Files.deleteIfExists(path); } catch (java.io.IOException locked) { return false; }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

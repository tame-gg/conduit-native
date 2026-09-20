// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.config.SimplePluginConfiguration;
import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.event.Subscribe;
import gg.tame.conduit.api.plugin.Plugin;
import gg.tame.conduit.api.plugin.PluginDescription;
import gg.tame.conduit.api.scheduler.ScheduledTask;
import gg.tame.conduit.api.scheduler.Scheduler;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.ConsoleCommandSource;
import gg.tame.conduit.command.CommandGraph;
import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.ParsedCommand;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.command.RegisteredCommand;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.event.ConduitEventManager;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.runtime.ConduitRuntime;
import gg.tame.conduit.scheduler.ConduitScheduler;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

/** Commands, the client command graph, and the native plugin API. */
public final class CommandApiTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    registrationIsAtomic();
    registrationRejectsUntypeableNames();
    pluginUnregisterIsScoped();
    aliasesTabComplete();
    collapsedSpacesInArguments();
    serverReportsFailedTransfer();
    sendNeedsItsNodeAndNothingMore();
    serverNeedsNoPermission();
    conduitSubcommandPermissionsAreIndividual();
    conduitIsNotThereWithoutAnyNode();
    adminNodeStandsForEveryConduitNode();
    defaultProviderGrantsNothing();
    explicitDenyBeatsAdmin();
    commandChangesReDeclareTheTree();
    sendCurrentReportsFailure();
    conduitSubcommandsMatchTheGraph();
    helpListsWhatTheSourceMayRun();
    graphCarriesPluginCommands();
    aPluginCommandsArgumentsParseOnTheClient();
    graphSurvivesReDeclareAfterSwitch();
    legacyAndModernCompletionAgree();
    eventsReachSupertypeListeners();
    eventsRejectNonEventParameters();
    pluginLifecycleReleasesCommandsAndTasks();
    pluginConfigurationSeedsDefaults();
    consoleRunsCommands();
    dumpCarriesNoSecrets();
    dumpsLandInTheIgnoredDirectory();
    heapIsGatedAndSaysWhatItHolds();
    cacheInvalidateTakesLiteralAddressesOnly();
    commandTreeHoldsAcrossDirectProtocols();
    mergeKeepsTreesEndingInAHighByte();
    mergeKeepsATreeEndingInEightyHex();
    greedyStringArgumentsUseTheParserIdClientsRead();
    pluginJarEndToEnd();
    badPluginJarsDoNotStopTheProxy();
    pluginsEnableInDependencyOrderAndStopInReverse();
    foreignFormatPluginsGetTheNativeLifecycle();
    permissionProviderRevertsWhenItsPluginGoes();
    apiCommandsServeAnySource();
    pluginsDisplaceBuiltInsButNotEachOther();
    System.out.println("CommandApiTests OK");
  }

  /**
   * velocity-hub registers /hub and /lobby, and was refused outright: Conduit's /hub and the /lobby
   * server shortcut held both names. A plugin now displaces a built-in; never /conduit, and never
   * another plugin.
   */
  private static void pluginsDisplaceBuiltInsButNotEachOther() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer player = new RecordingPlayer("Kyle", "survival", Set.of("conduit.test"));
    fixture.players.add(player);
    RegisteredCommand builtInHub = fixture.commands.get("hub").orElseThrow();
    RegisteredCommand builtInLobby = fixture.commands.get("lobby").orElseThrow();
    RegisteredCommand builtInServer = fixture.commands.get("server").orElseThrow();
    Plugin hubPlugin = new TestPlugin("velocity-hub");
    List<String> ran = new ArrayList<>();
    fixture.commands.register(hubPlugin, new RegisteredCommand("hub", List.of("lobby"), "",
        (source, arguments) -> ran.add("plugin"), (source, arguments) -> List.of("from-plugin")));
    fixture.commands.register(hubPlugin, cmd("server", List.of()));
    fixture.commands.dispatch(player, "/hub");
    fixture.commands.dispatch(player, "/lobby");
    require(ran.equals(List.of("plugin", "plugin")), "both names run the plugin's command, ran " + ran);
    require(player.backend.equals("survival"), "the built-ins did not run");
    require(fixture.commands.tabComplete(player, "/lobby ").equals(List.of("from-plugin")), "completion is the plugin's");
    require(fixture.commands.displacedBuiltIns().equals(Set.of("hub", "lobby", "server")), "displaced " + fixture.commands.displacedBuiltIns());
    byte[] graph = CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), fixture.names(), fixture.commands.names(),
        fixture.commands.displacedBuiltIns());
    require(childrenOf(graph, "server").equals(List.of("arguments")),
        "the graph declares the plugin's /server -- free arguments, not the built-in's server list -- got "
            + childrenOf(graph, "server"));
    List<String> roots = rootChildren(graph);
    require(roots.contains("hub") && roots.contains("lobby") && roots.stream().distinct().count() == roots.size(), "each name declared once, got " + roots);

    boolean reserved = false;
    try { fixture.commands.register(new TestPlugin("greedy"), cmd("mine", List.of("conduit"))); }
    catch (IllegalArgumentException expected) { reserved = expected.getMessage().contains("reserved"); }
    require(reserved, "/conduit is reserved, even as an alias");
    require(fixture.commands.get("mine").isEmpty() && fixture.commands.get("conduit").orElseThrow() != null, "nothing of it registered");
    boolean clash = false;
    try { fixture.commands.register(new TestPlugin("rival"), cmd("fresh", List.of("hub"))); }
    catch (IllegalArgumentException expected) { clash = true; }
    require(clash && fixture.commands.get("fresh").isEmpty(), "another plugin's name still refuses the whole command");

    fixture.commands.unregisterAll(hubPlugin);
    require(fixture.commands.get("hub").orElseThrow() == builtInHub && fixture.commands.get("lobby").orElseThrow() == builtInLobby
        && fixture.commands.get("server").orElseThrow() == builtInServer, "the built-ins come back when the plugin goes");
    require(fixture.commands.displacedBuiltIns().isEmpty(), "nothing displaced any more");
    fixture.commands.dispatch(player, "/lobby");
    require(player.backend.equals("lobby"), "the /lobby shortcut works again, said " + player.messages);
    require(childrenOf(CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), fixture.names(), fixture.commands.names(),
        fixture.commands.displacedBuiltIns()), "server").equals(fixture.names()), "the graph declares the built-in /server again");
  }

  /**
   * Three lifecycle faults, each seen before this test: a plugin whose dependency threw in onEnable
   * was enabled anyway; shutdown disabled a dependency before the plugins using it; and a second
   * loadAll() enabled every plugin a second time.
   */
  private static void pluginsEnableInDependencyOrderAndStopInReverse() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-order");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    // Jar names sort dependents first, so directory order alone would get every one of these wrong.
    buildPluginJar(plugins.resolve("A-beta.jar"), "beta", "beta.BetaPlugin", 1, lifecycle("beta", "Beta", false), "depend: [alpha]\n");
    buildPluginJar(plugins.resolve("B-delta.jar"), "delta", "delta.DeltaPlugin", 1, lifecycle("delta", "Delta", false),
        "optional-dependencies: [epsilon, absent]\n");
    buildPluginJar(plugins.resolve("C-alpha.jar"), "alpha", "alpha.AlphaPlugin", 1, lifecycle("alpha", "Alpha", false));
    buildPluginJar(plugins.resolve("D-epsilon.jar"), "epsilon", "epsilon.EpsilonPlugin", 1, lifecycle("epsilon", "Epsilon", false));
    buildPluginJar(plugins.resolve("E-gamma.jar"), "gamma", "gamma.GammaPlugin", 1, lifecycle("gamma", "Gamma", false), "depend: [broken]\n");
    buildPluginJar(plugins.resolve("F-broken.jar"), "broken", "broken.BrokenPlugin", 1, lifecycle("broken", "Broken", true));
    SIGNALS.clear();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      runtime.pluginRuntime().loadAll();
      require(List.copyOf(SIGNALS).stream().filter("enable:alpha"::equals).count() == 1, "a second loadAll enables nothing again, signals " + SIGNALS);
      for (String id : List.of("alpha", "beta", "delta", "epsilon")) require(runtime.plugins().plugin(id).isPresent(), id + " enabled");
      require(runtime.plugins().plugin("broken").isEmpty(), "a plugin whose onEnable threw is not enabled");
      require(runtime.plugins().plugin("gamma").isEmpty(), "a plugin whose dependency failed is not enabled, signals " + SIGNALS);
      require(!SIGNALS.contains("enable:gamma"), "its onEnable never ran");
      require(before("enable:alpha", "enable:beta"), "dependency enabled first, signals " + SIGNALS);
      require(before("enable:epsilon", "enable:delta"), "present optional dependency enabled first, signals " + SIGNALS);
      runtime.plugins().disable(runtime.plugins().plugin("alpha").orElseThrow());
      require(before("disable:beta", "disable:alpha"), "disabling a dependency disables its dependents first, signals " + SIGNALS);
      require(runtime.plugins().plugin("beta").isEmpty(), "dependent went with it");
    } finally {
      runtime.close();
    }
    require(before("disable:delta", "disable:epsilon"), "shutdown disables in reverse enable order, signals " + SIGNALS);
    try (var list = Files.list(plugins)) {
      for (Path jar : list.filter(path -> path.toString().endsWith(".jar")).toList()) require(deletable(jar), "jar released: " + jar.getFileName());
    }
  }

  private static boolean before(String first, String second) {
    synchronized (SIGNALS) {
      int a = SIGNALS.indexOf(first);
      int b = SIGNALS.indexOf(second);
      return a >= 0 && b >= 0 && a < b;
    }
  }

  private static String lifecycle(String pkg, String name, boolean throwOnEnable) {
    return plugin(pkg, name, """
        @Override public void onEnable() {
          gg.tame.conduit.tests.CommandApiTests.signal("enable:" + description().id());
          if (%s) throw new IllegalStateException("enable failed");
        }
        @Override public void onDisable() { gg.tame.conduit.tests.CommandApiTests.signal("disable:" + description().id()); }
        """.formatted(throwOnEnable));
  }

  /** The Velocity layer's route in: a loader for another jar format, run through the native lifecycle. */
  private static void foreignFormatPluginsGetTheNativeLifecycle() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-format");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    foreignJar(plugins.resolve("A-foreign.jar"), "foreign");
    buildPluginJar(plugins.resolve("B-alpha.jar"), "alpha", "alpha.AlphaPlugin", 1, lifecycle("alpha", "Alpha", false));
    foreignJar(plugins.resolve("C-dupe.jar"), "alpha");
    foreignJar(plugins.resolve("D-boom.jar"), "boom");
    SIGNALS.clear();
    TestFormat format = new TestFormat();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.events().register(new TestPlugin("watch"), new Object() {
        @Subscribe public void on(gg.tame.conduit.api.event.plugin.PluginEnableEvent event) { signal("enable-event:" + event.plugin().description().id()); }
        @Subscribe public void off(gg.tame.conduit.api.event.plugin.PluginDisableEvent event) { signal("disable-event:" + event.plugin().description().id()); }
      });
      runtime.plugins().registerLoader(format);
      runtime.pluginRuntime().loadAll();
      require(before("enable:foreign", "enable-event:foreign"), "PluginEnableEvent follows onEnable, signals " + SIGNALS);
      boolean refused = false;
      try { runtime.plugins().registerLoader(new TestFormat()); } catch (IllegalStateException expected) { refused = true; }
      require(refused, "a loader registered after loading is refused, not silently never used");
      Plugin foreign = runtime.plugins().plugin("foreign").orElseThrow(() -> new AssertionError("foreign plugin not enabled, signals " + SIGNALS));
      require(before("enable:alpha", "enable:foreign"), "foreign plugin ordered after its native dependency, signals " + SIGNALS);
      require(foreign.dataDirectory().equals(plugins.toAbsolutePath().normalize().resolve("foreign")) && Files.isDirectory(foreign.dataDirectory()),
          "attached with its own data directory");
      require(foreign.getLogger() != null && foreign.proxy() == runtime, "attached logger and proxy");
      require(runtime.pluginCatalog().all().stream().anyMatch(entry -> entry.display().contains("[testformat]")), "listed under its format");
      require(format.closes.get() == 1, "the duplicate id's resources were closed, closes " + format.closes.get());
      require(runtime.commands().hasCommand("foreign"), "its command is registered");
      runtime.events().fire(new ProxyStartEvent(runtime));
      require(SIGNALS.contains("foreign-event"), "its listener runs");

      runtime.plugins().disable(foreign);
      require(before("disable-event:foreign", "disable:foreign"), "PluginDisableEvent, then onDisable, signals " + SIGNALS);
      require(format.closes.get() == 2, "resources closed once on disable, closes " + format.closes.get());
      require(!runtime.commands().hasCommand("foreign"), "command released");
      SIGNALS.remove("foreign-event");
      runtime.events().fire(new ProxyStartEvent(runtime));
      require(!SIGNALS.contains("foreign-event"), "listener released");
      runtime.plugins().disable(foreign);
      require(format.closes.get() == 2, "disabling twice closes nothing twice");
    } finally {
      runtime.close();
    }
  }

  private static void foreignJar(Path jar, String id) throws Exception {
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("test-format.txt"));
      out.write(id.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }

  private static final class TestFormat implements gg.tame.conduit.api.plugin.PluginLoader {
    private final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
    @Override public String format() { return "testformat"; }
    @Override public boolean accepts(java.util.jar.JarFile jar) { return jar.getEntry("test-format.txt") != null; }
    @Override public Loaded load(Path jar) throws Exception {
      String id;
      try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile());
           var input = file.getInputStream(file.getEntry("test-format.txt"))) {
        id = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
      }
      if (id.equals("boom")) throw new IllegalStateException("unreadable format");
      return new Loaded(new PluginDescription(id, id, "2.0", "none", 1, List.of("alpha")), new ForeignPlugin(), closes::incrementAndGet);
    }
  }

  public static final class ForeignPlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
    @Override public void onEnable() {
      signal("enable:" + description().id());
      proxy().commands().register(this, gg.tame.conduit.api.command.CommandManager.Command.builder("foreign").build());
      proxy().events().register(this, this);
    }
    @Subscribe public void onStart(ProxyStartEvent event) { signal("foreign-event"); }
    @Override public void onDisable() { signal("disable:" + description().id()); }
  }

  /**
   * A 1.13+ client is sent the command tree on join and on a server switch and never in between, so
   * a command registered, unregistered or newly permitted mid-session used to stay invisible until
   * the player switched servers. Every connected session is told to declare it again instead.
   */
  private static void commandChangesReDeclareTheTree() throws Exception {
    Path root = TempFiles.dir("conduit-tree-refresh");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    buildPluginJar(plugins.resolve("perm.jar"), "perm", "perm.PermPlugin", 1, plugin("perm", "Perm", """
        @Override public void onEnable() {
          proxy().setPermissionProvider(this, (subject, node) -> true);
        }
        """));
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      Counting watcher = new Counting();
      runtime.playerManager().add(watcher);
      int joined = watcher.refreshes;
      runtime.commandManager().register(new RegisteredCommand("late", List.of(), null,
          (source, arguments) -> { }, (source, arguments) -> List.of()));
      require(watcher.refreshes > joined, "a registered command declares the tree again");
      int registered = watcher.refreshes;
      runtime.commandManager().unregister("late");
      require(watcher.refreshes > registered, "and so does taking one away");
      int unregistered = watcher.refreshes;
      require(runtime.commandManager().unregister("neverthere") == null, "nothing was registered under that name");
      require(watcher.refreshes == unregistered, "a removal that removed nothing tells nobody");
      runtime.pluginRuntime().loadAll();
      require(watcher.refreshes > unregistered, "a permission provider changes who may run what, so the tree goes again");
    } finally {
      runtime.close();
    }
  }

  /** A player index entry that counts what it was told, standing in for a connected session. */
  private static final class Counting implements gg.tame.conduit.session.TrackedPlayer {
    private volatile int refreshes;
    @Override public java.util.UUID uniqueId() { return java.util.UUID.nameUUIDFromBytes("counting".getBytes(StandardCharsets.UTF_8)); }
    @Override public String username() { return "Counting"; }
    @Override public String currentBackend() { return "lobby"; }
    @Override public boolean transferTo(String serverName) { return false; }
    @Override public void refreshCommands() { refreshes++; }
  }

  /** A provider left installed after its plugin went would answer from a closed class loader. */
  private static void permissionProviderRevertsWhenItsPluginGoes() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-perms");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    buildPluginJar(plugins.resolve("perm.jar"), "perm", "perm.PermPlugin", 1, plugin("perm", "Perm", """
        @Override public void onEnable() {
          proxy().setPermissionProvider(this, (subject, node) -> node.equals("granted"));
        }
        """));
    buildPluginJar(plugins.resolve("bystander.jar"), "bystander", "bystander.BystanderPlugin", 1, plugin("bystander", "Bystander", ""));
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      require(runtime.permissions().hasPermission(null, "granted") && !runtime.permissions().hasPermission(null, "other"),
          "the plugin's provider answers");
      runtime.plugins().disable(runtime.plugins().plugin("bystander").orElseThrow());
      require(!runtime.permissions().hasPermission(null, "other"), "another plugin's disable leaves it alone");
      runtime.plugins().disable(runtime.plugins().plugin("perm").orElseThrow());
      require(runtime.permissions() instanceof gg.tame.conduit.permission.DefaultPermissionProvider, "default back after its owner went");
    } finally {
      runtime.close();
    }
  }

  /** The Velocity layer runs commands as its own sources; it must get those same objects back. */
  private static void apiCommandsServeAnySource() throws Exception {
    Path root = TempFiles.dir("conduit-api-commands");
    ConduitRuntime runtime = runtimeAt(root);
    try {
      gg.tame.conduit.api.command.CommandManager api = runtime.commands();
      Plugin owner = new TestPlugin("api");
      List<Object> ran = new ArrayList<>();
      api.register(owner, gg.tame.conduit.api.command.CommandManager.Command.builder("greet").alias("hi").permission("greet.use")
          .handler((source, arguments) -> { ran.add(source); ran.addAll(arguments); })
          .completer((source, arguments) -> List.of("alice", "bob"))
          .build());
      ApiSource allowed = new ApiSource("Remote", Set.of("greet.use"), new ArrayList<>());
      ApiSource denied = new ApiSource("Nobody", Set.of(), new ArrayList<>());
      require(api.execute(allowed, "/hi x"), "alias runs");
      require(ran.size() == 2 && ran.get(0) == allowed && ran.get(1).equals("x"), "handler got the caller's own source and its arguments, got " + ran);
      require(api.execute(denied, "greet"), "a known command is handled even when refused");
      require(ran.size() == 2 && denied.messages().stream().anyMatch(line -> line.contains("permission")), "refused, and told so");
      require(api.complete(allowed, "/hi a").equals(List.of("alice")), "completer filtered by the typed prefix");
      require(api.complete(allowed, "/gre").equals(List.of("greet")), "name completes");
      require(api.complete(denied, "/gre").isEmpty(), "no suggestion without permission");
      require(api.hasCommand("hi") && api.hasCommand("GREET") && !api.hasCommand("nope"), "hasCommand by name or alias");
      require(!api.execute(allowed, "/nope"), "an unknown command is not handled");
      require(api.execute(runtime.console(), "greet console"), "the console runs it");
      require(runtime.console().hasPermission("anything") && runtime.console().username().equals("CONSOLE"), "console holds every node");
      boolean threw = false;
      try { api.register(owner, gg.tame.conduit.api.command.CommandManager.Command.builder("other").alias("hi").build()); }
      catch (IllegalArgumentException expected) { threw = true; }
      require(threw && !api.hasCommand("other"), "a clashing alias refuses the whole command");
      api.unregisterAll(owner);
      require(!api.hasCommand("greet") && !api.hasCommand("hi"), "unregisterAll releases name and alias");
    } finally {
      runtime.close();
    }
  }

  private record ApiSource(String username, Set<String> permissions, List<String> messages) implements gg.tame.conduit.api.command.CommandSource {
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public void sendMessage(Text text) { messages.add(text.plain()); }
  }

  /** register() used to install the name, then throw on a colliding alias, leaving half a command. */
  private static void registrationIsAtomic() {
    CommandManager manager = new CommandManager();
    manager.register(cmd("alpha", List.of()));
    boolean threw = false;
    try { manager.register(cmd("beta", List.of("gamma", "alpha"))); }
    catch (IllegalArgumentException expected) { threw = true; }
    require(threw, "colliding alias rejected");
    require(manager.get("beta").isEmpty(), "rejected command left no primary name behind");
    require(manager.get("gamma").isEmpty(), "rejected command left no alias behind");
    require(manager.get("alpha").isPresent(), "existing command untouched");
  }

  private static void registrationRejectsUntypeableNames() {
    for (String bad : List.of("/", "//", "two words", " ")) {
      boolean threw = false;
      try { cmd(bad, List.of()); } catch (IllegalArgumentException expected) { threw = true; }
      require(threw, "rejected unreachable command name: '" + bad + "'");
    }
    // A player reaches it as "//slash": one slash is the command's, the rest is the name.
    require(cmd("/slash", List.of()).name().equals("/slash"), "a name that starts with a slash is typeable");
    boolean threw = false;
    try { cmd("ok", List.of("bad alias")); } catch (IllegalArgumentException expected) { threw = true; }
    require(threw, "rejected unreachable alias");
  }

  /** One plugin could unregister another plugin's command, and unregistering by alias leaked. */
  private static void pluginUnregisterIsScoped() {
    CommandManager manager = new CommandManager();
    Plugin one = new TestPlugin("one");
    Plugin two = new TestPlugin("two");
    manager.register(one, cmd("mine", List.of("m")));
    manager.unregister(two, "mine");
    require(manager.get("mine").isPresent(), "other plugin could not unregister it");
    manager.unregister(one, "m");
    require(manager.get("mine").isEmpty() && manager.get("m").isEmpty(), "owner unregistered by alias");
    manager.register(one, cmd("mine", List.of("m")));
    manager.unregisterAll(one);
    require(manager.get("mine").isEmpty(), "unregisterAll after an alias unregister");
    require(manager.names().isEmpty(), "no keys left");
  }

  /** "/tp" looked for a command NAMED tp and found nothing, so aliases never completed. */
  private static void aliasesTabComplete() {
    CommandManager manager = new CommandManager();
    RecordingPlayer source = new RecordingPlayer("Kyle", "lobby", Set.of("conduit.test"));
    manager.register(cmd("teleport", List.of("tp")));
    require(manager.tabComplete(source, "/tp").equals(List.of("tp")), "alias completes");
    require(manager.tabComplete(source, "/tel").equals(List.of("teleport")), "name completes");
    require(manager.tabComplete(source, "/t").equals(List.of("teleport", "tp")), "both offered");
    RecordingPlayer denied = new RecordingPlayer("Nobody", "lobby", Set.of());
    require(manager.tabComplete(denied, "/t").isEmpty(), "no permission, no suggestions");
  }

  private static void collapsedSpacesInArguments() {
    require(ParsedCommand.parseKeepEmpty("/send  lobby").arguments().equals(List.of("lobby")),
        "a run of spaces is one separator");
    require(ParsedCommand.parseKeepEmpty("/send lobby ").arguments().equals(List.of("lobby", "")),
        "a trailing space still starts a new argument");
    require(ParsedCommand.parseKeepEmpty("/server  ").arguments().equals(List.of("")),
        "trailing run of spaces is one empty argument");
    require(ParsedCommand.parse("/send  lobby  survival").arguments().equals(List.of("lobby", "survival")),
        "dispatch collapses too");
  }

  /** "Connecting to X..." and then nothing at all when the switch failed. */
  private static void serverReportsFailedTransfer() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer kyle = new RecordingPlayer("Kyle", "lobby", Set.of());
    kyle.failTransfer = true;
    fixture.players.add(kyle);
    fixture.commands.dispatch(kyle, "/server survival");
    require(kyle.said("is unavailable"), "failed switch is reported, said " + kyle.messages);
  }

  /** conduit.command.send is the whole of /send: a player, the current player, or a whole server. */
  private static void sendNeedsItsNodeAndNothingMore() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer kyle = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.SEND));
    RecordingPlayer steve = new RecordingPlayer("Steve", "lobby", Set.of());
    fixture.players.add(kyle);
    fixture.players.add(steve);
    fixture.commands.dispatch(kyle, "/send Kyle survival");
    require(kyle.backend.equals("survival"), "player sent themselves, said " + kyle.messages);
    fixture.commands.dispatch(kyle, "/send Steve survival");
    require(steve.backend.equals("survival"), "and somebody else, said " + kyle.messages);
    require(!kyle.said("permission"), "no permission complaint, said " + kyle.messages);

    fixture.commands.dispatch(steve, "/send Kyle lobby");
    require(steve.said("permission"), "refused without the node, said " + steve.messages);
    require(kyle.backend.equals("survival"), "and nobody was moved");
  }

  private static void sendCurrentReportsFailure() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer kyle = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.SEND));
    kyle.failTransfer = true;
    fixture.players.add(kyle);
    fixture.commands.dispatch(kyle, "/send current survival");
    require(kyle.said("is unavailable"), "failed /send current is reported, said " + kyle.messages);
  }

  /** The graph's /conduit children and the server-side completer drifted apart. */
  private static void conduitSubcommandsMatchTheGraph() throws Exception {
    Fixture fixture = new Fixture();
    // Deliberately not an admin: the sweep below runs every subcommand, and dump/heap would write
    // real files. Without those nodes they answer "no permission", which is still not "unknown".
    Set<String> nodes = Set.of(Permissions.INFO, Permissions.PLUGINS, Permissions.SERVERS,
        Permissions.UPTIME, Permissions.RELOAD, Permissions.METRICS, Permissions.HEALTH, Permissions.MAINTENANCE,
        Permissions.DRAIN, Permissions.DOCTOR, Permissions.DIAGNOSTICS, Permissions.ATTACK, Permissions.CACHE);
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", nodes);
    List<String> expected = new ArrayList<>(CoreCommands.CONDUIT_SUBCOMMANDS);
    expected.removeAll(List.of("dump", "heap"));
    List<String> completed = fixture.commands.tabComplete(admin, "/conduit ");
    require(completed.equals(expected), "completer offers what this source may run, got " + completed);
    List<String> shown = childrenOf(treeFor(fixture, admin), "conduit");
    require(shown.equals(expected), "graph declares the same ones, got " + shown);
    List<String> graph = childrenOf(CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), fixture.names()), "conduit");
    require(graph.equals(CoreCommands.CONDUIT_SUBCOMMANDS), "an unfiltered graph declares all of them, got " + graph);
    for (String sub : CoreCommands.CONDUIT_SUBCOMMANDS) {
      admin.messages.clear();
      fixture.commands.dispatch(admin, "/conduit " + sub);
      require(!admin.said("Unknown /conduit subcommand"), "/conduit " + sub + " is a real subcommand");
    }
  }

  /** The tree a 1.20.4 client on no backend tree would be sent, as {@code source} is shown it. */
  private static byte[] treeFor(Fixture fixture, CommandSource source) throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
    return CommandGraphs.mergeProxyCommands(protocol, rootOnly(protocol), fixture.names(), fixture.commands.names(),
        Set.of(), fixture.commands.shownTo(source));
  }

  /** A Declare Commands packet holding nothing but its root. */
  private static byte[] rootOnly(ProtocolDefinition protocol) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, gg.tame.conduit.protocol.PlayPackets.packetId(
          CommandGraphs.proxyOnly(protocol, List.of())));
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      output.writeByte(0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }

  /** /server, its shortcuts, /hub and /ping belong to every player; there is no node to hold. */
  private static void serverNeedsNoPermission() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer guest = new RecordingPlayer("Guest", "lobby", Set.of());
    fixture.players.add(guest);
    fixture.commands.dispatch(guest, "/server");
    require(guest.said("connected to: lobby") && guest.said("survival"), "/server lists the servers, said " + guest.messages);
    fixture.commands.dispatch(guest, "/server survival");
    require(guest.backend.equals("survival"), "/server <name> switches, said " + guest.messages);
    fixture.commands.dispatch(guest, "/lobby");
    require(guest.backend.equals("lobby"), "the /<server> shortcut switches, said " + guest.messages);
    fixture.commands.dispatch(guest, "/ping");
    require(!guest.said("permission"), "nothing was refused, said " + guest.messages);
    require(fixture.commands.tabComplete(guest, "/ser").equals(List.of("server")), "/server completes");
    require(fixture.commands.tabComplete(guest, "/server ").equals(List.of("lobby", "survival")), "and so do its servers");
    byte[] tree = treeFor(fixture, guest);
    require(rootChildren(tree).containsAll(List.of("server", "hub", "ping", "lobby", "survival")), "the tree offers them, got " + rootChildren(tree));
    require(childrenOf(tree, "server").equals(List.of("lobby", "survival")), "with the servers under /server");
  }

  /** Holding doctor gave nothing else, and holding nothing but doctor still reached doctor. */
  private static void conduitSubcommandPermissionsAreIndividual() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer doctor = new RecordingPlayer("Doc", "lobby", Set.of(Permissions.DOCTOR));
    fixture.commands.dispatch(doctor, "/conduit doctor");
    require(!doctor.said("permission"), "doctor runs on its own node, said " + doctor.messages);
    for (String other : List.of("reload", "maintenance on", "info", "servers", "drain lobby", "attack on", "plugins")) {
      doctor.messages.clear();
      require(fixture.commands.dispatch(doctor, "/conduit " + other), "/conduit is there for them");
      require(doctor.said("permission"), "/conduit " + other + " is refused, said " + doctor.messages);
    }
    require(fixture.commands.tabComplete(doctor, "/conduit ").equals(List.of("doctor", "help")), "only doctor is offered, got "
        + fixture.commands.tabComplete(doctor, "/conduit "));
    require(fixture.commands.tabComplete(doctor, "/conduit maintenance ").isEmpty(), "nor a refused subcommand's arguments");
    require(childrenOf(treeFor(fixture, doctor), "conduit").equals(List.of("doctor", "help")), "the tree agrees");
    doctor.messages.clear();
    fixture.commands.dispatch(doctor, "/conduit help");
    require(doctor.said("/conduit doctor") && !doctor.said("/conduit reload"), "help lists doctor alone, said " + doctor.messages);

    RecordingPlayer servers = new RecordingPlayer("Ops", "lobby", Set.of(Permissions.SERVERS));
    fixture.commands.dispatch(servers, "/conduit servers");
    require(servers.said("Conduit Servers"), "servers runs on its own node, said " + servers.messages);
    fixture.commands.dispatch(servers, "/conduit doctor");
    require(servers.said("permission"), "and gives no doctor");
  }

  /** For a player with no /conduit node at all, /conduit is not there: the line goes to the backend. */
  private static void conduitIsNotThereWithoutAnyNode() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer guest = new RecordingPlayer("Guest", "lobby", Set.of());
    require(!fixture.commands.dispatch(guest, "/conduit reload"), "/conduit reload is not handled for them");
    require(!fixture.commands.dispatch(guest, "/conduit"), "nor /conduit");
    require(guest.messages.isEmpty(), "and nothing is said, said " + guest.messages);
    require(fixture.commands.dispatch(guest, "/send Guest survival") && guest.said("permission"), "/send is refused");
    require(fixture.commands.tabComplete(guest, "/con").isEmpty(), "/conduit is not offered");
    List<String> offered = fixture.commands.tabComplete(guest, "/");
    List<String> declared = rootChildren(treeFor(fixture, guest));
    for (String hidden : List.of("conduit", "send", "glist", "plist", "find", "alert", "gkick")) {
      require(!offered.contains(hidden), "/" + hidden + " is not offered, got " + offered);
      require(!declared.contains(hidden), "/" + hidden + " is not in their tree, got " + declared);
    }
    require(fixture.commands.dispatch(new ConsoleCommandSource(), "/conduit help"), "the console still has /conduit");
  }

  /** conduit.admin stands for every Conduit node, top-level commands included, and for no plugin's. */
  private static void adminNodeStandsForEveryConduitNode() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_ADMIN));
    fixture.players.add(admin);
    fixture.commands.dispatch(admin, "/glist");
    require(admin.said("player(s) online"), "/glist runs, said " + admin.messages);
    require(fixture.commands.tabComplete(admin, "/conduit ").containsAll(CoreCommands.CONDUIT_SUBCOMMANDS), "every subcommand");
    fixture.commands.register(new TestPlugin("demo"), new RegisteredCommand("warp", List.of(), "demo.warp",
        (source, arguments) -> { }, (source, arguments) -> List.of()));
    require(fixture.commands.dispatch(admin, "/warp") && admin.said("permission"), "a plugin's node is not Conduit's to grant");
  }

  /**
   * Conduit's default provider granted every node, so with no permissions plugin any player could
   * reload the proxy or kick players. Denying only the {@code conduit.} nodes left every other
   * plugin's administrative node open to everyone, so it now grants a player nothing at all.
   */
  private static void defaultProviderGrantsNothing() throws Exception {
    var provider = new gg.tame.conduit.permission.DefaultPermissionProvider();
    for (String node : List.of(Permissions.RELOAD, Permissions.SEND, Permissions.CONDUIT_ADMIN, Permissions.MAINTENANCE_BYPASS,
        Permissions.DRAIN_BYPASS, "Conduit.Command.Doctor", "minimotd.admin", "maintenance.admin")) {
      require(!provider.hasPermission(null, node), "default does not grant " + node);
    }
    Fixture fixture = new Fixture();
    DefaultedPlayer guest = new DefaultedPlayer(provider);
    require(fixture.commands.dispatch(guest, "/server survival"), "/server still works on the default");
    require(!fixture.commands.dispatch(guest, "/conduit reload"), "/conduit reload is not there on the default");
    require(fixture.commands.dispatch(guest, "/gkick Someone"), "/gkick is handled");
    require(guest.said.stream().anyMatch(line -> line.contains("permission")), "and refused, said " + guest.said);
    require(fixture.commands.dispatch(new ConsoleCommandSource(), "/conduit uptime"), "the console administers regardless");
  }

  /**
   * {@code conduit.admin} stands for every Conduit node, but not over a node a provider denies
   * outright: permissions used to be yes or no, so the admin grant answered for a node explicitly
   * set to false and the player ran the command anyway.
   */
  private static void explicitDenyBeatsAdmin() throws Exception {
    var provider = new gg.tame.conduit.api.permission.PermissionProvider() {
      @Override public boolean hasPermission(gg.tame.conduit.api.permission.PermissionSubject subject, String permission) {
        return Boolean.TRUE.equals(permissionValue(subject, permission));
      }
      @Override public Boolean permissionValue(gg.tame.conduit.api.permission.PermissionSubject subject, String permission) {
        if (permission.equals(Permissions.CONDUIT_ADMIN)) return Boolean.TRUE;
        if (permission.equals(Permissions.RELOAD)) return Boolean.FALSE;   // denied outright
        return null;                                                       // nothing said
      }
    };
    DefaultedPlayer admin = new DefaultedPlayer(provider);
    require(!Permissions.allows(admin, Permissions.RELOAD), "an outright denial is obeyed over admin");
    require(Permissions.allows(admin, Permissions.UPTIME), "a node nobody mentioned still falls to admin");
    require(!Permissions.allows(admin, "minimotd.admin"), "admin never stands for a plugin's node");
    require(!Permissions.allows(provider, admin, Permissions.RELOAD), "and the same asked of the provider");
    require(Permissions.allows(provider, admin, Permissions.UPTIME), "and the same asked of the provider");
    Fixture fixture = new Fixture();
    require(fixture.commands.dispatch(admin, "/conduit reload"), "the command is there for an admin");
    require(admin.said.stream().anyMatch(line -> line.contains("permission")), "but refused, said " + admin.said);
    require(!fixture.commands.shownTo(admin).test("conduit reload"), "and the client tree does not offer it");
    require(fixture.commands.shownTo(admin).test("conduit uptime"), "while the rest of /conduit stays");
  }

  /** A player answered by a real provider rather than a fixed set. */
  private static final class DefaultedPlayer implements CommandSource {
    private final gg.tame.conduit.api.permission.PermissionProvider provider;
    private final List<String> said = new ArrayList<>();
    DefaultedPlayer(gg.tame.conduit.api.permission.PermissionProvider provider) { this.provider = provider; }
    @Override public String username() { return "Guest"; }
    @Override public boolean hasPermission(String permission) { return provider.hasPermission(this, permission); }
    @Override public Boolean permissionValue(String permission) { return provider.permissionValue(this, permission); }
    @Override public void sendMessage(String message) { said.add(message); }
    @Override public void sendMessage(Text text) { said.add(text == null ? "" : text.plain()); }
    @Override public String currentBackend() { return "lobby"; }
  }

  /** /conduit help is the in-game command reference; it listed neither /glist nor its neighbours. */
  private static void helpListsWhatTheSourceMayRun() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.INFO, Permissions.CONDUIT_ADMIN));
    fixture.commands.dispatch(admin, "/conduit help");
    for (String listed : List.of("/server", "/send", "/glist", "/plist", "/find", "/alert", "/ping", "/gkick", "/hub", "/conduit")) {
      require(admin.said(listed), "help lists " + listed + ", said " + admin.messages);
    }
    RecordingPlayer plain = new RecordingPlayer("Guest", "lobby", Set.of(Permissions.INFO));
    fixture.commands.dispatch(plain, "/conduit help");
    require(plain.said("/server"), "help keeps what this source may run");
    require(!plain.said("/gkick"), "help hides what it may not, said " + plain.messages);
  }

  /** A plugin's command was dispatchable but absent from the tree a 1.13+ client parses against. */
  private static void graphCarriesPluginCommands() throws Exception {
    Fixture fixture = new Fixture();
    fixture.commands.register(new TestPlugin("demo"), cmd("warp", List.of("w")));
    byte[] packet = CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), fixture.names(), fixture.commands.names());
    List<String> roots = rootChildren(packet);
    require(roots.contains("warp") && roots.contains("w"), "plugin command and alias declared, got " + roots);
    require(roots.contains("server") && roots.contains("lobby"), "built-ins and server shortcuts kept");
    require(roots.stream().distinct().count() == roots.size(), "no duplicate root literal, got " + roots);
  }

  /**
   * A plugin's command has to parse on the client, not just run on the proxy.
   *
   * <p>Every registered command used to be declared as a childless literal, so a 1.13+ client
   * matched {@code lpv} and then had no node for the rest of {@code /lpv user Kyle permission info}.
   * Brigadier calls that a syntax error and the client paints the whole line red -- while Conduit,
   * which parses the line itself, ran it perfectly. Hence "red in chat but works".
   *
   * <p>Checked by parsing with Brigadier, which is the client's own parser, against a dispatcher
   * rebuilt from the bytes Conduit sends. Asserting the node is merely present would not catch it:
   * a non-greedy string argument is present too, and leaves {@code /lpv user Kyle} just as red.
   */
  private static void aPluginCommandsArgumentsParseOnTheClient() throws Exception {
    Fixture fixture = new Fixture();
    // As LuckPerms and MiniMOTD register: several aliases of one command, and a one-word command.
    fixture.commands.register(new TestPlugin("luckperms"), cmd("luckperms", List.of("lp", "lpv", "perms")));
    fixture.commands.register(new TestPlugin("minimotd"), cmd("minimotd", List.of()));
    byte[] packet = CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), fixture.names(), fixture.commands.names());

    require(childrenOf(packet, "lpv").equals(List.of("arguments")), "a plugin command takes an argument, got "
        + childrenOf(packet, "lpv"));
    com.mojang.brigadier.CommandDispatcher<Object> client = clientDispatcher(packet);
    for (String line : List.of("lpv user Kyle permission info", "lp group default info", "luckperms sync",
        "minimotd reload", "perms editor", "server lobby", "alert hello there")) {
      var parse = client.parse(line, new Object());
      require(parse.getReader().getRemainingLength() == 0 && parse.getExceptions().isEmpty(),
          "the client parses /" + line + " (" + parse.getReader().getRemainingLength() + " chars left over, "
              + parse.getExceptions().size() + " errors) -- it would be red");
    }
    // A server shortcut really does take nothing, so it keeps the parse error that says so.
    require(client.parse("lobby nonsense", new Object()).getReader().getRemainingLength() > 0,
        "a /<server> shortcut still takes no arguments");
  }

  /**
   * The command tree as the client's Brigadier sees it, built from the encoded packet: literals, and
   * {@code brigadier:string} arguments in the word, quotable or greedy form their one property byte
   * names. Conduit writes no other parser.
   */
  private static com.mojang.brigadier.CommandDispatcher<Object> clientDispatcher(byte[] packet) throws Exception {
    var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(body(packet)));
    int count = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
    List<List<Integer>> children = new ArrayList<>();
    List<com.mojang.brigadier.tree.CommandNode<Object>> built = new ArrayList<>();
    var dispatcher = new com.mojang.brigadier.CommandDispatcher<Object>();
    for (int index = 0; index < count; index++) {
      int flags = input.readUnsignedByte();
      int childCount = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      List<Integer> kids = new ArrayList<>(childCount);
      for (int child = 0; child < childCount; child++) kids.add(gg.tame.conduit.protocol.MinecraftInput.varInt(input));
      children.add(kids);
      if ((flags & 0x08) != 0) gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      int type = flags & 0x03;
      String name = (type == 1 || type == 2) ? gg.tame.conduit.protocol.MinecraftInput.string(input, 32767) : null;
      com.mojang.brigadier.tree.CommandNode<Object> node;
      if (type == 0) {
        node = dispatcher.getRoot();
      } else if (type == 1) {
        node = com.mojang.brigadier.builder.LiteralArgumentBuilder.<Object>literal(name).executes(context -> 1).build();
      } else {
        int parser = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
        require(parser == gg.tame.conduit.command.ParserIds.INDEXED.stringId(), "only brigadier:string is written, got " + parser);
        var string = switch (gg.tame.conduit.protocol.MinecraftInput.varInt(input)) {
          case 2 -> com.mojang.brigadier.arguments.StringArgumentType.greedyString();
          case 1 -> com.mojang.brigadier.arguments.StringArgumentType.string();
          default -> com.mojang.brigadier.arguments.StringArgumentType.word();
        };
        node = com.mojang.brigadier.builder.RequiredArgumentBuilder.<Object, String>argument(name, string)
            .executes(context -> 1).build();
      }
      if ((flags & 0x10) != 0) gg.tame.conduit.protocol.MinecraftInput.string(input, 32767);
      built.add(node);
    }
    for (int index = 0; index < count; index++) {
      for (int child : children.get(index)) built.get(index).addChild(built.get(child));
    }
    return dispatcher;
  }

  /**
   * Each backend declares its own tree, and a switch means a second one arrives with nothing of
   * Conduit's on it. Every one of them has to come back out of the merge carrying the proxy names,
   * and carrying the backend's own untouched.
   */
  private static void graphSurvivesReDeclareAfterSwitch() throws Exception {
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(765);
    Fixture fixture = new Fixture();
    fixture.commands.register(new TestPlugin("demo"), cmd("warp", List.of("w")));
    List<String> extra = fixture.commands.names();
    for (String backendCommand : List.of("gamemode", "tp")) {
      byte[] declared = backendTree(protocol, backendCommand);
      List<String> roots = rootChildren(CommandGraphs.mergeProxyCommands(protocol, declared, fixture.names(), extra));
      require(roots.contains(backendCommand), "backend kept its own " + backendCommand);
      require(roots.contains("server") && roots.contains("conduit") && roots.contains("warp"),
          "proxy commands re-declared on the new backend's tree, got " + roots);
      require(roots.stream().distinct().count() == roots.size(), "no name declared twice, got " + roots);
    }
  }

  /** A backend's own declare-commands: root plus one executable literal. */
  /**
   * A backend tree whose last node ends in a byte with the high bit set, which is what a real
   * server's tree does whenever its final node carries a non-ASCII name or a properties payload
   * ending in a raw float, double or long byte. The merge copies those bytes without decoding
   * them, so it has to find where they stop without mistaking one of them for part of the
   * trailing root index.
   */
  private static byte[] backendTreeEndingHigh(ProtocolDefinition protocol) throws Exception {
    return backendTree(protocol, "café");
  }


  /**
   * The parser numbering a 26.2 client actually reads, taken from a 26.2 server's own command
   * tree rather than from a changelog.
   *
   * <p>In that tree {@code brigadier:string} is id 5 and id 4 is never written at all, which is
   * the registry with {@code brigadier:float} still in it. Conduit believed 26.2 had dropped
   * float and wrote its string arguments as 4. For a plain word argument that is survivable --
   * the client reads 4 as {@code brigadier:long}, whose properties are also one byte when no
   * bounds are set. For a greedy one it is not: the properties byte is 2, which as a long means
   * "a maximum follows", so the client swallows the next eight bytes and reads every node after
   * from the wrong offset until the packet runs out under it.
   */
  private static void greedyStringArgumentsUseTheParserIdClientsRead() throws Exception {
    require(gg.tame.conduit.command.ParserIds.forProtocol(776).stringId() == 5,
        "26.2 writes brigadier:string as 5");
    require(gg.tame.conduit.command.ParserIds.forProtocol(765).stringId() == 5,
        "1.20.4 writes brigadier:string as 5");

    Fixture fixture = new Fixture();
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    byte[] declared = CommandGraphs.mergeProxyCommands(protocol, backendTree(protocol, "gamemode"),
        fixture.names(), fixture.commands.names(), fixture.commands.displacedBuiltIns());
    byte[] name = "message".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int at = indexOf(declared, name);
    require(at > 0, "the merged tree declares a message argument");
    int parser = declared[at + name.length] & 0xFF;
    int properties = declared[at + name.length + 1] & 0xFF;
    require(parser == 5, "the greedy argument names parser 5, got " + parser);
    require(properties == 2, "the greedy argument says greedy, got " + properties);
  }

  /**
   * The silent case, and the one that kicked a real client: a last node ending in {@code 0x80}.
   * Walking backwards from the end took that byte for part of the root index, and {@code 0x80 0x00}
   * decodes to 0 exactly as {@code 0x00} does -- so the "root is node 0" check passed, the copy
   * stopped a byte early, and the client was handed a tree with a byte missing out of the middle.
   * A double's bounds are written as raw IEEE bytes, so ending in one is ordinary.
   */
  private static void mergeKeepsATreeEndingInEightyHex() throws Exception {
    Fixture fixture = new Fixture();
    ProtocolDefinition protocol = ProtocolDefinition.forVersion(776);
    var bytes = new java.io.ByteArrayOutputStream();
    byte[] node;
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, protocol.id(
          gg.tame.conduit.protocol.ConnectionState.PLAY,
          gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT,
          gg.tame.conduit.protocol.PacketKind.PLAY_DECLARE_COMMANDS));
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 2);
      output.writeByte(0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      var tail = new java.io.ByteArrayOutputStream();
      try (var nodeOut = new java.io.DataOutputStream(tail)) {
        nodeOut.writeByte(0x02 | 0x04);
        gg.tame.conduit.protocol.MinecraftOutput.varInt(nodeOut, 0);
        gg.tame.conduit.protocol.MinecraftOutput.string(nodeOut, "amount");
        // brigadier:double -- id 2 in the registry clients actually read -- then its flags
        // byte and a max whose last byte is 0x80.
        gg.tame.conduit.protocol.MinecraftOutput.varInt(nodeOut, 2);
        nodeOut.writeByte(0x02);
        nodeOut.writeLong(0x4059000000000080L);
      }
      node = tail.toByteArray();
      output.write(node);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    byte[] backend = bytes.toByteArray();
    require((node[node.length - 1] & 0xFF) == 0x80, "the fixture must end in 0x80");

    byte[] declared = CommandGraphs.mergeProxyCommands(protocol, backend, fixture.names());
    // Not decoded: the merge's contract is that a backend's nodes are copied through untouched,
    // whatever they hold. Losing any of them is the failure, and it is visible as bytes.
    require(indexOf(declared, node) >= 0, "the backend's node was not copied through intact");
    require(rootChildren(declared, gg.tame.conduit.command.ParserIds.forProtocol(776)).contains("server"),
        "the proxy commands are still declared");
  }

  /** Where {@code needle} starts in {@code haystack}, or -1. */
  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int start = 0; start + needle.length <= haystack.length; start++) {
      for (int i = 0; i < needle.length; i++) {
        if (haystack[start + i] != needle[i]) continue outer;
      }
      return start;
    }
    return -1;
  }

  /** The merge must not lose a byte of a tree that ends in one. */
  private static void mergeKeepsTreesEndingInAHighByte() throws Exception {
    Fixture fixture = new Fixture();
    for (int version : new int[] { 393, 765, 776 }) {
      ProtocolDefinition protocol = ProtocolDefinition.forVersion(version);
      byte[] backend = backendTreeEndingHigh(protocol);
      byte[] declared = CommandGraphs.mergeProxyCommands(protocol, backend, fixture.names());
      var parsers = gg.tame.conduit.command.ParserIds.forProtocol(version);
      // Decoding is the same structural check a client makes: a byte lost anywhere in the copied
      // region shifts everything after it, and shows up here rather than as a kicked player.
      CommandGraph.decode(body(declared), parsers);
      List<String> roots = rootChildren(declared, parsers);
      require(roots.contains("café"), version + " keeps a backend command ending in a high byte, got " + roots);
      require(roots.contains("server"), version + " still declares /server, got " + roots);
    }
  }

  private static byte[] backendTree(ProtocolDefinition protocol, String literal) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, protocol.id(
          gg.tame.conduit.protocol.ConnectionState.PLAY,
          gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT,
          gg.tame.conduit.protocol.PacketKind.PLAY_DECLARE_COMMANDS));
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 2);
      output.writeByte(0);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 1);
      output.writeByte(0x01 | 0x04);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
      gg.tame.conduit.protocol.MinecraftOutput.string(output, literal);
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0);
    }
    return bytes.toByteArray();
  }

  /** Both completion paths answer for the same set of names; only their packet shape differs. */
  private static void legacyAndModernCompletionAgree() throws Exception {
    Fixture fixture = new Fixture();
    fixture.commands.register(new TestPlugin("demo"), cmd("warp", List.of("w")));
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of("conduit.test",
        Permissions.SEND, Permissions.INFO));
    List<String> legacy = fixture.commands.tabComplete(admin, "/");
    List<String> modern = rootChildren(CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765),
        fixture.names(), fixture.commands.names()));
    for (String name : legacy) require(modern.contains(name), "legacy name " + name + " is in the graph too");
  }

  private static void eventsReachSupertypeListeners() {
    ConduitEventManager events = new ConduitEventManager();
    Plugin plugin = new TestPlugin("listener");
    List<String> seen = new ArrayList<>();
    events.register(plugin, new Object() {
      @Subscribe public void exact(Ping event) { seen.add("exact"); }
      @Subscribe public void base(Event event) { seen.add("base"); }
    });
    events.fire(new Ping());
    require(seen.contains("exact"), "exact-type listener ran");
    require(seen.contains("base"), "supertype listener ran");
    events.unregister(plugin);
    seen.clear();
    events.fire(new Ping());
    require(seen.isEmpty(), "unregistered");
  }

  private static void eventsRejectNonEventParameters() {
    ConduitEventManager events = new ConduitEventManager();
    boolean threw = false;
    try {
      events.register(new TestPlugin("bad"), new Object() {
        @Subscribe public void wrong(String notAnEvent) { }
      });
    } catch (IllegalArgumentException expected) { threw = true; }
    require(threw, "a listener that could never fire is refused");
  }

  private static void pluginLifecycleReleasesCommandsAndTasks() throws Exception {
    Fixture fixture = new Fixture();
    Plugin plugin = new TestPlugin("demo");
    fixture.commands.register(plugin, cmd("warp", List.of("w")));
    require(fixture.commands.get("w").isPresent(), "plugin command registered");
    try (ConduitScheduler scheduler = new ConduitScheduler()) {
      CountDownLatch ran = new CountDownLatch(1);
      ScheduledTask task = scheduler.buildTask(plugin, ran::countDown).delay(Duration.ofMillis(1)).schedule();
      require(ran.await(5, TimeUnit.SECONDS), "scheduled task ran");
      require(!task.cancelled(), "one-shot task is not reported cancelled");
      ScheduledTask repeating = scheduler.buildTask(plugin, () -> { }).repeat(Duration.ofMillis(10)).schedule();
      scheduler.cancel(plugin);
      require(repeating.cancelled(), "plugin shutdown cancels its repeating tasks");
    }
    fixture.commands.unregisterAll(plugin);
    require(fixture.commands.get("warp").isEmpty() && fixture.commands.get("w").isEmpty(), "aliases released too");
  }

  private static void pluginConfigurationSeedsDefaults() throws Exception {
    Path file = TempFiles.dir("conduit-plugin-config").resolve("nested").resolve("config.properties");
    Map<String, String> defaults = new LinkedHashMap<>();
    defaults.put("greeting", "hello");
    defaults.put("limit", "5");
    SimplePluginConfiguration first = SimplePluginConfiguration.load(file, defaults);
    require(first.string("greeting", "").equals("hello"), "default applied");
    require(Files.exists(file), "default file written");
    Files.writeString(file, "greeting=hi");
    defaults.put("added-later", "yes");
    SimplePluginConfiguration second = SimplePluginConfiguration.load(file, defaults);
    require(second.string("greeting").orElseThrow().equals("hi"), "existing value kept");
    require(second.string("added-later", "").equals("yes"), "new default added");
    require(SimplePluginConfiguration.load(file, defaults).string("greeting").orElseThrow().equals("hi"),
        "re-reading the file it just wrote is stable");
  }

  private static void consoleRunsCommands() throws Exception {
    Fixture fixture = new Fixture();
    ConsoleCommandSource console = new ConsoleCommandSource();
    List<String> printed = new ArrayList<>();
    fixture.commands.register(new RegisteredCommand("echo", List.of(), Permissions.CONDUIT_ADMIN,
        (source, arguments) -> { printed.addAll(arguments); source.sendMessage(Text.of("ok")); },
        (source, arguments) -> List.of()));
    require(fixture.commands.dispatch(console, "/echo hello"), "console dispatch");
    require(printed.equals(List.of("hello")), "console arguments reached the command");
    require(console.currentBackend().isEmpty(), "console is on no backend");
    require(console.hasPermission(Permissions.CONDUIT_ADMIN), "console holds every node");
    require(fixture.commands.tabComplete(console, "/ser").equals(List.of("server")), "console completes");
    // /server from the console is not "that backend is unavailable" -- the console is not a player.
    RecordingPlayer relay = new RecordingPlayer("Op", "", Set.of());
    fixture.commands.dispatch(new Relay(relay), "/server survival");
    require(relay.said("Only a player can switch servers"), "console /server message, said " + relay.messages);
    relay.messages.clear();
    fixture.commands.dispatch(new Relay(relay), "/hub");
    require(relay.said("Only a player can use /hub"), "console /hub message, said " + relay.messages);
    relay.messages.clear();
    fixture.commands.dispatch(new Relay(relay), "/server");
    require(relay.said("connected to: none"), "console /server lists servers, said " + relay.messages);
  }

  /** A console source whose output is captured: same class of source, not a TrackedPlayer. */
  private record Relay(RecordingPlayer sink) implements CommandSource {
    @Override public String username() { return "CONSOLE"; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMessage(String message) { sink.messages.add(message); }
    @Override public String currentBackend() { return ""; }
  }

  /**
   * /conduit dump is the one dump an operator is told they can paste into an issue, so every field
   * it writes is asserted here: counters and names in, everything the proxy is trusted with out.
   */
  private static void dumpCarriesNoSecrets() throws Exception {
    Path root = TempFiles.dir("conduit-dump-audit");
    Path secretFile = root.resolve("forwarding.secret");
    Files.writeString(secretFile, SECRET);
    ConduitRuntime runtime = new ConduitRuntime(new gg.tame.conduit.config.ConduitConfiguration(
        new java.net.InetSocketAddress("127.0.0.1", 25565), 2048,
        gg.tame.conduit.config.ForwardingMode.MODERN, java.util.Optional.of(secretFile),
        List.of(new gg.tame.conduit.config.BackendServer("lobby",
            new java.net.InetSocketAddress("10.11.12.13", 24601))),
        List.of("lobby"), List.of("lobby")), root.resolve("plugins"), root);
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.INFO, Permissions.CONDUIT_ADMIN));
    try {
      CoreCommands.register(runtime);
      runtime.commandManager().dispatch(admin, "/conduit dump");
    } finally {
      runtime.close();
    }
    Path written = onlyFile(root.resolve("dumps"));
    String body = Files.readString(written);

    require(body.contains("Conduit "), "dump names the version");
    require(body.contains("uptimeMs="), "dump carries uptime");
    require(body.contains("metrics="), "dump carries the counter snapshot");
    require(body.contains("server=lobby"), "dump names the backend");
    // Everything the proxy is trusted with. A future field that drags any of this in fails here.
    for (String forbidden : List.of(SECRET, "10.11.12.13", "24601", "forwarding.secret",
        "sessionserver", "hasJoined", "accessToken", "25565")) {
      require(!body.contains(forbidden), "dump leaked " + forbidden + ": " + body);
    }
    require(admin.said("Counters and server names only"), "operator is told what the file holds");
  }

  /** Both dumps go beside the config, in a directory named dumps -- what .gitignore matches. */
  private static void dumpsLandInTheIgnoredDirectory() throws Exception {
    Path root = TempFiles.dir("conduit-dump-path");
    Path cwd = Path.of("").toAbsolutePath();
    ConduitRuntime runtime = runtimeAt(root);
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.INFO, Permissions.CONDUIT_ADMIN));
    try {
      CoreCommands.register(runtime);
      // No argument may steer the write. The file name is a timestamp; these are simply ignored.
      for (String argument : List.of("", " ../../escaped", " /etc/passwd", " ..\\..\\escaped.txt")) {
        runtime.commandManager().dispatch(admin, "/conduit dump" + argument);
      }
      // Dumps taken within one clock tick each keep their own file. Windows' clock moves every
      // half millisecond or more, and a repeated name overwrote the dump before it.
      for (int i = 0; i < 100; i++) runtime.commandManager().dispatch(admin, "/conduit dump");
    } finally {
      runtime.close();
    }
    Path dumps = root.resolve("dumps");
    require(Files.isDirectory(dumps), "dumps directory sits beside the config");
    try (var written = Files.list(dumps)) {
      List<Path> files = written.toList();
      require(files.size() == 104, "one file per run, got " + files.size());
      for (Path file : files) {
        require(file.getParent().equals(dumps), "no argument escaped the dumps directory: " + file);
        require(file.getFileName().toString().startsWith("conduit-")
            && file.getFileName().toString().endsWith(".txt"), "timestamped name: " + file.getFileName());
      }
    }
    require(!Files.exists(root.resolve("escaped.txt")) && !Files.exists(root.getParent().resolve("escaped")),
        "nothing was written outside the dumps directory");
    require(!Files.exists(cwd.resolve("dumps").resolve("conduit-escaped.txt")), "nothing landed in the working directory");
  }

  /**
   * A heap dump is every secret the process holds. The gate must hold, and the operator must be
   * told what the file is before it exists -- the .hprof this project once committed said nothing.
   */
  private static void heapIsGatedAndSaysWhatItHolds() throws Exception {
    Path root = TempFiles.dir("conduit-heap-audit");
    ConduitRuntime runtime = runtimeAt(root);
    try {
      CoreCommands.register(runtime);
      RecordingPlayer nosy = new RecordingPlayer("Nosy", "lobby", Set.of(Permissions.INFO));
      runtime.commandManager().dispatch(nosy, "/conduit heap");
      require(nosy.said("permission"), "heap is gated, said " + nosy.messages);
      require(!nosy.said("contains everything"), "a refused caller is told nothing else");
      require(!Files.exists(root.resolve("dumps")), "a refused heap dump writes nothing");

      RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.INFO, Permissions.HEAP));
      runtime.commandManager().dispatch(admin, "/conduit heap");
      require(admin.said("contains everything this process holds in memory"), "warned what it holds, said " + admin.messages);
      require(admin.said("forwarding secret"), "warning names the forwarding secret");
      require(admin.said("session tokens"), "warning names session tokens");
      require(admin.said("do not commit it"), "warning says not to commit it");
      if (admin.said("Heap dump written to")) {
        Path written = onlyFile(root.resolve("dumps"));
        require(written.getFileName().toString().endsWith(".hprof"), "heap file name");
        require(written.getParent().getFileName().toString().equals("dumps"), "heap goes to the ignored directory");
        Files.delete(written);
      }
    } finally {
      runtime.close();
    }
  }

  private static final String SECRET = "s3cr3t-forwarding-key-do-not-leak";

  private static Path onlyFile(Path directory) throws Exception {
    try (var files = Files.list(directory)) {
      List<Path> found = files.toList();
      require(found.size() == 1, "exactly one file in " + directory + ", got " + found);
      return found.getFirst();
    }
  }

  private static ConduitRuntime runtimeAt(Path root) {
    return runtime(root.resolve("plugins"), root);
  }

  /** The argument used to reach InetAddress.getByName, which resolves whatever it is handed. */
  private static void cacheInvalidateTakesLiteralAddressesOnly() throws Exception {
    Path root = TempFiles.dir("conduit-cache-arg");
    ConduitRuntime runtime = runtimeAt(root);
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.INFO, Permissions.CONDUIT_ADMIN));
    try {
      CoreCommands.register(runtime);
      // Names, not addresses. "ace.cafe" is the awkward one: every character is a hex digit, so a
      // character-class check would wave it through and getByName would go and resolve it.
      for (String name : List.of("example.com", "ace.cafe", "localhost", "1.2.3", "1.2.3.4.5",
          "256.1.1.1", "1.2.3.-1", "01234.1.1.1", "1.2.3.", "0x7f.0.0.1", "2130706433")) {
        admin.messages.clear();
        runtime.commandManager().dispatch(admin, "/conduit cache invalidate " + name);
        require(admin.said("Not an IP address"), "refused '" + name + "', said " + admin.messages);
      }
      admin.messages.clear();
      runtime.commandManager().dispatch(admin, "/conduit cache invalidate");
      require(admin.said("Usage: /conduit cache invalidate"), "a missing argument is a usage line");
      for (String literal : List.of("10.11.12.13", "0.0.0.0", "255.255.255.255", "::1", "fe80::1")) {
        admin.messages.clear();
        runtime.commandManager().dispatch(admin, "/conduit cache invalidate " + literal);
        require(!admin.said("Not an IP address"), "accepted literal " + literal + ", said " + admin.messages);
        require(admin.said("cache"), "answered about the cache for " + literal + ", said " + admin.messages);
      }
    } finally {
      runtime.close();
    }
  }

  /**
   * The DIRECT protocols this phase's command-tree changes actually touch, one per behaviour class:
   * 47 has no command tree at all, 393 is the oldest that has one, 765 adds the Configuration phase,
   * 776 is the parser-id shift the merge is written to avoid decoding. For each, the tree Conduit
   * would declare must parse back to the full command set with every node index in range.
   */
  private static void commandTreeHoldsAcrossDirectProtocols() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer player = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.CONDUIT_ADMIN));

    // 47 (1.8.9): no command tree on the wire. Completion is the server Tab-Complete path only.
    ProtocolDefinition legacy = ProtocolDefinition.forVersion(47);
    require(!legacy.capabilities().commandTree(), "1.8.9 declares no command tree");
    require(fixture.commands.tabComplete(player, "/ser").equals(List.of("server")), "legacy completes /server");
    require(fixture.commands.tabComplete(player, "/con").equals(List.of("conduit")), "legacy completes /conduit");
    require(fixture.commands.tabComplete(player, "/server ").equals(List.of("lobby", "survival")), "legacy completes servers");
    require(fixture.commands.tabComplete(player, "/conduit ").equals(CoreCommands.CONDUIT_SUBCOMMANDS), "legacy completes subcommands");

    for (int version : new int[] { 393, 765, 776 }) {
      ProtocolDefinition protocol = ProtocolDefinition.forVersion(version);
      require(protocol.capabilities().commandTree(), version + " has a command tree");
      byte[] declared = CommandGraphs.mergeProxyCommands(protocol, backendTree(protocol, "gamemode"), fixture.names());

      int expected = protocol.id(gg.tame.conduit.protocol.ConnectionState.PLAY,
          gg.tame.conduit.protocol.PacketDirection.SERVER_TO_CLIENT,
          gg.tame.conduit.protocol.PacketKind.PLAY_DECLARE_COMMANDS);
      var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(declared));
      require(gg.tame.conduit.protocol.MinecraftInput.varInt(input) == expected, version + " keeps the packet id");

      // decode() validates every child and redirect index against the node count and refuses
      // trailing bytes, so this is the structural check a client would otherwise make for us. It is
      // also what proves the argument nodes: a parser named the wrong way, or with the wrong id,
      // makes its properties the wrong length and every byte after it shifts, which shows up here as
      // an index out of bounds or trailing bytes rather than as a silently broken client.
      CommandGraph graph = CommandGraph.decode(body(declared), gg.tame.conduit.command.ParserIds.forProtocol(version));
      gg.tame.conduit.command.ParserIds parsers = gg.tame.conduit.command.ParserIds.forProtocol(version);
      List<String> roots = rootChildren(declared, parsers);
      require(roots.contains("gamemode"), version + " keeps the backend's own command");
      for (String name : List.of("server", "send", "conduit", "glist", "plist", "find", "alert",
          "ping", "hub", "gkick", "lobby", "survival")) {
        require(roots.contains(name), version + " declares /" + name + ", got " + roots);
      }
      require(roots.stream().distinct().count() == roots.size(), version + " declares no name twice");
      require(childrenOf(declared, "conduit", parsers).equals(CoreCommands.CONDUIT_SUBCOMMANDS),
          version + " declares every /conduit subcommand");
      require(childrenOf(declared, "server", parsers).equals(fixture.names()),
          version + " declares the servers under /server");
      require(childrenOf(declared, "send", parsers).getFirst().equals("current"),
          version + " declares /send current");
      // The point of the whole change: /conduit drain lists the servers, and /gkick has an argument
      // the client will ask Conduit about instead of a dead end.
      require(drainChildren(declared, parsers).equals(fixture.names()),
          version + " declares the servers under /conduit drain, got " + drainChildren(declared, parsers));
      require(childrenOf(declared, "gkick", parsers).equals(List.of("player")),
          version + " declares a player argument under /gkick");
      require(graph.root() == 0, version + " root is node 0");
    }
  }

  /** The whole native plugin path: a real jar through discover, enable, use, disable. */
  private static void pluginJarEndToEnd() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-e2e");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    Path jar = plugins.resolve("Demo.jar");
    buildPluginJar(jar, "demo", "demo.DemoPlugin", 1, DEMO_PLUGIN);
    SIGNALS.clear();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      Plugin plugin = runtime.plugins().plugin("demo").orElseThrow(() -> new AssertionError("plugin not loaded"));
      require(SIGNALS.contains("enable"), "onEnable ran, signals " + SIGNALS);
      require(runtime.pluginCatalog().size() == 1, "listed in the plugin catalog");
      require(plugin.dataDirectory().equals(plugins.toAbsolutePath().normalize().resolve("demo")), "data directory");

      ConsoleCommandSource console = new ConsoleCommandSource();
      require(runtime.commandManager().dispatch(console, "/d one two"), "plugin command registered under its alias");
      require(SIGNALS.contains("command:one,two"), "plugin command ran, signals " + SIGNALS);
      require(runtime.commandManager().names().contains("demo"), "plugin command is declarable to clients");

      runtime.events().fire(new ProxyStartEvent(runtime));
      require(SIGNALS.contains("event"), "plugin listener ran, signals " + SIGNALS);
      require(await("task"), "plugin scheduled task ran, signals " + SIGNALS);

      runtime.plugins().disable(plugin);
      require(SIGNALS.contains("disable"), "onDisable ran");
      require(runtime.plugins().plugin("demo").isEmpty(), "plugin removed");
      require(runtime.pluginCatalog().size() == 0, "catalog entry removed");
      require(runtime.commandManager().get("demo").isEmpty() && runtime.commandManager().get("d").isEmpty(),
          "command and alias released");
      SIGNALS.remove("event");
      runtime.events().fire(new ProxyStartEvent(runtime));
      require(!SIGNALS.contains("event"), "listener released");
    } finally {
      runtime.close();
    }
    require(deletable(jar), "plugin jar released by the closed classloader");
  }

  /** Jars the manager must reject without taking the proxy, or the next plugin, down with them. */
  private static void badPluginJarsDoNotStopTheProxy() throws Exception {
    Path root = TempFiles.dir("conduit-plugin-bad");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    Path noDescriptor = plugins.resolve("NoDescriptor.jar");
    try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(noDescriptor))) {
      jar.putNextEntry(new JarEntry("nothing.txt"));
      jar.write("hi".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    buildPluginJar(plugins.resolve("Malformed.jar"), "malformed", "malformed.MalformedPlugin", -1,
        plugin("malformed", "Malformed", ""));
    buildPluginJar(plugins.resolve("TooNew.jar"), "toonew", "toonew.ToonewPlugin", 99, plugin("toonew", "Toonew", ""));
    buildPluginJar(plugins.resolve("Boom.jar"), "boom", "boom.BoomPlugin", 1, BOOM_PLUGIN);
    buildPluginJar(plugins.resolve("Throws.jar"), "throws", "thrower.ThrowerPlugin", 1, THROWING_PLUGIN);
    buildPluginJar(plugins.resolve("DupeA.jar"), "dupe", "dupea.DupeaPlugin", 1, plugin("dupea", "Dupea", ""));
    buildPluginJar(plugins.resolve("DupeB.jar"), "dupe", "dupeb.DupebPlugin", 1, plugin("dupeb", "Dupeb", ""));
    buildPluginJar(plugins.resolve("Good.jar"), "good", "good.GoodPlugin", 1, plugin("good", "Good", ""));

    SIGNALS.clear();
    ConduitRuntime runtime = runtime(plugins, root);
    try {
      runtime.pluginRuntime().loadAll();
      require(runtime.plugins().plugin("good").isPresent(), "a good jar still loads beside the bad ones");
      require(runtime.plugins().plugin("malformed").isEmpty(), "malformed descriptor rejected");
      require(runtime.plugins().plugin("toonew").isEmpty(), "future api-version rejected");
      require(runtime.plugins().plugin("boom").isEmpty(), "class that fails to initialize rejected");
      require(runtime.plugins().plugin("throws").isEmpty(), "plugin whose onEnable threw is not enabled");
      require(runtime.plugins().plugin("dupe").isPresent(), "exactly one of the duplicate ids won");
      require(runtime.commandManager().get("halfway").isEmpty(), "a failed enable left no command registered");
      require(SIGNALS.contains("thrower-enable"), "the failing plugin really did run and throw");
    } finally {
      runtime.close();
    }
    try (var list = Files.list(plugins)) {
      for (Path jar : list.toList()) require(deletable(jar), "jar released after shutdown: " + jar.getFileName());
    }
  }

  // Signals from plugin code, which runs in its own classloader but shares this class through the
  // parent loader. A list, not a flag, so "which of these ran" is what a failure message says.
  private static final List<String> SIGNALS = java.util.Collections.synchronizedList(new ArrayList<>());
  public static void signal(String value) { SIGNALS.add(value); }

  private static boolean await(String value) throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (SIGNALS.contains(value)) return true;
      Thread.sleep(50);
    }
    return false;
  }

  private static boolean deletable(Path path) {
    try { return Files.deleteIfExists(path); } catch (java.io.IOException locked) { return false; }
  }

  private static ConduitRuntime runtime(Path plugins, Path root) {
    return new ConduitRuntime(new gg.tame.conduit.config.ConduitConfiguration(
        new java.net.InetSocketAddress("127.0.0.1", 1), 2048, gg.tame.conduit.config.ForwardingMode.NONE,
        java.util.Optional.empty(),
        List.of(new gg.tame.conduit.config.BackendServer("lobby", new java.net.InetSocketAddress("127.0.0.1", 2))),
        List.of("lobby"), List.of()), plugins, root);
  }

  /** Compiles the source in-process and packs it with a descriptor. */
  static void buildPluginJar(Path jar, String id, String mainClass, int apiVersion, String source) throws Exception {
    buildPluginJar(jar, id, mainClass, apiVersion, source, "");
  }
  /** {@code descriptorLines} is appended to conduit-plugin.yml as it stands, e.g. "depend: [a]\n". */
  static void buildPluginJar(Path jar, String id, String mainClass, int apiVersion, String source, String descriptorLines) throws Exception {
    Path work = TempFiles.dir("conduit-plugin-build");
    String pkg = mainClass.substring(0, mainClass.lastIndexOf('.'));
    String simple = mainClass.substring(mainClass.lastIndexOf('.') + 1);
    Path file = Files.createDirectories(work.resolve(pkg)).resolve(simple + ".java");
    Files.writeString(file, source);
    javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    require(compiler != null, "a JDK compiler is available to the tests");
    int status = compiler.run(null, null, null, "--release", "21",
        "-cp", System.getProperty("java.class.path"), "-d", work.toString(), file.toString());
    require(status == 0, "test plugin " + id + " compiled");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("conduit-plugin.yml"));
      out.write(("id: " + id + "\nname: " + id + "\nversion: 1.0.0\nmain: " + mainClass
          + "\napi-version: " + apiVersion + "\n" + descriptorLines).getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
      try (var classes = Files.walk(work.resolve(pkg))) {
        for (Path compiled : classes.filter(path -> path.toString().endsWith(".class")).toList()) {
          out.putNextEntry(new JarEntry(pkg.replace('.', '/') + "/" + compiled.getFileName()));
          out.write(Files.readAllBytes(compiled));
          out.closeEntry();
        }
      }
    }
  }

  private static String plugin(String pkg, String name, String body) {
    return "package " + pkg + ";\npublic final class " + name
        + "Plugin extends gg.tame.conduit.api.plugin.ConduitPlugin {\n" + body + "}\n";
  }

  private static final String DEMO_PLUGIN = """
      package demo;
      import gg.tame.conduit.api.command.CommandManager;
      import gg.tame.conduit.api.event.Subscribe;
      import gg.tame.conduit.api.event.proxy.ProxyStartEvent;
      import gg.tame.conduit.api.plugin.ConduitPlugin;
      import gg.tame.conduit.tests.CommandApiTests;
      import java.time.Duration;
      public final class DemoPlugin extends ConduitPlugin {
        @Override public void onEnable() {
          CommandApiTests.signal("enable");
          proxy().commands().register(this, CommandManager.Command.builder("demo").alias("d")
              .handler((source, arguments) -> CommandApiTests.signal("command:" + String.join(",", arguments)))
              .build());
          proxy().events().register(this, this);
          getScheduler().buildTask(this, () -> CommandApiTests.signal("task")).delay(Duration.ofMillis(1)).schedule();
        }
        @Subscribe public void onStart(ProxyStartEvent event) { CommandApiTests.signal("event"); }
        @Override public void onDisable() { CommandApiTests.signal("disable"); }
      }
      """;

  /** Its class can never initialize: Class.forName throws an Error, not an Exception. */
  private static final String BOOM_PLUGIN = """
      package boom;
      public final class BoomPlugin extends gg.tame.conduit.api.plugin.ConduitPlugin {
        static { if (Boolean.TRUE) throw new IllegalStateException("boom"); }
      }
      """;

  /** Registers, then throws: what it registered must not outlive the failed enable. */
  private static final String THROWING_PLUGIN = """
      package thrower;
      import gg.tame.conduit.api.command.CommandManager;
      import gg.tame.conduit.api.plugin.ConduitPlugin;
      import gg.tame.conduit.tests.CommandApiTests;
      public final class ThrowerPlugin extends ConduitPlugin {
        @Override public void onEnable() {
          CommandApiTests.signal("thrower-enable");
          proxy().commands().register(this, CommandManager.Command.builder("halfway").build());
          throw new IllegalStateException("enable failed");
        }
      }
      """;

  // --- helpers -------------------------------------------------------------

  private static RegisteredCommand cmd(String name, List<String> aliases) {
    return new RegisteredCommand(name, aliases, "conduit.test", (source, arguments) -> { }, (source, arguments) -> List.of());
  }

  private static byte[] body(byte[] packet) throws Exception {
    var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet));
    gg.tame.conduit.protocol.MinecraftInput.varInt(input);
    return input.readAllBytes();
  }

  /** Literal names hanging directly off the root of a declare-commands packet. */
  private static List<String> rootChildren(byte[] packet) throws Exception {
    return rootChildren(packet, gg.tame.conduit.command.ParserIds.INDEXED);
  }

  /** @param parsers how the release that wrote this tree names an argument node's parser */
  private static List<String> rootChildren(byte[] packet, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    CommandGraph graph = CommandGraph.decode(body(packet), parsers);
    return names(graph, graph.root(), parsers);
  }

  private static List<String> childrenOf(byte[] packet, String literal) throws Exception {
    return childrenOf(packet, literal, gg.tame.conduit.command.ParserIds.INDEXED);
  }

  private static List<String> childrenOf(byte[] packet, String literal, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    CommandGraph graph = CommandGraph.decode(body(packet), parsers);
    for (int index : childIndexes(graph, graph.root(), parsers)) {
      if (literal.equals(nameOf(graph, index, parsers))) return names(graph, index, parsers);
    }
    throw new IllegalStateException("no literal " + literal + " in the graph");
  }

  /** The children of /conduit drain, two levels down, which no other helper reaches. */
  private static List<String> drainChildren(byte[] packet, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    CommandGraph graph = CommandGraph.decode(body(packet), parsers);
    for (int conduit : childIndexes(graph, graph.root(), parsers)) {
      if (!"conduit".equals(nameOf(graph, conduit, parsers))) continue;
      for (int subcommand : childIndexes(graph, conduit, parsers)) {
        if ("drain".equals(nameOf(graph, subcommand, parsers))) return names(graph, subcommand, parsers);
      }
    }
    throw new IllegalStateException("no /conduit drain in the graph");
  }

  private static List<String> names(CommandGraph graph, int node) throws Exception {
    return names(graph, node, gg.tame.conduit.command.ParserIds.INDEXED);
  }

  private static List<String> names(CommandGraph graph, int node, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    List<String> result = new ArrayList<>();
    for (int index : childIndexes(graph, node, parsers)) result.add(nameOf(graph, index, parsers));
    return result;
  }

  // CommandGraph.Node keeps its fields private; the encoded packet is the only public view of them.
  private static List<Integer> childIndexes(CommandGraph graph, int node) throws Exception {
    return childIndexes(graph, node, gg.tame.conduit.command.ParserIds.INDEXED);
  }

  private static List<Integer> childIndexes(CommandGraph graph, int node, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    return decoded(graph, parsers).get(node).children;
  }

  private static String nameOf(CommandGraph graph, int node) throws Exception {
    return nameOf(graph, node, gg.tame.conduit.command.ParserIds.INDEXED);
  }

  private static String nameOf(CommandGraph graph, int node, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    return decoded(graph, parsers).get(node).name;
  }

  /**
   * Re-encodes and re-reads the graph, which is the only way at its private fields. A node keeps the
   * parser form it was decoded with, so the flag has to travel with it.
   */
  private static List<RawNode> decoded(CommandGraph graph, gg.tame.conduit.command.ParserIds parsers) throws Exception {
    byte[] encoded = graph.encode(0);
    var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(encoded));
    gg.tame.conduit.protocol.MinecraftInput.varInt(input);
    int count = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
    List<RawNode> nodes = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      int flags = input.readUnsignedByte();
      int childCount = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      List<Integer> children = new ArrayList<>(childCount);
      for (int child = 0; child < childCount; child++) children.add(gg.tame.conduit.protocol.MinecraftInput.varInt(input));
      if ((flags & 0x08) != 0) gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      int type = flags & 0x03;
      String name = (type == 1 || type == 2) ? gg.tame.conduit.protocol.MinecraftInput.string(input, 32767) : null;
      if (type == 2) {
        int parser = parsers.indexed()
            ? parsers.canonical(gg.tame.conduit.protocol.MinecraftInput.varInt(input))
            : gg.tame.conduit.command.ArgumentProperties.idFor(
                gg.tame.conduit.protocol.MinecraftInput.string(input, 32767));
        gg.tame.conduit.command.ArgumentProperties.read(input, parser);
      }
      if ((flags & 0x10) != 0) gg.tame.conduit.protocol.MinecraftInput.string(input, 32767);
      nodes.add(new RawNode(children, name));
    }
    return nodes;
  }

  private record RawNode(List<Integer> children, String name) {}

  private record Ping() implements Event {}

  private static final class Fixture {
    private final ServerRegistry registry;
    private final PlayerManager players = new PlayerManager();
    private final CommandManager commands = new CommandManager();
    private Fixture() throws Exception {
      Path config = TempFiles.file("conduit-commands", ".toml");
      Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n"
          + "[forwarding]\nmode=\"none\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=1\n"
          + "[servers.survival]\nhost=\"127.0.0.1\"\nport=2\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"lobby\"]\n");
      registry = new ServerRegistry(ConfigurationLoader.load(config));
      CoreCommands.register(commands, registry, players);
    }
    private List<String> names() { return registry.names(); }
  }

  private static final class RecordingPlayer implements CommandSource, TrackedPlayer {
    private final String username;
    private final UUID id = UUID.randomUUID();
    private final Set<String> permissions;
    private final List<String> messages = new ArrayList<>();
    private String backend;
    private boolean failTransfer;
    private RecordingPlayer(String username, String backend, Set<String> permissions) {
      this.username = username;
      this.backend = backend;
      this.permissions = permissions;
    }
    @Override public UUID uniqueId() { return id; }
    @Override public String username() { return username; }
    @Override public String currentBackend() { return backend; }
    @Override public boolean transferTo(String serverName) {
      if (failTransfer) return false;
      backend = serverName;
      return true;
    }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public void sendMessage(Text text) { messages.add(text == null ? "" : text.plain()); }
    private boolean said(String fragment) {
      String needle = fragment.toLowerCase(Locale.ROOT);
      return messages.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(needle));
    }
  }

  private record TestPlugin(String id) implements Plugin {
    @Override public PluginDescription description() { return new PluginDescription(id, id, "1.0", "Main", 1, List.of()); }
    @Override public ConduitProxy proxy() { return null; }
    @Override public Logger getLogger() { return Logger.getLogger("test." + id); }
    @Override public Path dataDirectory() { return Path.of("."); }
    @Override public Scheduler getScheduler() { return null; }
    @Override public void onLoad() { }
    @Override public void onEnable() { }
    @Override public void onDisable() { }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

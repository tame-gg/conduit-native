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
    sendSelfNeedsNoOthersPermission();
    sendCurrentReportsFailure();
    conduitSubcommandsMatchTheGraph();
    helpListsWhatTheSourceMayRun();
    graphCarriesPluginCommands();
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
    pluginJarEndToEnd();
    badPluginJarsDoNotStopTheProxy();
    System.out.println("CommandApiTests OK");
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
    for (String bad : List.of("/slash", "two words", " ")) {
      boolean threw = false;
      try { cmd(bad, List.of()); } catch (IllegalArgumentException expected) { threw = true; }
      require(threw, "rejected unreachable command name: '" + bad + "'");
    }
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
    RecordingPlayer kyle = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.SERVER_USE));
    kyle.failTransfer = true;
    fixture.players.add(kyle);
    fixture.commands.dispatch(kyle, "/server survival");
    require(kyle.said("is unavailable"), "failed switch is reported, said " + kyle.messages);
  }

  private static void sendSelfNeedsNoOthersPermission() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer kyle = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.SERVER_SEND));
    fixture.players.add(kyle);
    fixture.commands.dispatch(kyle, "/send Kyle survival");
    require(kyle.backend.equals("survival"), "player sent themselves, said " + kyle.messages);
    require(!kyle.said("permission"), "no permission complaint for a self-send");

    RecordingPlayer steve = new RecordingPlayer("Steve", "lobby", Set.of(Permissions.SERVER_SEND));
    fixture.players.add(steve);
    fixture.commands.dispatch(kyle, "/send Steve survival");
    require(kyle.said("permission"), "still refused for somebody else");
    require(steve.backend.equals("lobby"), "somebody else was not moved");
  }

  private static void sendCurrentReportsFailure() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer kyle = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.SERVER_SEND));
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
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_INFO));
    List<String> completed = fixture.commands.tabComplete(admin, "/conduit ");
    require(completed.equals(CoreCommands.CONDUIT_SUBCOMMANDS), "completer offers every subcommand");
    List<String> graph = childrenOf(CommandGraphs.proxyOnly(ProtocolDefinition.forVersion(765), fixture.names()), "conduit");
    require(graph.equals(CoreCommands.CONDUIT_SUBCOMMANDS), "graph declares the same ones, got " + graph);
    for (String sub : CoreCommands.CONDUIT_SUBCOMMANDS) {
      admin.messages.clear();
      fixture.commands.dispatch(admin, "/conduit " + sub);
      require(!admin.said("Unknown /conduit subcommand"), "/conduit " + sub + " is a real subcommand");
    }
  }

  /** /conduit help is the in-game command reference; it listed neither /glist nor its neighbours. */
  private static void helpListsWhatTheSourceMayRun() throws Exception {
    Fixture fixture = new Fixture();
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_INFO, Permissions.CONDUIT_ADMIN));
    fixture.commands.dispatch(admin, "/conduit help");
    for (String listed : List.of("/server", "/send", "/glist", "/plist", "/find", "/alert", "/ping", "/gkick", "/hub", "/conduit")) {
      require(admin.said(listed), "help lists " + listed + ", said " + admin.messages);
    }
    RecordingPlayer plain = new RecordingPlayer("Guest", "lobby", Set.of(Permissions.CONDUIT_INFO, Permissions.SERVER_USE));
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
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of("conduit.test", Permissions.SERVER_USE,
        Permissions.SERVER_SEND, Permissions.CONDUIT_INFO));
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
    RecordingPlayer relay = new RecordingPlayer("Op", "", Set.of(Permissions.SERVER_USE));
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
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_INFO, Permissions.CONDUIT_ADMIN));
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
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_INFO, Permissions.CONDUIT_ADMIN));
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
      RecordingPlayer nosy = new RecordingPlayer("Nosy", "lobby", Set.of(Permissions.CONDUIT_INFO));
      runtime.commandManager().dispatch(nosy, "/conduit heap");
      require(nosy.said("permission"), "heap is gated, said " + nosy.messages);
      require(!nosy.said("contains everything"), "a refused caller is told nothing else");
      require(!Files.exists(root.resolve("dumps")), "a refused heap dump writes nothing");

      RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_INFO, Permissions.HEAP));
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
    RecordingPlayer admin = new RecordingPlayer("Op", "lobby", Set.of(Permissions.CONDUIT_INFO, Permissions.CONDUIT_ADMIN));
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
    RecordingPlayer player = new RecordingPlayer("Kyle", "lobby", Set.of(Permissions.SERVER_USE,
        Permissions.SERVER_SEND, Permissions.CONDUIT_INFO));

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
      // trailing bytes, so this is the structural check a client would otherwise make for us.
      CommandGraph graph = CommandGraph.decode(body(declared));
      List<String> roots = rootChildren(declared);
      require(roots.contains("gamemode"), version + " keeps the backend's own command");
      for (String name : List.of("server", "send", "conduit", "glist", "plist", "find", "alert",
          "ping", "hub", "gkick", "lobby", "survival")) {
        require(roots.contains(name), version + " declares /" + name + ", got " + roots);
      }
      require(roots.stream().distinct().count() == roots.size(), version + " declares no name twice");
      require(childrenOf(declared, "conduit").equals(CoreCommands.CONDUIT_SUBCOMMANDS),
          version + " declares every /conduit subcommand");
      require(childrenOf(declared, "server").equals(fixture.names()), version + " declares the servers under /server");
      require(childrenOf(declared, "send").getFirst().equals("current"), version + " declares /send current");
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
  private static void buildPluginJar(Path jar, String id, String mainClass, int apiVersion, String source) throws Exception {
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
          + "\napi-version: " + apiVersion + "\n").getBytes(StandardCharsets.UTF_8));
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
    CommandGraph graph = CommandGraph.decode(body(packet));
    return names(graph, graph.root());
  }

  private static List<String> childrenOf(byte[] packet, String literal) throws Exception {
    CommandGraph graph = CommandGraph.decode(body(packet));
    for (int index : childIndexes(graph, graph.root())) {
      if (literal.equals(nameOf(graph, index))) return names(graph, index);
    }
    throw new IllegalStateException("no literal " + literal + " in the graph");
  }

  private static List<String> names(CommandGraph graph, int node) throws Exception {
    List<String> result = new ArrayList<>();
    for (int index : childIndexes(graph, node)) result.add(nameOf(graph, index));
    return result;
  }

  // CommandGraph.Node keeps its fields private; the encoded packet is the only public view of them.
  private static List<Integer> childIndexes(CommandGraph graph, int node) throws Exception {
    return decoded(graph).get(node).children;
  }

  private static String nameOf(CommandGraph graph, int node) throws Exception {
    return decoded(graph).get(node).name;
  }

  private static List<RawNode> decoded(CommandGraph graph) throws Exception {
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
        int parser = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
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

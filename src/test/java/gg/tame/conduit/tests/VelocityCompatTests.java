package gg.tame.conduit.tests;

import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.SecuritySettings;
import gg.tame.conduit.config.StatusSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PluginMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The Velocity compatibility layer, end to end: purpose-built plugins compiled against the real
 * velocity-api (its annotation processor writes their velocity-plugin.json), loaded from jars by the
 * real loader into a real MinecraftProxy, driven by a scripted 1.8 client over two scripted backends.
 *
 * <p>Plugins cannot see this class (Conduit's packages are hidden from them), so they report back
 * through a queue left in the system properties.
 */
public final class VelocityCompatTests {
  public static void main(String[] a) throws Exception { run(); }

  static final String SIGNALS = "velocity.test.signals";
  private static final Queue<String> signals = new ConcurrentLinkedQueue<>();

  public static void run() throws Exception {
    installSignals();
    endToEnd();
    brokenPluginsAreRejected();
    System.out.println("VelocityCompatTests OK");
  }

  // ---------------------------------------------------------------- the plugins

  /** Shared by every test plugin: how it reports what happened. */
  private static final String SIGNAL_METHOD = """
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("velocity.test.signals")).add(value); }
      """;

  private static final String VLIB = """
      package vlib;
      import com.velocitypowered.api.plugin.Plugin;
      import javax.inject.Inject;
      @Plugin(id = "vlib", name = "VLib", version = "0.1")
      public final class VLib {
      """ + SIGNAL_METHOD + """
        private final com.velocitypowered.api.proxy.ProxyServer proxy;
        @Inject public VLib(net.kyori.adventure.text.logger.slf4j.ComponentLogger logger, com.velocitypowered.api.proxy.ProxyServer proxy) {
          this.proxy = proxy;
          logger.info(net.kyori.adventure.text.Component.text("vlib component log"));
          signal("vlib-constructed");
        }
        @com.velocitypowered.api.event.Subscribe
        public void init(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
          var commands = proxy.getCommandManager();
          commands.register(commands.metaBuilder("vlibstop").plugin(this).build(), (com.velocitypowered.api.command.SimpleCommand) invocation -> {
            signal("vlib-stop");
            proxy.shutdown();
          });
        }
        @com.velocitypowered.api.event.Subscribe
        public void stop(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) { signal("vlib-shutdown:" + proxy.isShuttingDown()); }
        public static String greeting() { return "from-vlib"; }
      }
      """;

  private static final String VTEST = """
      package vtest;
      import com.mojang.brigadier.arguments.IntegerArgumentType;
      import com.velocitypowered.api.command.BrigadierCommand;
      import com.velocitypowered.api.command.CommandManager;
      import com.velocitypowered.api.command.CommandSource;
      import com.velocitypowered.api.command.RawCommand;
      import com.velocitypowered.api.command.SimpleCommand;
      import com.velocitypowered.api.event.Continuation;
      import com.velocitypowered.api.event.EventManager;
      import com.velocitypowered.api.event.EventTask;
      import com.velocitypowered.api.event.PostOrder;
      import com.velocitypowered.api.event.ResultedEvent;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.command.CommandExecuteEvent;
      import com.velocitypowered.api.event.connection.DisconnectEvent;
      import com.velocitypowered.api.event.connection.LoginEvent;
      import com.velocitypowered.api.event.connection.PluginMessageEvent;
      import com.velocitypowered.api.event.connection.PostLoginEvent;
      import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
      import com.velocitypowered.api.event.player.KickedFromServerEvent;
      import com.velocitypowered.api.event.player.PlayerChatEvent;
      import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
      import com.velocitypowered.api.event.player.ServerConnectedEvent;
      import com.velocitypowered.api.event.player.ServerPreConnectEvent;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.event.proxy.ProxyPingEvent;
      import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
      import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
      import com.velocitypowered.api.plugin.Dependency;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.plugin.PluginContainer;
      import com.velocitypowered.api.plugin.PluginDescription;
      import com.velocitypowered.api.plugin.annotation.DataDirectory;
      import com.velocitypowered.api.permission.Tristate;
      import com.velocitypowered.api.proxy.Player;
      import com.velocitypowered.api.proxy.ProxyServer;
      import com.velocitypowered.api.proxy.ServerConnection;
      import com.velocitypowered.api.proxy.server.RegisteredServer;
      import com.velocitypowered.api.proxy.server.ServerInfo;
      import com.velocitypowered.api.proxy.server.ServerPing;
      import com.velocitypowered.api.util.Favicon;
      import java.net.InetSocketAddress;
      import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
      import java.nio.charset.StandardCharsets;
      import java.nio.file.Path;
      import java.util.List;
      import java.util.concurrent.TimeUnit;
      import javax.inject.Inject;
      import net.kyori.adventure.text.Component;
      import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
      import org.slf4j.Logger;

      @Plugin(id = "vtest", name = "VTest", version = "1.2.3", authors = {"conduit"}, dependencies = {@Dependency(id = "vlib")})
      public final class VTest {
        static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("vtest", "chan");
        private final ProxyServer proxy;
        private final Logger logger;
        @Inject private EventManager events;
        @Inject private com.google.inject.Injector injector;
        @Inject private final com.velocitypowered.api.plugin.PluginManager pluginManager;
        private com.velocitypowered.api.scheduler.Scheduler scheduler;
        @Inject void scheduler(com.velocitypowered.api.command.CommandManager commands, com.velocitypowered.api.scheduler.Scheduler injected) { this.scheduler = injected; }

        @Inject
        public VTest(ProxyServer proxy, Logger logger, @DataDirectory Path data, PluginContainer container, PluginDescription description,
                     Metrics.Factory metrics) {
          this.proxy = proxy;
          this.logger = logger;
          this.pluginManager = null;
          signal("metrics:" + (metrics.proxy == proxy) + ":" + (metrics.data == data));
          signal("constructed:" + description.getVersion().orElse("?") + ":" + container.getDescription().getId() + ":" + data.getFileName() + ":" + data.getParent().getFileName());
        }
      """ + SIGNAL_METHOD + """

        @Subscribe
        public void onInit(ProxyInitializeEvent event) {
          logger.info("vtest initializing");
          signal("init:" + (events == proxy.getEventManager()) + ":" + (pluginManager == proxy.getPluginManager()) + ":" + (scheduler == proxy.getScheduler()));
          CommandManager commands = proxy.getCommandManager();
          commands.register(commands.metaBuilder("vtest").aliases("vt", "/vt").plugin(this).build(), new Main());
          commands.register(commands.metaBuilder("vraw").plugin(this).build(), (RawCommand) invocation ->
              invocation.source().sendMessage(Component.text("raw:" + invocation.alias() + ":" + invocation.arguments())));
          BrigadierCommand brigadier = new BrigadierCommand(BrigadierCommand.literalArgumentBuilder("vbrig")
              .then(BrigadierCommand.requiredArgumentBuilder("n", IntegerArgumentType.integer())
                  .executes(context -> {
                    context.getSource().sendMessage(Component.text("brig:" + IntegerArgumentType.getInteger(context, "n")));
                    return 1;
                  })));
          commands.register(commands.metaBuilder(brigadier).plugin(this).build(), brigadier);
          // A plugin's own alias can be registered again, and the newer command wins; another plugin's cannot.
          commands.register(commands.metaBuilder("vre").plugin(this).build(), (SimpleCommand) invocation -> invocation.source().sendMessage(Component.text("vre first")));
          commands.register(commands.metaBuilder("vre").plugin(this).build(), (SimpleCommand) invocation -> invocation.source().sendMessage(Component.text("vre second")));
          boolean taken = false;
          try { commands.register(commands.metaBuilder("vlibstop").plugin(this).build(), (SimpleCommand) invocation -> { }); }
          catch (IllegalArgumentException expected) { taken = true; }
          signal("reregister:" + taken);
          events.register(this, new Listeners());
          events.register(this, DisconnectEvent.class, (short) 5, gone -> signal("functional-disconnect:" + gone.getPlayer().getUsername()));
          proxy.getScheduler().buildTask(this, () -> signal("task")).delay(500, TimeUnit.MILLISECONDS).schedule();
          proxy.getScheduler().buildTask(this, task -> signal("tick")).repeat(100, TimeUnit.MILLISECONDS).schedule();
          proxy.getChannelRegistrar().register(CHANNEL);
          signal("servers:" + proxy.getAllServers().stream().map(server -> server.getServerInfo().getName()).sorted().toList());
          signal("lookup:" + proxy.getServer("survival").isPresent() + ":" + proxy.getServer("nope").isPresent() + ":" + proxy.matchServer("SUR").size());
          signal("lib:" + vlib.VLib.greeting());
          RegisteredServer extra = proxy.registerServer(new ServerInfo("extra", new InetSocketAddress("127.0.0.1", 1)));
          boolean listed = proxy.getServer("extra").isPresent();
          boolean duplicate = false;
          try { proxy.registerServer(new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 2))); } catch (IllegalArgumentException expected) { duplicate = true; }
          proxy.unregisterServer(extra.getServerInfo());
          signal("register:" + listed + ":" + duplicate + ":" + proxy.getServer("extra").isPresent());
          var config = proxy.getConfiguration();
          signal("config:" + config.isOnlineMode() + ":" + config.getAttemptConnectionOrder() + ":" + config.getServers().size()
              + ":" + proxy.getVersion().getName() + ":" + (proxy.getBoundAddress().getPort() > 0));
          signal("serverlist:" + config.getShowMaxPlayers() + ":" + plain(config.getMotd()) + ":" + config.getFavicon().map(Favicon::getBase64Url).orElse("none"));
          signal("tasks:" + proxy.getScheduler().tasksByPlugin(this).size());
          proxy.getPluginManager().getPlugin("vtest").orElseThrow().getExecutorService().execute(() -> signal("executor"));
          signal("classpath:" + extendClasspath());
          signal("plugins:" + proxy.getPluginManager().isLoaded("vlib") + ":" + proxy.getPluginManager().fromInstance(this).isPresent()
              + ":" + commands.hasCommand("vt") + ":" + commands.hasCommand("server"));
          // Guice: the injector holds what Conduit injects, and a child adds the plugin's own bindings.
          com.google.inject.Injector child = injector.createChildInjector(binder ->
              binder.bind(String.class).annotatedWith(com.google.inject.name.Names.named("greeting")).toInstance("hi"));
          Greeter greeter = child.getInstance(Greeter.class);
          signal("guice:" + (injector.getInstance(ProxyServer.class) == proxy) + ":" + (greeter.proxy == proxy) + ":" + greeter.greeting
              + ":" + greeter.data.getFileName() + ":" + (greeter.scheduler == proxy.getScheduler())
              + ":" + (injector.getInstance(PluginContainer.class) == proxy.getPluginManager().fromInstance(this).orElseThrow()));
        }

        public static final class Greeter {
          final ProxyServer proxy; final String greeting; final Path data; final com.velocitypowered.api.scheduler.Scheduler scheduler;
          @com.google.inject.Inject Greeter(ProxyServer proxy, @com.google.inject.name.Named("greeting") String greeting, @DataDirectory Path data,
                                           com.velocitypowered.api.scheduler.Scheduler scheduler, Logger logger) {
            this.proxy = proxy; this.greeting = greeting; this.data = data; this.scheduler = scheduler;
          }
        }

        /** Shaped like the bStats factory nearly every plugin bundles: a class of the plugin's own, built by injection. */
        public static final class Metrics {
          public static final class Factory {
            final ProxyServer proxy;
            final Path data;
            @Inject private Factory(ProxyServer proxy, Logger logger, @DataDirectory Path data) { this.proxy = proxy; this.data = data; }
          }
        }

        static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
        /** The last server-list connection, for the internals check. */
        static volatile com.velocitypowered.api.proxy.InboundConnection pinged;

        @Subscribe
        public void onShutdown(ProxyShutdownEvent event) { signal("shutdown"); }

        private final class Main implements SimpleCommand {
          @Override public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            Player player = source instanceof Player p ? p : null;
            switch (args.length == 0 ? "" : args[0]) {
              case "hello" -> source.sendMessage(Component.text("hello " + (player == null ? "console" : player.getUsername()) + " via " + invocation.alias()));
              case "whoami" -> {
                Player byName = proxy.getPlayer(player.getUsername()).orElseThrow();
                Player byId = proxy.getPlayer(player.getUniqueId()).orElseThrow();
                String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse("none");
                source.sendMessage(Component.text("found " + byName.getUsername() + " on " + server + " same=" + byName.equals(byId)
                    + " proto=" + player.getProtocolVersion().getProtocol() + " count=" + proxy.getPlayerCount()
                    + " match=" + proxy.matchPlayer(player.getUsername().substring(0, 2)).size()
                    + " there=" + proxy.getServer(server).map(s -> s.getPlayersConnected().size()).orElse(-1)
                    + " addr=" + player.getRemoteAddress().getAddress().getHostAddress() + " host=" + player.getVirtualHost().map(InetSocketAddress::getHostString).orElse("?")
                    + " profile=" + player.getGameProfile().getName() + " active=" + player.isActive() + " perm=" + player.hasPermission("vtest.use")
                    + " state=" + player.getProtocolState() + " online=" + player.isOnlineMode()));
              }
              case "deadsend" -> {
                RegisteredServer dead = proxy.registerServer(new ServerInfo("dead", new InetSocketAddress("127.0.0.1", 1)));
                player.createConnectionRequest(dead).connectWithIndication().thenAccept(ok -> {
                  proxy.unregisterServer(dead.getServerInfo());
                  signal("indicated:" + ok);
                });
              }
              case "send" -> player.createConnectionRequest(proxy.getServer(args[1]).orElseThrow()).connect()
                  .thenAccept(result -> signal("switch:" + result.getStatus()));
              case "refused" -> {
                RegisteredServer refuser = proxy.registerServer(new ServerInfo("refuser",
                    new InetSocketAddress("127.0.0.1", Integer.getInteger("velocity.test.refuser"))));
                player.createConnectionRequest(refuser).connect().thenAccept(result -> {
                  proxy.unregisterServer(refuser.getServerInfo());
                  signal("refused:" + result.getStatus());
                });
              }
              case "ping" -> {
                proxy.getServer("lobby").orElseThrow().ping().whenComplete((ping, failed) -> signal("backend-ping:" + (failed != null ? failed
                    : ping.getVersion().getProtocol() + ":" + ping.getVersion().getName() + ":" + ping.getPlayers().map(p -> p.getOnline() + "/" + p.getMax()).orElse("-")
                    + ":" + Thread.currentThread().getName().startsWith("conduit-velocity-"))));
                RegisteredServer dead = proxy.registerServer(new ServerInfo("deadping", new InetSocketAddress("127.0.0.1", 1)));
                dead.ping().whenComplete((ping, failed) -> {
                  proxy.unregisterServer(dead.getServerInfo());
                  signal("dead-ping:" + (failed != null));
                });
              }
              case "msg" -> {
                player.sendPluginMessage(CHANNEL, "to-client".getBytes(StandardCharsets.UTF_8));
                signal("sent-to-server:" + player.getCurrentServer().orElseThrow().sendPluginMessage(CHANNEL, "to-server".getBytes(StandardCharsets.UTF_8)));
                signal("sent-via-server:" + proxy.getServer("lobby").orElseThrow().sendPluginMessage(CHANNEL, "via-server".getBytes(StandardCharsets.UTF_8)));
                player.getCurrentServer().orElseThrow().getServer().sendMessage(Component.text("server-broadcast"));
                proxy.sendMessage(Component.text("proxy-broadcast"));
              }
              case "kick" -> player.disconnect(Component.text("bye from velocity"));
              case "unsupported" -> {
                try {
                  player.sendActionBar(Component.text("x"));
                  source.sendMessage(Component.text("silently accepted"));
                } catch (UnsupportedOperationException expected) {
                  source.sendMessage(Component.text("uoe: " + expected.getMessage()));
                }
              }
              case "internals" -> source.sendMessage(Component.text(internals()));
              case "locale" -> signal("locale:" + player.getUsername() + ":" + player.getPlayerSettings().getLocale() + ":"
                  + player.hasSentPlayerSettings() + ":" + player.getEffectiveLocale());
              case "perms" -> signal("perms:" + player.getUsername() + ":" + player.hasPermission("vtest.use") + ":"
                  + player.getPermissionValue("undefined.node") + ":" + player.getPermissionValue("other.node"));
              case "cached" -> {
                // White-box, for the test only: how many Player wrappers the adapter is holding on to.
                try {
                  java.lang.reflect.Field field = proxy.getClass().getDeclaredField("environment");
                  field.setAccessible(true);
                  Object environment = field.get(proxy);
                  java.lang.reflect.Field players = environment.getClass().getDeclaredField("players");
                  players.setAccessible(true);
                  signal("cached:" + ((java.util.Map<?, ?>) players.get(environment)).size());
                } catch (ReflectiveOperationException failed) {
                  signal("cached:" + failed);
                }
              }
              case "rich" -> source.sendRichMessage("<red>rich</red> <bold>text</bold>");
              case "exec" -> proxy.getCommandManager().executeAsync(source, "vtest hello")
                  .thenCombine(proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), "nside"), (mine, console) -> mine + ":" + console)
                  .thenCombine(proxy.getCommandManager().executeAsync(source, "no-such-command"), (both, missing) -> both + ":" + missing)
                  .thenCombine(proxy.getCommandManager().executeImmediatelyAsync(source, "vt hello"), (all, immediate) -> all + ":" + immediate)
                  .thenCombine(proxy.getCommandManager().offerSuggestions(source, "vtest h"), (all, suggestions) -> all + ":" + suggestions)
                  .thenAccept(results -> signal("exec:" + results));
              default -> source.sendMessage(Component.text("usage"));
            }
          }
          @Override public List<String> suggest(Invocation invocation) {
            return List.of("hello", "whoami", "send", "msg", "kick", "unsupported", "internals");
          }
        }

        /** PluginManager.addToClasspath: a jar written at runtime becomes visible to this plugin. */
        private boolean extendClasspath() {
          try {
            Path jar = proxy.getPluginManager().getPlugin("vtest").orElseThrow().getDescription().getSource().orElseThrow().resolveSibling("vtest").resolve("extra.jar");
            java.nio.file.Files.createDirectories(jar.getParent());
            try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
              out.putNextEntry(new java.util.jar.JarEntry("vtest-extra.txt"));
              out.write("extra".getBytes(StandardCharsets.UTF_8));
              out.closeEntry();
            }
            proxy.getPluginManager().addToClasspath(this, jar);
            return VTest.class.getClassLoader().getResource("vtest-extra.txt") != null;
          } catch (java.io.IOException failed) {
            return false;
          }
        }

        /** What a plugin could reach: Conduit classes by name, or Conduit types through the objects it was given. */
        private String internals() {
          for (String name : new String[] {"gg.tame.conduit.runtime.ConduitRuntime", "gg.tame.conduit.api.ConduitProxy", "gg.tame.conduit.session.PlayerSession"}) {
            try {
              Class.forName(name, false, VTest.class.getClassLoader());
              return "LEAK: loaded " + name;
            } catch (ClassNotFoundException expected) { }
          }
          Player anyPlayer = proxy.getAllPlayers().iterator().next();
          Object[] exposed = {proxy, proxy.getCommandManager(), proxy.getEventManager(), proxy.getScheduler(), proxy.getPluginManager(),
              proxy.getChannelRegistrar(), proxy.getConsoleCommandSource(), proxy.getConfiguration(), anyPlayer,
              anyPlayer.getCurrentServer().orElseThrow(), anyPlayer.getCurrentServer().orElseThrow().getServer(), pinged};
          // Declared methods, class by class: getMethods() would also resolve every Velocity API
          // signature, some of which name Adventure classes this class path does not have.
          for (Object object : exposed) {
            java.util.List<Class<?>> types = new java.util.ArrayList<>();
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
              types.add(type);
              for (Class<?> implemented : type.getInterfaces()) {
                if (conduit(implemented)) return "LEAK: implements " + implemented.getName();
                if (implemented.getName().startsWith("gg.tame.conduit.")) types.add(implemented);
              }
            }
            for (Class<?> type : types) {
              for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
                if (conduit(method.getReturnType())) return "LEAK: " + method;
                for (Class<?> parameter : method.getParameterTypes()) if (conduit(parameter)) return "LEAK: " + method;
              }
            }
          }
          return "internals hidden";
        }
        /** Conduit itself, as opposed to the adapter's own implementation classes. */
        private static boolean conduit(Class<?> type) {
          return type.getName().startsWith("gg.tame.conduit.") && !type.getName().startsWith("gg.tame.conduit.compat.velocity.");
        }

        public final class Listeners {
          /** Erin's permissions are the plugin's: vtest.* yes, undefined.node undefined, anything else no. */
          @Subscribe public void permissions(PermissionsSetupEvent event) {
            if (!(event.getSubject() instanceof Player player)) return;
            signal("permsetup:" + player.getUsername() + ":" + (event.createFunction(player).getPermissionValue("any") == Tristate.TRUE));
            if (!player.getUsername().equals("Erin")) return;
            event.setProvider(subject -> permission -> {
              if (permission.equals("nside.secret")) signal("perm-thread:" + Thread.currentThread().getName().startsWith("conduit-velocity-"));
              if (permission.startsWith("vtest.")) return Tristate.TRUE;
              return permission.equals("undefined.node") ? Tristate.UNDEFINED : Tristate.FALSE;
            });
          }
          @Subscribe public void login(LoginEvent event) {
            signal("login:" + event.getPlayer().getUsername());
            if (event.getPlayer().getUsername().equals("Erin")) {
              signal("login-perm:" + event.getPlayer().hasPermission("vtest.x") + ":" + event.getPlayer().hasPermission("other"));
            }
            if (event.getPlayer().getUsername().equals("Denied")) event.setResult(ResultedEvent.ComponentResult.denied(Component.text("no entry")));
          }
          @Subscribe(priority = 100) public EventTask postLoginFirst(PostLoginEvent event) {
            return EventTask.async(() -> signal("postlogin-first:" + event.getPlayer().getUsername()));
          }
          @Subscribe public void postLoginMiddle(PostLoginEvent event, Continuation continuation) {
            signal("postlogin-middle:" + event.getPlayer().getUsername());
            continuation.resume();
          }
          @Subscribe(order = PostOrder.LAST) public void postLoginLast(PostLoginEvent event) {
            signal("postlogin-last:" + event.getPlayer().getUsername());
          }
          @Subscribe public void chooseInitial(PlayerChooseInitialServerEvent event) {
            if (event.getPlayer().getUsername().equals("Bob")) event.setInitialServer(proxy.getServer("survival").orElseThrow());
          }
          @Subscribe public void preConnect(ServerPreConnectEvent event) {
            signal("preconnect:" + event.getOriginalServer().getServerInfo().getName());
            if (event.getPlayer().getUsername().equals("Carol")) event.setResult(ServerPreConnectEvent.ServerResult.allowed(proxy.getServer("survival").orElseThrow()));
            if (event.getPlayer().getUsername().equals("Dave")) event.setResult(ServerPreConnectEvent.ServerResult.denied());
          }
          @Subscribe public void connected(ServerConnectedEvent event) {
            signal("connected:" + event.getServer().getServerInfo().getName() + ":"
                + event.getPreviousServer().map(server -> server.getServerInfo().getName()).orElse("none"));
          }
          @Subscribe public void message(PluginMessageEvent event) {
            if (!event.getIdentifier().equals(CHANNEL)) return;
            String from = event.getSource() instanceof Player ? "player" : event.getSource() instanceof ServerConnection ? "server" : "?";
            signal("pm:" + from + ":" + new String(event.getData(), StandardCharsets.UTF_8));
            event.setResult(PluginMessageEvent.ForwardResult.handled());
          }
          @Subscribe public void command(CommandExecuteEvent event) {
            if (event.getCommand().startsWith("blocked")) event.setResult(CommandExecuteEvent.CommandResult.denied());
          }
          @Subscribe public void chat(PlayerChatEvent event) {
            signal("chat:" + event.getMessage());
            if (event.getMessage().equals("forbidden")) event.setResult(PlayerChatEvent.ChatResult.denied());
          }
          @Subscribe public void disconnect(DisconnectEvent event) {
            signal("disconnect:" + event.getPlayer().getUsername() + ":" + event.getLoginStatus());
          }
          /** The server list: rewritten for localhost, refused for deny.example, counts hidden for hidden.example. */
          @Subscribe public void ping(ProxyPingEvent event) {
            pinged = event.getConnection();
            String host = event.getConnection().getRawVirtualHost().orElse("?");
            ServerPing offered = event.getPing();
            signal("ping:" + host + ":" + event.getConnection().getProtocolVersion().getProtocol() + ":"
                + offered.getPlayers().map(p -> p.getOnline() + "/" + p.getMax()).orElse("-") + ":" + plain(offered.getDescriptionComponent())
                + ":" + offered.getFavicon().isPresent());
            signal("ping-port:" + host + ":" + event.getConnection().getVirtualHost().map(InetSocketAddress::getPort).orElse(-1));
            if (host.equals("deny.example")) {
              event.setResult(ResultedEvent.GenericResult.denied());
              return;
            }
            if (host.equals("hidden.example")) {
              event.setPing(offered.asBuilder().nullPlayers().build());
              return;
            }
            event.setPing(offered.asBuilder().description(Component.text("velocity motd")).maximumPlayers(42)
                .samplePlayers(new ServerPing.SamplePlayer("Sampled", new java.util.UUID(0, 7))).build());
          }
          /** Kicked from the server they play on: sent to the other one instead, with a message. */
          @Subscribe public void kicked(KickedFromServerEvent event) {
            String server = event.getServer().getServerInfo().getName();
            signal("kicked:" + server + ":" + event.getServerKickReason().map(VTest::plain).orElse("none") + ":"
                + event.kickedDuringServerConnect() + ":" + event.getResult().getClass().getSimpleName());
            if (event.kickedDuringServerConnect()) return;
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(proxy.getServer(server.equals("lobby") ? "survival" : "lobby").orElseThrow(),
                Component.text("redirected by vtest")));
          }
          /** Conduit never fires this; registering it must say so. */
          @Subscribe public void reload(ProxyReloadEvent event) { signal("reload"); }
        }
      }
      """;

  private static final String NATIVE_SIDE = """
      package nside;
      import gg.tame.conduit.api.command.CommandManager;
      import gg.tame.conduit.api.plugin.ConduitPlugin;
      public final class NsidePlugin extends ConduitPlugin {
        @Override public void onEnable() {
          proxy().commands().register(this, CommandManager.Command.builder("nside").handler((source, arguments) -> source.sendMessage("native ok")).build());
          proxy().commands().register(this, CommandManager.Command.builder("nsecret").permission("nside.secret").handler((source, arguments) -> source.sendMessage("secret ok")).build());
        }
      }
      """;

  // ---------------------------------------------------------------- end to end

  private static void endToEnd() throws Exception {
    signals.clear();
    Path root = TempFiles.dir("velocity-e2e");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    Path vlibClasses = compile(root, "vlib.VLib", VLIB, List.of(), true);
    jar(vlibClasses, plugins.resolve("VLib.jar"), null);
    jar(compile(root, "vtest.VTest", VTEST, List.of(vlibClasses), true), plugins.resolve("VTest.jar"), null);
    Path nativeClasses = compile(root, "nside.NsidePlugin", NATIVE_SIDE, List.of(), false);
    Files.writeString(nativeClasses.resolve("conduit-plugin.yml"), "id: nside\nname: nside\nversion: 1\nmain: nside.NsidePlugin\napi-version: 1\n");
    jar(nativeClasses, plugins.resolve("NativeSide.jar"), null);

    List<String> logged = new CopyOnWriteArrayList<>();
    Handler capture = new Handler() {
      @Override public void publish(LogRecord record) { logged.add(record.getLoggerName() + ": " + record.getMessage()); }
      @Override public void flush() { }
      @Override public void close() { }
    };
    Logger.getLogger("").addHandler(capture);

    Queue<String> backends = new ConcurrentLinkedQueue<>();
    List<Socket> open = new CopyOnWriteArrayList<>();
    try (ServerSocket lobby = new ServerSocket(0); ServerSocket survival = new ServerSocket(0); ServerSocket refuser = new ServerSocket(0)) {
      startBackend(lobby, "lobby", backends, open);
      startBackend(survival, "survival", backends, open);
      startRefuser(refuser, open);
      System.setProperty("velocity.test.refuser", Integer.toString(refuser.getLocalPort()));
      ConduitConfiguration configuration = configuration(List.of(
          new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobby.getLocalPort())),
          new BackendServer("survival", new InetSocketAddress("127.0.0.1", survival.getLocalPort()))));
      MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins);
      Thread serving = platform("proxy-serve", () -> { try { proxy.serve(); } catch (IOException ignored) { } });
      try {
        // Loading and initialization.
        awaitSignal("init:true:true:true");
        awaitSignal("plugins:true:true:true:true");
        for (String expected : List.of("reregister:true", "register:true:true:false", "config:false:[lobby]:2:Conduit:true", "tasks:2", "classpath:true",
            "serverlist:77:conduit motd:" + FAVICON)) {
          require(signals.contains(expected), expected + " in " + signals);
        }
        awaitSignal("executor");
        require(signals.contains("metrics:true:true"), "a plugin class with an @Inject constructor is built and injected: " + signals);
        awaitSignal("guice:true:true:hi:vtest:true:true");
        require(before("vlib-constructed", "constructed:1.2.3:vtest:vtest:plugins"), "dependency constructed first, injection complete: " + signals);
        require(signals.contains("servers:[lobby, survival]"), "registered servers listed: " + signals);
        require(signals.contains("lookup:true:false:1"), "server lookup and matching: " + signals);
        require(signals.contains("lib:from-vlib"), "a plugin sees the classes of the plugin it depends on: " + signals);
        
        var vtest = proxy.runtime().plugins().plugin("vtest").orElseThrow(() -> new AssertionError("vtest is a Conduit plugin"));
        require(proxy.runtime().plugins().plugin("nside").isPresent(), "a native plugin loads beside Velocity plugins");
        require(logged.stream().anyMatch(line -> line.startsWith("velocity: ") && line.contains("ProxyReloadEvent") && line.contains("never fires")),
            "a listener for an event Conduit never fires is reported: " + logged);
        require(logged.stream().noneMatch(line -> line.contains("ProxyPingEvent") && line.contains("never fires")), "ProxyPingEvent is fired: " + logged);

        // ProxyPingEvent: the plugin's ServerPing is the answer; a denied one is no answer at all.
        String answer = statusPing(proxy.port(), "localhost");
        awaitSignal("ping:localhost:47:0/77:conduit motd:true");
        require(answer != null && answer.contains("velocity motd") && answer.contains("\"max\":42") && answer.contains("\"name\":\"Sampled\"")
            && answer.contains(FAVICON), "the server list shows the plugin's answer: " + answer);
        awaitSignal("ping-port:localhost:" + proxy.port());
        require(statusPing(proxy.port(), "deny.example") == null, "a denied ping is not answered");
        awaitSignal("ping:deny.example:47:0/77:conduit motd:true");
        String hidden = statusPing(proxy.port(), "hidden.example");
        require(hidden != null && !hidden.contains("\"players\"") && hidden.contains("conduit motd"), "nullPlayers() hides the counts: " + hidden);
        require(logged.stream().anyMatch(line -> line.contains("[/vt] cannot be typed")), "an alias no player could type is skipped: " + logged);
        require(logged.contains("plugin.vtest: vtest initializing") && logged.contains("plugin.vlib: vlib component log"),
            "an injected slf4j Logger and ComponentLogger write to the plugin's Conduit logger: " + logged);

        // A denied login never reaches a backend.
        try (Client denied = Client.join(proxy.port(), "Denied")) {
          byte[] kicked = denied.await(frame -> text(frame).contains("no entry"), "login denial");
          require(kicked[0] == 0x00, "denied at login, with a login disconnect");
        }
        require(signals.contains("login:Denied") && backends.stream().noneMatch(line -> line.endsWith("login:Denied")),
            "the denied player never reached a backend: " + backends);

        try (Client alice = Client.join(proxy.port(), "Alice")) {
          alice.await(frame -> frame[0] == 0x01, "Join Game");
          awaitSignal("postlogin-last:Alice");
          require(before("postlogin-first:Alice", "postlogin-middle:Alice") && before("postlogin-middle:Alice", "postlogin-last:Alice"),
              "priority order, EventTask and Continuation handlers each finish before the next: " + signals);
          awaitSignal("connected:lobby:none");
          require(signals.contains("preconnect:lobby"), "the first connection raises ServerPreConnectEvent");

          // Commands.
          // The refused login left nothing behind: only Alice's wrapper is held.
          alice.chat("/vtest cached");
          awaitSignal("cached:1");
          alice.chat("/vtest hello");
          alice.awaitText("hello Alice via vtest");
          alice.chat("/vt whoami");
          alice.awaitText("found Alice on lobby same=true proto=47 count=1 match=1 there=1 addr=127.0.0.1 host=localhost profile=Alice active=true perm=true state=PLAY online=false");
          alice.chat("/vre");
          alice.awaitText("vre second");
          require(!alice.saw(frame -> text(frame).contains("vre first")), "the replaced command is gone");
          alice.chat("/vraw a b");
          alice.awaitText("raw:vraw:a b");
          alice.chat("/vbrig 5");
          alice.awaitText("brig:5");
          alice.chat("/vbrig x");
          alice.awaitText("position");
          alice.send(tabRequest("/vtest h"));
          alice.await(frame -> frame[0] == 0x3A && text(frame).contains("hello"), "tab completion from the plugin");
          alice.chat("/nside");
          alice.awaitText("native ok");
          alice.chat("/vtest exec");
          awaitSignal("exec:true:true:false:true:[hello]");

          // Chat from a pre-1.19 client can be refused.
          alice.chat("forbidden");
          alice.chat("allowed-chat");
          awaitIn(backends, "lobby:chat:allowed-chat");
          require(signals.contains("chat:forbidden") && !backends.contains("lobby:chat:forbidden"), "denied chat never reached the backend: " + backends);

          // Plugin messages: a registered channel is the plugin's, anything else passes through.
          alice.send(new PluginMessage("vtest:chan", bytes("from-client")).encode(0x17));
          alice.send(new PluginMessage("other:chan", bytes("pass")).encode(0x17));
          awaitIn(backends, "lobby:pm:other:chan:pass");
          require(signals.contains("pm:player:from-client"), "PluginMessageEvent from the client: " + signals);
          require(!backends.contains("lobby:pm:vtest:chan:from-client"), "a handled message is not forwarded");
          alice.chat("/vtest msg");
          alice.await(frame -> frame[0] == 0x3F && text(frame).contains("to-client"), "plugin message to the client");
          awaitIn(backends, "lobby:pm:vtest:chan:to-server");
          awaitIn(backends, "lobby:pm:vtest:chan:via-server");
          awaitSignal("sent-via-server:true");
          alice.awaitText("server-broadcast");
          alice.awaitText("proxy-broadcast");
          awaitSignal("sent-to-server:true");
          alice.await(frame -> frame[0] == 0x3F && text(frame).contains("after"), "the backend's unhandled message");
          require(signals.contains("pm:server:from-backend"), "PluginMessageEvent from the backend: " + signals);
          require(!alice.saw(frame -> text(frame).contains("from-backend")), "a handled backend message is not forwarded");

          // CommandExecuteEvent can refuse a command.
          alice.chat("/blocked");
          alice.chat("/notblocked");
          awaitIn(backends, "lobby:chat:/notblocked");
          require(!backends.contains("lobby:chat:/blocked"), "a denied command reached the backend: " + backends);

          // Unsupported calls say so; Conduit's internals are out of reach.
          alice.chat("/vtest unsupported");
          alice.awaitText("uoe: Audience.sendActionBar is not supported");
          alice.chat("/vtest internals");
          alice.awaitText("internals hidden");
          alice.chat("/vtest rich");
          alice.await(frame -> text(frame).contains("rich") && text(frame).contains("red") && text(frame).contains("bold"), "a MiniMessage rich message, formatting kept");

          // PermissionsSetupEvent: before LoginEvent, and the plugin's function answers for Erin
          // everywhere, Conduit's own command permission included. Alice keeps Conduit's default.
          awaitSignal("permsetup:Alice:true");
          Client erin = Client.join(proxy.port(), "Erin");
          erin.await(frame -> frame[0] == 0x01, "Erin's Join Game");
          require(before("permsetup:Erin:true", "login:Erin") && signals.contains("login-perm:true:false"),
              "permissions are set up before LoginEvent: " + signals);
          erin.chat("/vtest perms");
          awaitSignal("perms:Erin:true:UNDEFINED:FALSE");
          // The client's language reaches getPlayerSettings and getEffectiveLocale once it sends its settings.
          erin.chat("/vtest locale");
          awaitSignal("locale:Erin:en_US:false:null");
          erin.send(clientSettings("de_de"));
          erin.chat("/vtest locale");
          awaitSignal("locale:Erin:de_DE:true:de_DE");
          alice.chat("/vtest perms");
          awaitSignal("perms:Alice:true:TRUE:TRUE");
          erin.chat("/nsecret");
          erin.awaitText("permission");
          awaitSignal("perm-thread:true");
          alice.chat("/nsecret");
          alice.awaitText("secret ok");
          require(!erin.saw(frame -> text(frame).contains("secret ok")), "Erin's function refused a native command");

          // Scheduler.
          awaitSignal("task");
          awaitCount("tick", 2);

          // Switching between the two backends.
          alice.chat("/vtest deadsend");
          alice.awaitText("Unable to connect to dead");
          awaitSignal("indicated:false");
          alice.chat("/vtest send survival");
          awaitSignal("switch:SUCCESS");
          require(signals.contains("preconnect:survival") && signals.contains("connected:survival:lobby"), "switch events: " + signals);
          require(proxy.runtime().player("Alice").orElseThrow().currentServer().name().equals("survival"), "Alice is on survival");
          alice.chat("/vtest send survival");
          awaitSignal("switch:ALREADY_CONNECTED");

          // KickedFromServerEvent. A refused switch: Conduit's Notify, left alone, keeps Alice where she is.
          alice.chat("/vtest refused");
          awaitSignal("kicked:refuser:refuser says no:true:Notify");
          awaitSignal("refused:SERVER_DISCONNECTED");
          alice.awaitText("refuser says no");
          // Kicked while playing: the plugin's redirect moves her, with its message.
          alice.chat("kickme");
          awaitSignal("kicked:survival:backend says bye:false:DisconnectPlayer");
          awaitIn(backends, "lobby:login:Alice", 2);
          alice.awaitText("redirected by vtest");
          require(!alice.saw(frame -> frame[0] == 0x40), "the redirected player was not disconnected");
          require(proxy.runtime().player("Alice").orElseThrow().currentServer().name().equals("lobby"), "Alice was redirected to lobby");
          alice.chat("/vtest send survival");
          awaitCount("switch:SUCCESS", 2);
          // RegisteredServer.ping asks the backend; one that does not answer fails the future.
          alice.chat("/vtest ping");
          awaitSignal("backend-ping:47:Backend 1.8:3/20:true");
          awaitSignal("dead-ping:true");

          // ServerPreConnectEvent redirects Carol's first connection.
          try (Client carol = Client.join(proxy.port(), "Carol")) {
            carol.await(frame -> frame[0] == 0x01, "Carol's Join Game");
            awaitIn(backends, "survival:login:Carol");
            require(!backends.contains("lobby:login:Carol"), "Carol was redirected before lobby was dialled: " + backends);
          }
          // ServerPreConnectEvent can refuse every server: Dave never reaches a backend.
          try (Client dave = Client.join(proxy.port(), "Dave")) {
            dave.awaitClosed("Dave's connection");
          }
          require(backends.stream().noneMatch(line -> line.endsWith("login:Dave")), "a denied connection never dialled a backend: " + backends);
          // Let in, never joined: plugins still hear that Dave is gone, and the adapter lets go of him.
          awaitSignal("disconnect:Dave:PRE_SERVER_JOIN");
          // PlayerChooseInitialServerEvent picks Bob's first server; then Bob is kicked.
          try (Client bob = Client.join(proxy.port(), "Bob")) {
            bob.await(frame -> frame[0] == 0x01, "Bob's Join Game");
            awaitIn(backends, "survival:login:Bob");
            require(!backends.contains("lobby:login:Bob"), "Bob started on the server the plugin chose: " + backends);
            bob.chat("/vtest kick");
            byte[] kick = bob.await(frame -> text(frame).contains("bye from velocity"), "kick");
            require(kick[0] == 0x40, "kicked with a Disconnect packet");
            awaitSignal("disconnect:Bob:SUCCESSFUL_LOGIN");
            awaitSignal("functional-disconnect:Bob");
          }

          // Disabling the plugin takes its commands, tasks and listeners with it.
          proxy.runtime().plugins().disable(vtest);
          require(count("shutdown") == 1, "a disabled plugin gets its ProxyShutdownEvent: " + signals);
          require(!proxy.runtime().commands().hasCommand("vtest") && !proxy.runtime().commands().hasCommand("vbrig"), "commands released");
          int ticks = count("tick");
          Thread.sleep(400);
          require(count("tick") <= ticks + 1, "repeating task stopped");
          // Its permission function went with it: Erin is back on Conduit's default.
          erin.chat("/nsecret");
          erin.awaitText("secret ok");
          erin.close();
          alice.chat("/vtest hello");
          awaitIn(backends, "survival:chat:/vtest hello");
          int connected = count("connected:lobby:survival");
          alice.chat("/server lobby");
          awaitIn(backends, "lobby:login:Alice", 3);
          Thread.sleep(300);
          require(count("connected:lobby:survival") == connected, "listeners released: " + signals);
          require(proxy.runtime().plugins().plugin("vlib").isPresent(), "the dependency stays");

          // ProxyServer.shutdown stops the real proxy: serve() returns and the remaining plugin gets its ProxyShutdownEvent.
          alice.chat("/vlibstop");
          awaitSignal("vlib-stop");
          serving.join(20_000);
          require(!serving.isAlive(), "the proxy stopped serving");
          awaitSignal("vlib-shutdown:true");
        }
      } finally {
        Logger.getLogger("").removeHandler(capture);
        proxy.close();
        serving.join(10_000);
        for (Socket socket : open) try { socket.close(); } catch (IOException ignored) { }
      }
    }
    require(count("shutdown") == 1, "ProxyShutdownEvent reached vtest exactly once: " + signals);
    try (var jars = Files.list(plugins)) {
      for (Path jar : jars.filter(path -> path.toString().endsWith(".jar")).toList()) require(deletable(jar), "jar unlocked after shutdown: " + jar.getFileName());
    }
  }

  // ---------------------------------------------------------------- rejections

  private static final String PLAIN = """
      package %1$s;
      @com.velocitypowered.api.plugin.Plugin(id = "%1$s"%2$s)
      public final class Main {
      """ + SIGNAL_METHOD + """
        %3$s
        @com.velocitypowered.api.event.Subscribe
        public void init(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) { signal("%1$s-init"); }
      }
      """;

  private static void brokenPluginsAreRejected() throws Exception {
    signals.clear();
    Path root = TempFiles.dir("velocity-bad");
    Path plugins = Files.createDirectories(root.resolve("plugins"));
    plain(root, plugins, "okay", "", "");
    plain(root, plugins, "optdep", ", dependencies = @com.velocitypowered.api.plugin.Dependency(id = \"absent\", optional = true)", "");
    plain(root, plugins, "needy", ", dependencies = @com.velocitypowered.api.plugin.Dependency(id = \"absent\")", "");
    plain(root, plugins, "boom", "", "public Main() { signal(\"boom-ran\"); throw new IllegalStateException(\"boom\"); }");
    plain(root, plugins, "badinject", "", "@javax.inject.Inject public Main(Thread notInjectable) { }");
    Path classes = compile(root, "nomain.Main", PLAIN.formatted("nomain", "", ""), List.of(), false);
    jar(classes, plugins.resolve("NoMain.jar"), "{\"id\":\"nomain\",\"main\":\"nomain.Missing\"}");
    jar(classes, plugins.resolve("BadJson.jar"), "{\"id\": \"badjson\", \"main\": ");
    jar(classes, plugins.resolve("BadId.jar"), "{\"id\":\"Bad Id\",\"main\":\"nomain.Main\"}");

    ConduitConfiguration configuration = configuration(List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", 1))));
    try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(), plugins)) {
      proxy.runtime().pluginRuntime().loadAll();
      var loaded = proxy.runtime().plugins().plugins().stream().map(plugin -> plugin.description().id()).sorted().toList();
      require(loaded.equals(List.of("okay", "optdep")), "only the sound plugins load: " + loaded);
      require(signals.contains("boom-ran"), "the throwing constructor really ran");
      for (String rejected : List.of("Needy.jar", "Boom.jar", "Badinject.jar", "NoMain.jar", "BadJson.jar", "BadId.jar")) {
        require(deletable(plugins.resolve(rejected)), "rejected jar unlocked at once: " + rejected);
      }
      proxy.runtime().events().fire(new gg.tame.conduit.api.event.proxy.ProxyStartEvent(proxy.runtime()));
      awaitSignal("okay-init");
      awaitSignal("optdep-init");
    }
    require(deletable(plugins.resolve("Okay.jar")) && deletable(plugins.resolve("Optdep.jar")), "loaded jars unlocked after shutdown");
  }

  private static void plain(Path root, Path plugins, String id, String annotationExtra, String body) throws Exception {
    Path classes = compile(root, id + ".Main", PLAIN.formatted(id, annotationExtra, body), List.of(), true);
    jar(classes, plugins.resolve(Character.toUpperCase(id.charAt(0)) + id.substring(1) + ".jar"), null);
  }

  // ---------------------------------------------------------------- plugin building

  /**
   * Compiles one plugin against the test class path. With {@code processor} the velocity-api
   * annotation processor runs, as a plugin's build runs it, and writes velocity-plugin.json.
   */
  static Path compile(Path root, String mainClass, String source, List<Path> extraClassPath, boolean processor) throws Exception {
    Path work = Files.createTempDirectory(root, "build");
    Path sourceFile = Files.createDirectories(work.resolve("src").resolve(mainClass.substring(0, mainClass.lastIndexOf('.'))))
        .resolve(mainClass.substring(mainClass.lastIndexOf('.') + 1) + ".java");
    Files.writeString(sourceFile, source);
    Path classes = Files.createDirectories(work.resolve("classes"));
    StringBuilder classPath = new StringBuilder(System.getProperty("java.class.path"));
    for (Path extra : extraClassPath) classPath.append(java.io.File.pathSeparator).append(extra);
    List<String> arguments = new ArrayList<>(List.of("--release", "21", "-nowarn", "-cp", classPath.toString(), "-d", classes.toString()));
    if (processor) arguments.addAll(List.of("-processor", "com.velocitypowered.api.plugin.ap.PluginAnnotationProcessor"));
    else arguments.add("-proc:none");
    arguments.add(sourceFile.toString());
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int status = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, errors, errors, arguments.toArray(String[]::new));
    require(status == 0, "test plugin " + mainClass + " compiled:\n" + errors.toString(StandardCharsets.UTF_8));
    if (processor) require(Files.exists(classes.resolve("velocity-plugin.json")), "the velocity-api processor wrote velocity-plugin.json for " + mainClass);
    return classes;
  }

  /** Jars a class directory; {@code metadata}, when given, replaces velocity-plugin.json. */
  static void jar(Path classes, Path jar, String metadata) throws IOException {
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String name = classes.relativize(file).toString().replace('\\', '/');
        if (metadata != null && name.equals("velocity-plugin.json")) continue;
        out.putNextEntry(new JarEntry(name));
        out.write(Files.readAllBytes(file));
        out.closeEntry();
      }
      if (metadata != null) {
        out.putNextEntry(new JarEntry("velocity-plugin.json"));
        out.write(metadata.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
  }

  // ---------------------------------------------------------------- scripted sessions

  static final String FAVICON = "data:image/png;base64,iVBORw0KGgo=";

  /** A status request to the proxy: the JSON answer, or null when the proxy closed without one. */
  private static String statusPing(int port, String host) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      MinecraftFrames.write(socket.getOutputStream(), new Handshake(47, host, port, 1).encode());
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {0x00});
      byte[] response;
      try { response = MinecraftFrames.read(socket.getInputStream(), 1 << 16); }
      catch (java.io.EOFException | java.net.SocketException closed) { return null; }
      return MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(response, 1, response.length - 1)), 1 << 16);
    }
  }

  /** A 1.8 backend that refuses every login with "refuser says no". */
  private static void startRefuser(ServerSocket listener, List<Socket> open) {
    platform("backend-refuser", () -> {
      while (!listener.isClosed()) {
        try (Socket socket = listener.accept()) {
          open.add(socket);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.read(socket.getInputStream(), 4096);
          MinecraftFrames.write(socket.getOutputStream(), packet(0x00, "{\"text\":\"refuser says no\"}"));
        } catch (IOException ended) { }
      }
    });
  }

  /**
   * A 1.8 backend: answers status requests, logs the player in, records their chat and plugin
   * messages, answers "to-server", and kicks a player who says "kickme".
   */
  private static void startBackend(ServerSocket listener, String name, Queue<String> saw, List<Socket> open) {
    platform("backend-" + name, () -> {
      while (!listener.isClosed()) {
        Socket socket;
        try { socket = listener.accept(); } catch (IOException closed) { return; }
        open.add(socket);
        platform("backend-" + name + "-session", () -> {
          try (socket) {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() == 1) {
              MinecraftFrames.read(in, 4096);
              MinecraftFrames.write(out, packet(0x00, "{\"version\":{\"name\":\"Backend 1.8\",\"protocol\":47},"
                  + "\"players\":{\"max\":20,\"online\":3},\"description\":{\"text\":\"backend motd\"}}"));
              return;
            }
            byte[] loginStart = MinecraftFrames.read(in, 4096);
            String player = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(loginStart, 1, loginStart.length - 1)), 16);
            saw.add(name + ":login:" + player);
            MinecraftFrames.write(out, packet(0x02, "00000000-0000-0000-0000-000000000000", player));
            MinecraftFrames.write(out, joinGame());
            // A live server is never silent for long (vanilla sends Time Update every second), and a
            // silent one would look dead to the proxy.
            platform("backend-" + name + "-ticks", () -> {
              try {
                while (!socket.isClosed()) {
                  synchronized (out) { MinecraftFrames.write(out, timeUpdate()); }
                  Thread.sleep(1000);
                }
              } catch (IOException | InterruptedException ended) { }
            });
            while (true) {
              byte[] frame = MinecraftFrames.read(in, 1 << 16);
              byte[] body = Arrays.copyOfRange(frame, 1, frame.length);
              if (frame[0] == 0x01) {
                String chat = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(body)), 256);
                saw.add(name + ":chat:" + chat);
                if (chat.equals("kickme")) synchronized (out) { MinecraftFrames.write(out, packet(0x40, "{\"text\":\"backend says bye\"}")); }
              }
              if (frame[0] == 0x17) {
                PluginMessage message = PluginMessage.decodeBody(body, 1 << 16);
                String data = new String(message.data(), StandardCharsets.UTF_8);
                saw.add(name + ":pm:" + message.channel() + ":" + data);
                if (data.equals("to-server")) {
                  synchronized (out) {
                    MinecraftFrames.write(out, new PluginMessage("vtest:chan", bytes("from-backend")).encode(0x3F));
                    MinecraftFrames.write(out, new PluginMessage("other:chan", bytes("after")).encode(0x3F));
                  }
                }
              }
            }
          } catch (IOException ended) { }
        });
      }
    });
  }

  /** A scripted 1.8 client. Everything the proxy sends it is kept, for what it did and did not get. */
  private static final class Client implements AutoCloseable {
    private final Socket socket;
    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final List<byte[]> seen = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
    private Client(Socket socket) {
      this.socket = socket;
      platform("client-reader", () -> {
        try {
          while (true) incoming.add(MinecraftFrames.read(socket.getInputStream(), 1 << 20));
        } catch (IOException ended) {
          closed.countDown();
        }
      });
    }
    /** The proxy closed the connection. */
    void awaitClosed(String what) throws InterruptedException {
      if (!closed.await(10, TimeUnit.SECONDS)) throw new AssertionError("the proxy never closed " + what + "; signals " + signals);
    }
    static Client join(int port, String name) throws IOException {
      Socket socket = new Socket("127.0.0.1", port);
      Client client = new Client(socket);
      client.send(new Handshake(47, "localhost", port, 2).encode());
      client.send(packet(0x00, name));
      return client;
    }
    synchronized void send(byte[] frame) throws IOException { MinecraftFrames.write(socket.getOutputStream(), frame); }
    void chat(String line) throws IOException { send(packet(0x01, line)); }
    byte[] await(Predicate<byte[]> wanted, String what) throws InterruptedException {
      for (byte[] frame : seen) if (wanted.test(frame)) return frame;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        byte[] frame = incoming.poll(100, TimeUnit.MILLISECONDS);
        if (frame == null) continue;
        seen.add(frame);
        if (wanted.test(frame)) return frame;
      }
      throw new AssertionError("client never got " + what + "; signals " + signals);
    }
    void awaitText(String expected) throws InterruptedException { await(frame -> text(frame).contains(expected), "\"" + expected + "\""); }
    boolean saw(Predicate<byte[]> test) {
      byte[] frame;
      while ((frame = incoming.poll()) != null) seen.add(frame);
      return seen.stream().anyMatch(test);
    }
    @Override public void close() throws IOException { socket.close(); }
  }

  private static byte[] packet(int id, String... strings) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      for (String value : strings) MinecraftOutput.string(output, value);
    }
    return bytes.toByteArray();
  }
  /** 1.8 Client Settings: language, view distance, chat mode, chat colours, skin parts. */
  private static byte[] clientSettings(String language) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x15);
      MinecraftOutput.string(output, language);
      output.writeByte(8); output.writeByte(0); output.writeBoolean(true); output.writeByte(0x7F);
    }
    return bytes.toByteArray();
  }
  private static byte[] tabRequest(String text) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x14); MinecraftOutput.string(output, text); output.writeBoolean(false);
    }
    return bytes.toByteArray();
  }
  private static byte[] joinGame() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x01); output.writeInt(1); output.writeByte(0); output.writeByte(0);
      output.writeByte(1); output.writeByte(20); MinecraftOutput.string(output, "flat"); output.writeBoolean(false);
    }
    return bytes.toByteArray();
  }
  private static byte[] timeUpdate() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, 0x03); output.writeLong(0); output.writeLong(6000);
    }
    return bytes.toByteArray();
  }
  private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
  private static String text(byte[] frame) { return new String(frame, StandardCharsets.UTF_8); }

  static ConduitConfiguration configuration(List<BackendServer> backends) throws IOException {
    OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2),
        null, null, SecuritySettings.defaults(), null, null,
        new StatusSettings(gg.tame.conduit.api.text.Text.of("conduit motd"), 77, Optional.of(FAVICON)));
    int port;
    try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
    return new ConduitConfiguration(new InetSocketAddress("127.0.0.1", port), 1 << 16, ForwardingMode.NONE, Optional.empty(),
        backends, List.of(backends.get(0).name()), List.of(backends.get(0).name()), AuthenticationSettings.offline(), Optional.empty(), ops);
  }

  /** Socket work in tests stays on platform threads, as Conduit's own does on Windows. */
  private static Thread platform(String name, Runnable body) {
    Thread thread = new Thread(body, name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  // ---------------------------------------------------------------- signals

  /** Points the test plugins' reporting queue at this class's, emptied. */
  static void installSignals() {
    signals.clear();
    System.getProperties().put(SIGNALS, signals);
  }
  static void awaitSignal(String expected) throws InterruptedException { awaitIn(signals, expected); }
  private static void awaitIn(Queue<String> queue, String expected) throws InterruptedException { awaitIn(queue, expected, 1); }
  private static void awaitIn(Queue<String> queue, String expected, int times) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (queue.stream().filter(expected::equals).count() >= times) return;
      Thread.sleep(20);
    }
    throw new AssertionError("never saw " + expected + (times > 1 ? " x" + times : "") + " in " + queue + "; signals " + signals);
  }
  private static void awaitCount(String expected, int times) throws InterruptedException { awaitIn(signals, expected, times); }
  private static int count(String value) { return (int) signals.stream().filter(value::equals).count(); }
  private static boolean before(String first, String second) {
    List<String> order = List.copyOf(signals);
    return order.contains(first) && order.contains(second) && order.indexOf(first) < order.indexOf(second);
  }
  static boolean deletable(Path path) {
    try { return Files.deleteIfExists(path) || !Files.exists(path); } catch (IOException locked) { return false; }
  }
  static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

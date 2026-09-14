package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.login.AuthenticatedPlayerProfile;
import gg.tame.conduit.login.LoginPipeline;
import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PacketTrace;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolProfileAdapter;
import gg.tame.conduit.protocol.ProtocolSession;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.routing.ServerRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerSession implements CommandSource, TrackedPlayer, gg.tame.conduit.api.player.Player, AutoCloseable {
  private final ConduitConfiguration configuration;
  private final PacketTransport client;
  private final ProtocolDefinition protocol;
  private final ProtocolSession clientState;
  private final LoginPipeline loginPipeline;
  private final PlayerInfoForwarder forwarder;
  private final gg.tame.conduit.runtime.ConduitRuntime runtime;
  private final CommandManager commands;
  private final PlayerManager players;
  private final BackendSelector selector;
  private final Handshake handshake;
  private final byte[] originalHandshake;
  private final byte[] originalLoginStart;
  private final InetAddress address;
  private final Object lock = new Object();
  private final AtomicReference<SessionLifecycle> lifecycle = new AtomicReference<>(SessionLifecycle.CONNECTING);
  private final BlockingQueue<Boolean> configurationAck = new ArrayBlockingQueue<>(1);
  private final BlockingQueue<Boolean> knownPacksAck = new ArrayBlockingQueue<>(1);
  private volatile BackendConnection backend;
  private volatile BackendConnection switchingTarget;
  private volatile boolean closed;
  private volatile boolean expectClientLoginAck;
  private volatile boolean commandsDeclared;
  private static final int MAX_DEFERRED_PLAY = 512;
  private final List<byte[]> deferredPlay = new java.util.ArrayList<>();
  private boolean playLoginSent;
  private boolean needSelfPlayerInfo = true;
  private volatile Thread clientReader;
  /**
   * Body of the client's most recent Client Information packet, without the packet id.
   *
   * <p>The client sends this once, during its initial configuration, and never again unless the
   * player changes a video setting. It carries Displayed Skin Parts — the bitmask for cape, jacket,
   * sleeves, pants and hat. A backend that never receives it falls back to defaults and broadcasts
   * them in player-info, which is what stripped the cape and the outer skin layer after a switch
   * even though the textures property was intact the whole time. Cached here and replayed to every
   * new backend, the way Velocity replays its cached client settings.
   */
  private volatile byte[] clientInformation;
  public PlayerSession(ConduitConfiguration configuration, PacketTransport client, ProtocolDefinition protocol, ProtocolSession clientState,
      LoginPipeline loginPipeline, PlayerInfoForwarder forwarder, gg.tame.conduit.runtime.ConduitRuntime runtime,
      Handshake handshake, byte[] originalHandshake, byte[] originalLoginStart, InetAddress address) {
    this.configuration = configuration; this.client = client; this.protocol = protocol; this.clientState = clientState;
    this.loginPipeline = loginPipeline; this.forwarder = forwarder; this.runtime = runtime;
    this.commands = runtime.commandManager(); this.players = runtime.playerManager(); this.selector = runtime.selector();
    this.handshake = handshake; this.originalHandshake = originalHandshake; this.originalLoginStart = originalLoginStart; this.address = address;
  }
  public PlayerProfile profile() { return AuthenticatedPlayerProfile.require(loginPipeline.player()); }
  /** Same object as {@link #profile()}; the session never recreates identity on {@code /server}. */
  public PlayerProfile authenticatedProfile() { return profile(); }
  @Override public java.util.UUID uniqueId() { return profile().uniqueId(); }
  @Override public boolean authenticated() { return profile().authenticated(); }
  @Override public String connectionState() { return clientState.state().name(); }
  @Override public OptionalServer currentServer() {
    BackendConnection current = backend;
    if (current == null) return new OptionalServerView(null);
    return new OptionalServerView(runtime.registered(current.server().name()).orElse(null));
  }
  @Override public java.util.concurrent.CompletableFuture<Boolean> connect(gg.tame.conduit.api.server.RegisteredServer server) {
    return java.util.concurrent.CompletableFuture.supplyAsync(() -> transferTo(server.getName()));
  }
  @Override public void disconnect(String reason) {
    try { writeClient(PlayPackets.systemChat(protocol, reason)); } catch (IOException ignored) { }
    close();
  }
  @Override public void sendPluginMessage(String channel, byte[] data) {
    try {
      ConnectionState state = clientState.state();
      PacketKind kind = state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE;
      if (!protocol.defines(state, PacketDirection.SERVER_TO_CLIENT, kind)) return;
      writeClient(new gg.tame.conduit.protocol.PluginMessage(channel, data).encode(protocol.id(state, PacketDirection.SERVER_TO_CLIENT, kind)));
    } catch (IOException ignored) { }
  }
  private boolean forwardPluginMessage(byte[] packet, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction direction, BackendConnection target) {
    try {
      ConnectionState state = clientState.state();
      PacketKind kind = direction == gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY
          ? (state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE)
          : (state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE);
      PacketDirection dir = direction == gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY
          ? PacketDirection.CLIENT_TO_SERVER : PacketDirection.SERVER_TO_CLIENT;
      if (!protocol.defines(state, dir, kind)) return true;
      if (!protocol.is(state, dir, PlayPackets.peekId(packet), kind)) return true;
      var decoded = gg.tame.conduit.protocol.PluginMessage.decodeBody(PlayPackets.body(packet), configuration.maxFrameBytes());
      var event = new gg.tame.conduit.api.event.messaging.PluginMessageEvent(this, decoded.channel(), decoded.data(), direction);
      runtime.events().fire(event);
      return !event.cancelled();
    } catch (IOException ignored) {
      return true;
    }
  }
  private record OptionalServerView(gg.tame.conduit.api.server.RegisteredServer server) implements OptionalServer {
    @Override public boolean isPresent() { return server != null; }
    @Override public gg.tame.conduit.api.server.RegisteredServer orElse(gg.tame.conduit.api.server.RegisteredServer fallback) {
      return server == null ? fallback : server;
    }
    @Override public String name() { return server == null ? "" : server.getName(); }
  }
  public SessionLifecycle lifecycle() { return lifecycle.get(); }
  public BackendConnection backend() { return backend; }
  public void play() throws IOException {
    BackendConnection initial = connectInitial();
    synchronized (lock) { backend = initial; lifecycle.set(SessionLifecycle.CONNECTED); lock.notifyAll(); }
    players.add(this);
    gg.tame.conduit.metrics.ConduitMetrics.current().playerJoined();
    runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerPostLoginEvent(this));
    Thread backendReader = Thread.startVirtualThread(this::readBackend);
    try { readClient(); }
    finally {
      closed = true;
      players.remove(this);
      gg.tame.conduit.metrics.ConduitMetrics.current().playerLeft();
      runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerDisconnectEvent(this));
      backendReader.interrupt();
      close();
    }
  }
  private BackendConnection connectInitial() throws IOException {
    IOException last = null;
    for (BackendServer server : selector.candidatesFor(protocol.version().number())) {
      try {
        Socket socket = BackendConnection.open(server);
        MinecraftFrames.write(socket.getOutputStream(), originalHandshake);
        MinecraftFrames.write(socket.getOutputStream(), LoginStart.encode(profile()));
        BackendConnection connection = new BackendConnection(server, socket, protocol, forwarder, profile(), address, configuration, false);
        if (forwarder.mode() == ForwardingMode.MODERN) completeBackendLogin(connection, true);
        return connection;
      } catch (IOException exception) {
        last = exception;
        System.err.println("Backend unavailable: " + server.name() + " (" + exception.getMessage() + ")");
      }
    }
    throw last == null ? new IOException("all configured backends refused the connection") : last;
  }
  private void completeBackendLogin(BackendConnection connection, boolean forwardLoginSuccess) throws IOException {
    while (connection.state() == ConnectionState.LOGIN) {
      byte[] packet = connection.readUncompressed();
      PacketTrace.packet("backend-login", connection.state(), PacketDirection.SERVER_TO_CLIENT, protocol, packet);
      byte[] response = connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      if (response != null) { connection.writeUncompressed(response); continue; }
      if (!connection.login().shouldForward()) continue;
      if (!forwardLoginSuccess) throw new IOException("backend login failed");
      writeClient(packet);
      loginPipeline.observe(PacketDirection.SERVER_TO_CLIENT, packet);
    }
    if (protocol.hasConfiguration() && protocol.defines(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED)) {
      connection.writeUncompressed(PlayPackets.loginAcknowledged(protocol));
      if (forwardLoginSuccess) expectClientLoginAck = true;
    }
  }
  private void readClient() {
    clientReader = Thread.currentThread();
    try {
      while (!closed) {
        byte[] packet = client.read(configuration.maxFrameBytes());
        if (handleClientPacket(packet)) continue;
        if (lifecycle.get() == SessionLifecycle.SWITCHING) {
          BackendConnection target = switchingTarget;
          if (target != null && clientState.state() == ConnectionState.CONFIGURATION) target.writeUncompressed(packet);
          continue;
        }
        BackendConnection target = switchingTarget != null ? switchingTarget : backend;
        if (target != null) {
          if (!forwardPluginMessage(packet, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY, target)) {
            continue;
          }
          target.writeUncompressed(packet);
        }
      }
    } catch (IOException ignored) { }
  }
  private boolean handleClientPacket(byte[] packet) throws IOException {
    int id = PlayPackets.packetId(packet);
    rememberClientInformation(packet, id);
    if (expectClientLoginAck
        && protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_ACKNOWLEDGED)
        && packet.length <= 2) {
      expectClientLoginAck = false;
      return true;
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT_COMMAND)) {
      String command = PlayPackets.chatCommand(packet);
      var chat = new gg.tame.conduit.api.event.player.PlayerChatEvent(this, "/" + command);
      runtime.events().fire(chat);
      if (chat.cancelled()) return true;
      var execute = new gg.tame.conduit.api.event.command.CommandExecuteEvent(this, command);
      runtime.events().fire(execute);
      if (execute.cancelled()) return true;
      try { return commands.dispatch(this, command); }
      catch (RuntimeException exception) {
        gg.tame.conduit.log.ConduitLog.error("command failed", exception);
        return true;
      }
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_TAB_COMPLETE_REQUEST)) {
      PlayPackets.TabRequest request = PlayPackets.tabRequest(packet);
      var command = gg.tame.conduit.command.ParsedCommand.parseKeepEmpty(request.text());
      if (request.text().startsWith("/") && commands.get(command.name()).isPresent()) {
        List<String> completions = commands.tabComplete(this, request.text());
        int start = request.text().lastIndexOf(' ') + 1;
        int length = Math.max(0, request.text().length() - start);
        writeClient(PlayPackets.tabComplete(protocol, request.transactionId(), start, length, completions));
        return true;
      }
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)) {
      clientState.beginReconfiguration();
      configurationAck.offer(Boolean.TRUE);
      return true;
    }
    if (clientState.state() == ConnectionState.CONFIGURATION && protocol.knownPacks()
        && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.CONFIGURATION_KNOWN_PACKS)) {
      BackendConnection target = switchingTarget != null ? switchingTarget : backend;
      if (target != null) target.writeUncompressed(packet);
      knownPacksAck.offer(Boolean.TRUE);
      return true;
    }
    if (clientState.state() == ConnectionState.CONFIGURATION && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.CONFIGURATION_FINISH)) {
      BackendConnection target = switchingTarget != null ? switchingTarget : backend;
      if (target != null) target.writeUncompressed(packet);
      clientState.beginPlay();
      configurationAck.offer(Boolean.TRUE);
      flushDeferredPlay();
      return true;
    }
    return false;
  }
  /** Caches Client Information from either state; the packet is still forwarded normally. */
  private void rememberClientInformation(byte[] packet, int id) throws IOException {
    ConnectionState state = clientState.state();
    PacketKind kind = state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_CLIENT_INFORMATION
        : state == ConnectionState.PLAY ? PacketKind.PLAY_CLIENT_INFORMATION : null;
    if (kind == null || !protocol.defines(state, PacketDirection.CLIENT_TO_SERVER, kind)) return;
    if (!protocol.is(state, PacketDirection.CLIENT_TO_SERVER, id, kind)) return;
    clientInformation = PlayPackets.body(packet);
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
      System.out.println("TRACE cached client information from " + state + " (" + clientInformation.length + " body bytes)");
    }
  }
  /** Replays the cached Client Information to a backend in the given state. */
  private void replayClientInformation(BackendConnection target, ConnectionState state) {
    byte[] body = clientInformation;
    if (body == null || target == null) return;
    PacketKind kind = state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_CLIENT_INFORMATION : PacketKind.PLAY_CLIENT_INFORMATION;
    if (!protocol.defines(state, PacketDirection.CLIENT_TO_SERVER, kind)) return;
    try {
      target.writeUncompressed(PlayPackets.withId(protocol.id(state, PacketDirection.CLIENT_TO_SERVER, kind), body));
      if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
        System.out.println("TRACE replayed client information to " + target.server().name() + " in " + state);
      }
    } catch (IOException exception) {
      System.err.println("Could not replay client information to " + target.server().name() + ": " + exception.getMessage());
    }
  }
  private void readBackend() {
    while (!closed) {
      BackendConnection current;
      synchronized (lock) {
        while (!closed && (backend == null || lifecycle.get() == SessionLifecycle.SWITCHING)) {
          try { lock.wait(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
        current = backend;
        if (closed || current == null) continue;
      }
      try {
        byte[] packet = current.readUncompressed();
        synchronized (lock) {
          if (lifecycle.get() != SessionLifecycle.CONNECTED || current != backend) continue;
        }
        ConnectionState backendState = current.state();
        if (backendState == ConnectionState.CONFIGURATION) current.login().onBackendPacket(packet, configuration.maxFrameBytes());
        if (!forwardPluginMessage(packet, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.BACKEND_TO_PROXY, null)) continue;
        byte[] outbound = current.rewriteBrand(brandState(backendState), packet);
        if (clientState.state() == ConnectionState.CONFIGURATION && isFinishConfiguration(packet) && !current.brandSeen()) {
          writeClient(BrandRewriter.synthesize(protocol, ConnectionState.CONFIGURATION, ""), true);
          current.markBrandSeen();
        }
        outbound = maybeMergeCommands(outbound);
        if (deferPlayUntilReady(packet, outbound, current.state())) {
          flushDeferredPlay();
          continue;
        }
        writeClient(outbound, true);
        if (clientState.state() == ConnectionState.PLAY && isPlayLogin(packet)) {
          emitSelfPlayerInfoIfNeeded();
        }
        if (isPlayDisconnect(packet)) { close(); return; }
      } catch (IOException exception) {
        if (closed || lifecycle.get() != SessionLifecycle.CONNECTED) return;
        handleBackendLoss(current);
      }
    }
  }
  private ConnectionState brandState(ConnectionState backendState) {
    if (backendState == ConnectionState.PLAY) return ConnectionState.PLAY;
    if (protocol.hasConfiguration()) return ConnectionState.CONFIGURATION;
    return ConnectionState.PLAY;
  }
  private byte[] maybeMergeCommands(byte[] packet) throws IOException {
    if (clientState.state() != ConnectionState.PLAY) return packet;
    int id = PlayPackets.packetId(packet);
    if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_DECLARE_COMMANDS)) return packet;
    try {
      byte[] merged = CommandGraphs.mergeProxyCommands(protocol, packet, selector.registry().names());
      commandsDeclared = true;
      return merged;
    } catch (IOException exception) {
      System.err.println("Command tree merge skipped: " + exception);
      gg.tame.conduit.protocol.ProfileTrace.dumpCommandTree(protocol, packet, exception);
      return packet;
    }
  }
  private boolean isFinishConfiguration(byte[] packet) throws IOException {
    return protocol.hasConfiguration() && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.CONFIGURATION_FINISH);
  }
  private boolean isPlayDisconnect(byte[] packet) throws IOException {
    return clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_DISCONNECT);
  }
  private boolean isPlayLogin(byte[] packet) throws IOException {
    return protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_LOGIN);
  }
  /** Hold backend Play until Join Game has been written; change_difficulty/entities crash if levelData is still null. */
  private boolean deferPlayUntilReady(byte[] original, byte[] outbound, ConnectionState backendState) throws IOException {
    if (!protocol.hasConfiguration()) return false;
    if (isFinishConfiguration(original)) return false;
    synchronized (lock) {
      if (backendState != ConnectionState.PLAY) return false;
      if (playLoginSent && clientState.state() == ConnectionState.PLAY) return false;
      if (deferredPlay.size() >= MAX_DEFERRED_PLAY) throw new IOException("deferred play queue exceeded");
      deferredPlay.add(outbound);
      return true;
    }
  }
  private void flushDeferredPlay() throws IOException {
    synchronized (lock) {
      if (clientState.state() != ConnectionState.PLAY) return;
      List<byte[]> logins = new java.util.ArrayList<>();
      List<byte[]> rest = new java.util.ArrayList<>();
      for (byte[] packet : deferredPlay) {
        if (isPlayLogin(packet)) logins.add(packet);
        else rest.add(packet);
      }
      if (!playLoginSent && logins.isEmpty()) return;
      deferredPlay.clear();
      for (byte[] packet : logins) writeClient(packet);
      playLoginSent = true;
      emitSelfPlayerInfoIfNeeded();
      for (byte[] packet : rest) writeClient(maybeMergeCommands(packet));
    }
  }
  private void handleBackendLoss(BackendConnection lost) {
    synchronized (lock) {
      if (backend != lost || lifecycle.get() != SessionLifecycle.CONNECTED) return;
      lifecycle.set(SessionLifecycle.SWITCHING);
      lock.notifyAll();
    }
    lost.close();
    Set<String> failed = new HashSet<>();
    failed.add(ServerRegistry.normalize(lost.server().name()));
    sendMessage(gg.tame.conduit.api.text.Text.of("✕ ").color(gg.tame.conduit.api.text.TextColor.RED)
        .append(gg.tame.conduit.api.text.Text.of(gg.tame.conduit.command.ConduitUi.titleCase(lost.server().name()) + " is unavailable.").color(gg.tame.conduit.api.text.TextColor.WHITE)));
    for (BackendServer server : selector.fallback(lost.server().name(), failed)) {
      try {
        sendMessage(gg.tame.conduit.api.text.Text.of("Returning you to ")
            .color(gg.tame.conduit.api.text.TextColor.GRAY)
            .append(gg.tame.conduit.api.text.Text.of(gg.tame.conduit.command.ConduitUi.titleCase(server.name())).color(gg.tame.conduit.api.text.TextColor.AQUA).bold())
            .append(gg.tame.conduit.api.text.Text.of("...").color(gg.tame.conduit.api.text.TextColor.GRAY)));
        switchTo(server, true);
        gg.tame.conduit.command.ConduitUi.connected(this, gg.tame.conduit.command.ConduitUi.titleCase(server.name()));
        return;
      }
      catch (Exception exception) { failed.add(ServerRegistry.normalize(server.name())); }
    }
    close();
  }
  public void requestSwitch(String name) { transferTo(name); }
  @Override public boolean transferTo(String name) {
    BackendServer server = selector.registry().get(name).orElse(null);
    if (server == null) return false;
    String display = gg.tame.conduit.command.ConduitUi.titleCase(server.name());
    if (Thread.currentThread() == clientReader) {
      Thread.startVirtualThread(() -> {
        if (runSwitch(server)) gg.tame.conduit.command.ConduitUi.connected(this, display);
        else gg.tame.conduit.command.ConduitUi.failure(this, "Unable to connect to " + display + ".", "The server is currently unavailable.");
      });
      return true;
    }
    boolean ok = runSwitch(server);
    if (ok) gg.tame.conduit.command.ConduitUi.connected(this, display);
    else gg.tame.conduit.command.ConduitUi.failure(this, "Unable to connect to " + display + ".", "The server is currently unavailable.");
    return ok;
  }
  private boolean runSwitch(BackendServer server) {
    try {
      switchTo(server, false);
      return true;
    } catch (Exception exception) {
      return false;
    }
  }
  private void switchTo(BackendServer server, boolean fallback) throws Exception {
    var targetView = runtime.registered(server.name()).orElse(null);
    var sourceView = backend == null ? java.util.Optional.<gg.tame.conduit.api.server.RegisteredServer>empty() : runtime.registered(backend.server().name());
    if (targetView != null) {
      var connect = new gg.tame.conduit.api.event.player.PlayerServerConnectEvent(this, sourceView, targetView);
      runtime.events().fire(connect);
      if (connect.cancelled()) throw new IOException("connection cancelled");
    }
    long started = System.nanoTime();
    ensureCompatible(server);
    synchronized (lock) {
      if (lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
      if (!fallback && lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
      lifecycle.set(SessionLifecycle.SWITCHING);
      lock.notifyAll();
    }
    BackendConnection previous = backend;
    Socket socket = null;
    BackendConnection next = null;
    boolean clientEnteredConfiguration = false;
    try {
      socket = BackendConnection.open(server);
      BackendConnection.handshake(socket, handshake, server, profile());
      next = new BackendConnection(server, socket, protocol, forwarder, profile(), address, configuration, true);
      completeBackendLogin(next, false);
      // The client will not resend this; without it the new backend broadcasts default skin parts.
      replayClientInformation(next, ConnectionState.CONFIGURATION);
      gg.tame.conduit.protocol.ProfileTrace.beginSequence("switch to " + server.name(), 60);
      switchingTarget = next;
      if (protocol.hasConfiguration()) {
        configurationAck.clear();
        synchronized (lock) { deferredPlay.clear(); playLoginSent = false; needSelfPlayerInfo = true; }
        writeClient(PlayPackets.startConfiguration(protocol));
        clientEnteredConfiguration = true;
        if (configurationAck.poll(10, TimeUnit.SECONDS) == null) throw new IOException("client did not acknowledge reconfiguration");
        configurationAck.clear();
        knownPacksAck.clear();
        boolean registrySeen = !protocol.knownPacks();
        boolean knownPacksDone = !protocol.knownPacks();
        byte[] lastConfig = null;
        while (next.state() != ConnectionState.PLAY) {
          byte[] packet = next.readUncompressed();
          int id = PlayPackets.packetId(packet);
          next.login().onBackendPacket(packet, configuration.maxFrameBytes());
          byte[] outbound = next.rewriteBrand(ConnectionState.CONFIGURATION, packet);
          if (protocol.knownPacks() && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_KNOWN_PACKS)) {
            writeClient(outbound);
            if (knownPacksAck.poll(10, TimeUnit.SECONDS) == null) {
              throw new IOException("client did not reply to known packs");
            }
            knownPacksDone = true;
            continue;
          }
          if (protocol.knownPacks() && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_REGISTRY)) {
            registrySeen = true;
          }
          if (isFinishConfiguration(packet)) {
            if (packet.length > 2) continue;
            if (protocol.knownPacks() && !knownPacksDone) {
              throw new IOException("backend finished configuration before known packs");
            }
            if (!registrySeen) throw new IOException("backend finished configuration without 26.2 registry data");
            if (!next.brandSeen()) {
              writeClient(BrandRewriter.synthesize(protocol, ConnectionState.CONFIGURATION, ""));
              next.markBrandSeen();
            }
            writeClient(outbound);
            break;
          }
          if (lastConfig != null && java.util.Arrays.equals(lastConfig, outbound)) continue;
          lastConfig = outbound;
          writeClient(outbound);
        }
        if (protocol.knownPacks()) {
          if (!waitForClient(configurationAck, next, 10)) throw new IOException("client did not finish configuration");
        } else if (configurationAck.poll(10, TimeUnit.SECONDS) == null) {
          throw new IOException("client did not finish configuration");
        }
      }
      commandsDeclared = false;
      synchronized (lock) {
        backend = next;
        switchingTarget = null;
        next = null;
        lifecycle.set(SessionLifecycle.CONNECTED);
        lock.notifyAll();
      }
      replayClientInformation(backend, ConnectionState.PLAY);
      if (previous != null) previous.close();
      gg.tame.conduit.metrics.ConduitMetrics.current().serverSwitch(System.nanoTime() - started);
      if (targetView != null) {
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerConnectedEvent(this, sourceView, targetView));
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchEvent(this, sourceView, targetView));
      }
    } catch (Exception exception) {
      switchingTarget = null;
      if (next != null) next.close();
      else if (socket != null) try { socket.close(); } catch (IOException ignored) { }
      gg.tame.conduit.log.ConduitLog.warn("Switch to " + server.name() + " failed: " + exception.getMessage());
      if (targetView != null) {
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent(this, sourceView, targetView,
            exception.getMessage() == null ? "switch failed" : exception.getMessage()));
      }
      if (clientEnteredConfiguration) {
        try { writeClient(PlayPackets.configurationDisconnect(protocol, "Could not connect to " + server.name() + ".")); }
        catch (IOException ignored) { }
        close();
        throw exception;
      }
      synchronized (lock) {
        if (lifecycle.get() == SessionLifecycle.SWITCHING) {
          lifecycle.set(previous != null ? SessionLifecycle.CONNECTED : SessionLifecycle.CLOSED);
          lock.notifyAll();
        }
      }
      throw exception;
    }
  }
  private boolean waitForClient(java.util.concurrent.BlockingQueue<Boolean> queue, BackendConnection backend, int seconds) throws IOException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    backend.setReadTimeoutMillis(200);
    try {
      while (System.nanoTime() < deadline) {
        if (queue.poll() != null) return true;
        try {
          holdOrForwardDuringClientWait(backend.readUncompressed(), backend);
        } catch (java.net.SocketTimeoutException ignored) {
        }
      }
      return queue.poll() != null;
    } finally {
      backend.setReadTimeoutMillis(0);
    }
  }
  private void holdOrForwardDuringClientWait(byte[] packet, BackendConnection backend) throws IOException {
    int id = PlayPackets.packetId(packet);
    if (backend.state() == ConnectionState.CONFIGURATION
        && protocol.defines(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KEEP_ALIVE)
        && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_KEEP_ALIVE)) {
      writeClient(packet);
      return;
    }
    ConnectionState backendState = backend.state();
    byte[] outbound = backend.rewriteBrand(brandState(backendState), packet);
    if (deferPlayUntilReady(packet, outbound, backendState)) {
      flushDeferredPlay();
      return;
    }
    switch (handoff(backendState, clientState.state())) {
      case FORWARD_CONFIGURATION -> writeClient(outbound);
      case FORWARD_PLAY -> writeClient(maybeMergeCommands(outbound));
      case DROP -> System.err.println("Dropped backend " + backendState + " packet while client was in " + clientState.state());
    }
  }
  public enum Handoff { FORWARD_CONFIGURATION, FORWARD_PLAY, DROP }
  /**
   * Decides what to do with a backend packet read while the switch is waiting on the client.
   *
   * <p>The new backend enters Play and starts sending Join Game, player-info updates and entity
   * data before {@code waitForClient} returns. Those Play packets used to fall off the end of this
   * method and be silently discarded. Tracing a real switch showed the window does not actually
   * open in practice, so this was not the cause of the profile-rendering bug it was first written
   * for; it is still a real hole, and dropping backend Play packets is never correct. The DROP case
   * now logs rather than losing a packet in silence. Only 776 reaches this path, because only 776
   * reads the backend while waiting on the client (known packs).
   */
  public static Handoff handoff(ConnectionState backendState, ConnectionState clientState) {
    if (backendState == ConnectionState.CONFIGURATION) return Handoff.FORWARD_CONFIGURATION;
    if (backendState == ConnectionState.PLAY && clientState == ConnectionState.PLAY) return Handoff.FORWARD_PLAY;
    return Handoff.DROP;
  }
  private void ensureCompatible(BackendServer server) throws IOException {
    if (!ProtocolDefinition.hasCodec(protocol.version().number())) {
      throw new IOException("Unsupported Minecraft version.");
    }
    var advertisement = selector.advertisement(server.name());
    if (advertisement.isEmpty()) return;
    int backendProtocol = advertisement.get().protocol();
    if (backendProtocol != protocol.version().number()) {
      System.out.println("Client protocol " + protocol.version().number() + " connecting to " + server.name()
          + " advertised as " + backendProtocol + " (backend must accept the client protocol, e.g. ViaVersion).");
    }
  }
  private void emitSelfPlayerInfoIfNeeded() throws IOException {
    if (!needSelfPlayerInfo) return;
    if (clientState.state() != ConnectionState.PLAY) return;
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE)) {
      needSelfPlayerInfo = false;
      return;
    }
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) System.out.println("TRACE conduit synthesizing self ADD_PLAYER");
    writeClient(gg.tame.conduit.protocol.PlayerInfoUpdate.selfAdd(protocol, profile()));
    needSelfPlayerInfo = false;
  }
  private void writeClient(byte[] packet) throws IOException { writeClient(packet, true); }
  private void writeClient(byte[] packet, boolean flush) throws IOException {
    byte[] outbound = ProtocolProfileAdapter.backendToClient(protocol, clientState.state(), packet, profile());
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
      String where = lifecycle.get() == SessionLifecycle.SWITCHING ? "switch" : "steady";
      gg.tame.conduit.protocol.ProfileTrace.clientbound(where, protocol, clientState.state(), outbound, profile());
    }
    if (flush) client.write(outbound);
    else {
      client.writeUnflushed(outbound);
    }
  }
  @Override public String username() { return profile().username(); }
  @Override public boolean hasPermission(String permission) {
    return runtime.permissions().hasPermission(this, permission);
  }
  @Override public void sendMessage(String message) {
    sendMessage(gg.tame.conduit.api.text.Text.of(message == null ? "" : message));
  }
  @Override public void sendMessage(gg.tame.conduit.api.text.Text text) {
    try {
      if (clientState.state() == ConnectionState.PLAY) writeClient(PlayPackets.systemChat(protocol, text));
    } catch (IOException ignored) { }
  }
  @Override public String currentBackend() {
    BackendConnection current = backend;
    return current == null ? "" : current.server().name();
  }
  @Override public void close() {
    closed = true;
    lifecycle.set(SessionLifecycle.CLOSED);
    synchronized (lock) { lock.notifyAll(); }
    if (players != null) players.remove(this);
    BackendConnection current = backend;
    if (current != null) current.close();
    BackendConnection switching = switchingTarget;
    if (switching != null) switching.close();
    client.close();
  }
}

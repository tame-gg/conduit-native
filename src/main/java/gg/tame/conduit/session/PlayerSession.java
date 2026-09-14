package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
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

public final class PlayerSession implements CommandSource, TrackedPlayer, AutoCloseable {
  private final ConduitConfiguration configuration;
  private final PacketTransport client;
  private final ProtocolDefinition protocol;
  private final ProtocolSession clientState;
  private final LoginPipeline loginPipeline;
  private final PlayerInfoForwarder forwarder;
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
  private final List<byte[]> deferredPlay = new java.util.ArrayList<>();
  private boolean playLoginSent;
  private volatile Thread clientReader;
  public PlayerSession(ConduitConfiguration configuration, PacketTransport client, ProtocolDefinition protocol, ProtocolSession clientState,
      LoginPipeline loginPipeline, PlayerInfoForwarder forwarder, CommandManager commands, PlayerManager players, BackendSelector selector,
      Handshake handshake, byte[] originalHandshake, byte[] originalLoginStart, InetAddress address) {
    this.configuration = configuration; this.client = client; this.protocol = protocol; this.clientState = clientState;
    this.loginPipeline = loginPipeline; this.forwarder = forwarder; this.commands = commands; this.players = players; this.selector = selector;
    this.handshake = handshake; this.originalHandshake = originalHandshake; this.originalLoginStart = originalLoginStart; this.address = address;
  }
  public PlayerProfile profile() { return loginPipeline.player(); }
  @Override public java.util.UUID uniqueId() { return profile().uniqueId(); }
  public SessionLifecycle lifecycle() { return lifecycle.get(); }
  public BackendConnection backend() { return backend; }
  public void play() throws IOException {
    BackendConnection initial = connectInitial();
    synchronized (lock) { backend = initial; lifecycle.set(SessionLifecycle.CONNECTED); }
    players.add(this);
    Thread backendReader = Thread.startVirtualThread(this::readBackend);
    try { readClient(); }
    finally { closed = true; players.remove(this); backendReader.interrupt(); close(); }
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
        if (target != null) target.writeUncompressed(packet);
      }
    } catch (IOException ignored) { }
  }
  private boolean handleClientPacket(byte[] packet) throws IOException {
    int id = PlayPackets.packetId(packet);
    if (expectClientLoginAck
        && protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_ACKNOWLEDGED)
        && packet.length <= 2) {
      expectClientLoginAck = false;
      return true;
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT_COMMAND)) {
      return commands.dispatch(this, PlayPackets.chatCommand(packet));
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
  private void readBackend() {
    while (!closed) {
      BackendConnection current = backend;
      if (current == null || lifecycle.get() == SessionLifecycle.SWITCHING) {
        try { Thread.sleep(15); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        continue;
      }
      try {
        byte[] packet = current.readUncompressed();
        synchronized (lock) {
          if (lifecycle.get() != SessionLifecycle.CONNECTED || current != backend) continue;
        }
        ConnectionState backendState = current.state();
        if (backendState == ConnectionState.CONFIGURATION) current.login().onBackendPacket(packet, configuration.maxFrameBytes());
        byte[] outbound = current.rewriteBrand(brandState(backendState), packet);
        if (clientState.state() == ConnectionState.CONFIGURATION && isFinishConfiguration(packet) && !current.brandSeen()) {
          writeClient(BrandRewriter.synthesize(protocol, ConnectionState.CONFIGURATION, ""));
          current.markBrandSeen();
        }
        outbound = maybeMergeCommands(outbound);
        if (deferPlayUntilReady(packet, outbound, current.state())) {
          flushDeferredPlay();
          continue;
        }
        writeClient(outbound);
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
      System.err.println("Command tree merge skipped: " + exception.getMessage());
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
      for (byte[] packet : rest) writeClient(maybeMergeCommands(packet));
    }
  }
  private void handleBackendLoss(BackendConnection lost) {
    synchronized (lock) {
      if (backend != lost || lifecycle.get() != SessionLifecycle.CONNECTED) return;
      lifecycle.set(SessionLifecycle.SWITCHING);
    }
    lost.close();
    Set<String> failed = new HashSet<>();
    failed.add(ServerRegistry.normalize(lost.server().name()));
    for (BackendServer server : selector.fallback(lost.server().name(), failed)) {
      try { switchTo(server, true); return; }
      catch (Exception exception) { failed.add(ServerRegistry.normalize(server.name())); }
    }
    close();
  }
  public void requestSwitch(String name) { transferTo(name); }
  @Override public boolean transferTo(String name) {
    BackendServer server = selector.registry().get(name).orElse(null);
    if (server == null) return false;
    if (Thread.currentThread() == clientReader) {
      Thread.startVirtualThread(() -> {
        if (!runSwitch(server)) sendMessage("Unable to connect to " + server.name() + ".");
      });
      return true;
    }
    return runSwitch(server);
  }
  private boolean runSwitch(BackendServer server) {
    try {
      switchTo(server, false);
      return true;
    } catch (Exception exception) {
      String message = exception.getMessage();
      if (message != null && clientState.state() == ConnectionState.PLAY) sendMessage(message);
      return false;
    }
  }
  private void switchTo(BackendServer server, boolean fallback) throws Exception {
    ensureCompatible(server);
    synchronized (lock) {
      if (lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
      if (!fallback && lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
      lifecycle.set(SessionLifecycle.SWITCHING);
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
      switchingTarget = next;
      if (protocol.hasConfiguration()) {
        configurationAck.clear();
        synchronized (lock) { deferredPlay.clear(); playLoginSent = false; }
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
      }
      if (previous != null) previous.close();
    } catch (Exception exception) {
      switchingTarget = null;
      if (next != null) next.close();
      else if (socket != null) try { socket.close(); } catch (IOException ignored) { }
      System.err.println("Switch to " + server.name() + " failed: " + exception.getMessage());
      if (clientEnteredConfiguration) {
        try { writeClient(PlayPackets.configurationDisconnect(protocol, "Could not connect to " + server.name() + ".")); }
        catch (IOException ignored) { }
        close();
        throw exception;
      }
      synchronized (lock) {
        if (lifecycle.get() == SessionLifecycle.SWITCHING) lifecycle.set(previous != null ? SessionLifecycle.CONNECTED : SessionLifecycle.CLOSED);
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
    if (backendState == ConnectionState.CONFIGURATION) writeClient(outbound);
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
  private void writeClient(byte[] packet) throws IOException {
    synchronized (lock) {
      client.write(ProtocolProfileAdapter.backendToClient(protocol, clientState.state(), packet, profile()));
    }
  }
  @Override public String username() { return profile().username(); }
  @Override public boolean hasPermission(String permission) { return true; }
  @Override public void sendMessage(String message) {
    try { if (clientState.state() == ConnectionState.PLAY) writeClient(PlayPackets.systemChat(protocol, message)); }
    catch (IOException ignored) { }
  }
  @Override public String currentBackend() {
    BackendConnection current = backend;
    return current == null ? "" : current.server().name();
  }
  @Override public void close() {
    closed = true;
    lifecycle.set(SessionLifecycle.CLOSED);
    if (players != null) players.remove(this);
    BackendConnection current = backend;
    if (current != null) current.close();
    BackendConnection switching = switchingTarget;
    if (switching != null) switching.close();
    client.close();
  }
}

package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.login.LoginPipeline;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.network.PacketTransport;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
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

public final class PlayerSession implements CommandSource, AutoCloseable {
  private final ConduitConfiguration configuration;
  private final PacketTransport client;
  private final ProtocolDefinition protocol;
  private final ProtocolSession protocolSession;
  private final LoginPipeline loginPipeline;
  private final PlayerInfoForwarder forwarder;
  private final CommandManager commands;
  private final BackendSelector selector;
  private final Handshake handshake;
  private final byte[] originalHandshake;
  private final byte[] originalLoginStart;
  private final InetAddress address;
  private final Object lock = new Object();
  private final AtomicReference<SessionLifecycle> lifecycle = new AtomicReference<>(SessionLifecycle.CONNECTING);
  private final BlockingQueue<Boolean> configurationAck = new ArrayBlockingQueue<>(1);
  private volatile BackendConnection backend;
  private volatile BackendConnection switchingTarget;
  private volatile boolean closed;
  public PlayerSession(ConduitConfiguration configuration, PacketTransport client, ProtocolDefinition protocol, ProtocolSession protocolSession,
      LoginPipeline loginPipeline, PlayerInfoForwarder forwarder, CommandManager commands, BackendSelector selector,
      Handshake handshake, byte[] originalHandshake, byte[] originalLoginStart, InetAddress address) {
    this.configuration = configuration; this.client = client; this.protocol = protocol; this.protocolSession = protocolSession;
    this.loginPipeline = loginPipeline; this.forwarder = forwarder; this.commands = commands; this.selector = selector;
    this.handshake = handshake; this.originalHandshake = originalHandshake; this.originalLoginStart = originalLoginStart; this.address = address;
  }
  public PlayerProfile profile() { return loginPipeline.player(); }
  public SessionLifecycle lifecycle() { return lifecycle.get(); }
  public BackendConnection backend() { return backend; }
  public void authenticating() { if (!lifecycle.compareAndSet(SessionLifecycle.CONNECTING, SessionLifecycle.AUTHENTICATING)) throw new IllegalStateException("invalid authentication transition"); }
  public void finishAuthentication() { lifecycle.compareAndSet(SessionLifecycle.AUTHENTICATING, SessionLifecycle.CONNECTING); }
  public void play() throws IOException {
    BackendConnection initial = connectInitial();
    synchronized (lock) { backend = initial; lifecycle.set(SessionLifecycle.CONNECTED); }
    Thread backendReader = Thread.startVirtualThread(this::readBackend);
    try { readClient(); }
    finally { closed = true; backendReader.interrupt(); close(); }
  }
  private BackendConnection connectInitial() throws IOException {
    IOException last = null;
    for (BackendServer server : selector.candidates()) {
      try {
        Socket socket = BackendConnection.open(server);
        MinecraftFrames.write(socket.getOutputStream(), originalHandshake);
        MinecraftFrames.write(socket.getOutputStream(), originalLoginStart);
        BackendConnection connection = new BackendConnection(server, socket, protocol, forwarder, profile(), address, configuration, false);
        if (forwarder.mode() == ForwardingMode.MODERN) completeBackendLogin(connection, true);
        return connection;
      } catch (IOException exception) {
        last = exception;
        System.err.println("Backend unavailable: " + server.name());
      }
    }
    throw last == null ? new IOException("all configured backends refused the connection") : last;
  }
  private void completeBackendLogin(BackendConnection connection, boolean forwardLoginSuccess) throws IOException {
    while (connection.state() == ConnectionState.LOGIN) {
      byte[] packet = connection.readUncompressed();
      byte[] response = connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      if (response != null) { connection.writeUncompressed(response); continue; }
      if (!connection.login().shouldForward()) continue;
      if (!forwardLoginSuccess) throw new IOException("backend login failed");
      client.write(packet);
      loginPipeline.observe(PacketDirection.SERVER_TO_CLIENT, packet);
    }
  }
  private void readClient() {
    try {
      while (!closed) {
        byte[] packet = client.read(configuration.maxFrameBytes());
        if (handleClientPacket(packet)) continue;
        BackendConnection target = switchingTarget != null ? switchingTarget : backend;
        if (target != null) target.writeUncompressed(packet);
      }
    } catch (IOException ignored) { }
  }
  private boolean handleClientPacket(byte[] packet) throws IOException {
    int id = PlayPackets.packetId(packet);
    if (protocolSession.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT_COMMAND)) {
      return commands.dispatch(this, PlayPackets.chatCommand(packet));
    }
    if (protocolSession.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_TAB_COMPLETE_REQUEST)) {
      PlayPackets.TabRequest request = PlayPackets.tabRequest(packet);
      var command = gg.tame.conduit.command.ParsedCommand.parseKeepEmpty(request.text());
      if (request.text().startsWith("/") && commands.get(command.name()).isPresent()) {
        List<String> completions = commands.tabComplete(this, request.text());
        int start = request.text().lastIndexOf(' ') + 1;
        int length = Math.max(0, request.text().length() - start);
        client.write(PlayPackets.tabComplete(protocol, request.transactionId(), start, length, completions));
        return true;
      }
    }
    if (protocolSession.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)) {
      protocolSession.beginReconfiguration();
      configurationAck.offer(Boolean.TRUE);
      return true;
    }
    if (protocolSession.state() == ConnectionState.CONFIGURATION && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.CONFIGURATION_FINISH)) {
      if (switchingTarget != null) switchingTarget.writeUncompressed(packet);
      else if (backend != null) backend.writeUncompressed(packet);
      protocolSession.beginPlay();
      configurationAck.offer(Boolean.TRUE);
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
        if (lifecycle.get() != SessionLifecycle.CONNECTED || current != backend) continue;
        ConnectionState state = protocolSession.state();
        byte[] outbound = current.rewriteBrand(state == ConnectionState.PLAY ? ConnectionState.PLAY : ConnectionState.CONFIGURATION, packet);
        if (isFinishConfiguration(packet) && !current.brandSeen()) {
          client.write(BrandRewriter.synthesize(protocol, ConnectionState.CONFIGURATION, ""));
          current.markBrandSeen();
        }
        client.write(outbound);
        if (state != ConnectionState.PLAY) loginPipeline.observe(PacketDirection.SERVER_TO_CLIENT, packet);
        if (isPlayDisconnect(packet)) { close(); return; }
      } catch (IOException exception) {
        if (closed || lifecycle.get() != SessionLifecycle.CONNECTED) return;
        handleBackendLoss(current);
      }
    }
  }
  private boolean isFinishConfiguration(byte[] packet) throws IOException {
    return PlayPackets.packetId(packet) == protocol.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
  }
  private boolean isPlayDisconnect(byte[] packet) throws IOException {
    return protocolSession.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_DISCONNECT);
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
  public void requestSwitch(String name) {
    BackendServer server = selector.registry().get(name).orElseThrow();
    Thread.startVirtualThread(() -> {
      try { switchTo(server, false); }
      catch (Exception exception) { sendMessage("Unable to connect to " + server.name() + "."); }
    });
  }
  private void switchTo(BackendServer server, boolean fallback) throws Exception {
    synchronized (lock) {
      if (lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
      if (!fallback && lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
      lifecycle.set(SessionLifecycle.SWITCHING);
    }
    BackendConnection previous = backend;
    Socket socket = null;
    BackendConnection next = null;
    try {
      socket = BackendConnection.open(server);
      BackendConnection.handshake(socket, handshake, server, profile());
      next = new BackendConnection(server, socket, protocol, forwarder, profile(), address, configuration, true);
      completeBackendLogin(next, false);
      switchingTarget = next;
      configurationAck.clear();
      client.write(PlayPackets.startConfiguration(protocol));
      if (configurationAck.poll(10, TimeUnit.SECONDS) == null) throw new IOException("client did not acknowledge reconfiguration");
      configurationAck.clear();
      while (next.state() != ConnectionState.PLAY) {
        byte[] packet = next.readUncompressed();
        next.login().onBackendPacket(packet, configuration.maxFrameBytes());
        byte[] outbound = next.rewriteBrand(ConnectionState.CONFIGURATION, packet);
        if (isFinishConfiguration(packet) && !next.brandSeen()) {
          client.write(BrandRewriter.synthesize(protocol, ConnectionState.CONFIGURATION, ""));
          next.markBrandSeen();
        }
        client.write(outbound);
      }
      if (configurationAck.poll(10, TimeUnit.SECONDS) == null) throw new IOException("client did not finish configuration");
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
      synchronized (lock) {
        if (lifecycle.get() == SessionLifecycle.SWITCHING) lifecycle.set(previous != null ? SessionLifecycle.CONNECTED : SessionLifecycle.CLOSED);
      }
      throw exception;
    }
  }
  @Override public String username() { return profile().username(); }
  @Override public boolean hasPermission(String permission) { return true; }
  @Override public void sendMessage(String message) {
    try { if (protocolSession.state() == ConnectionState.PLAY) client.write(PlayPackets.systemChat(protocol, message)); }
    catch (IOException ignored) { }
  }
  @Override public String currentBackend() {
    BackendConnection current = backend;
    return current == null ? "" : current.server().name();
  }
  @Override public void close() {
    closed = true;
    lifecycle.set(SessionLifecycle.CLOSED);
    BackendConnection current = backend;
    if (current != null) current.close();
    BackendConnection switching = switchingTarget;
    if (switching != null) switching.close();
    client.close();
  }
}

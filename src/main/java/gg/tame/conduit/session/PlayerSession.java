package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.command.CommandGraphs;
import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.Messages;
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
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.IdentityTranslator;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.protocol.translate.TranslationException;
import gg.tame.conduit.routing.BackendSelector;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.metrics.ConduitMetrics;
import gg.tame.conduit.modded.HandshakeClassifier;
import gg.tame.conduit.modded.KnownPacksValidator;
import gg.tame.conduit.modded.ModLoaderFamily;
import gg.tame.conduit.modded.PluginPayloadValidator;
import gg.tame.conduit.modded.SwitchPacketQueue;
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
  private final int clientProtocol;
  private volatile int backendProtocol;
  private volatile ProtocolDefinition backendDefinition;
  private volatile ProtocolTranslator translator = IdentityTranslator.INSTANCE;
  private volatile TranslationSupport translationSupport = TranslationSupport.DIRECT;
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
  private final HandshakeClassifier modClassifier;
  private final SwitchPacketQueue switchQueue;
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
  /** Set once Conduit has synthesised finish_configuration for a non-configuration backend. */
  private volatile boolean configurationFinishSynthesized;
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
    this.clientProtocol = protocol.version().number();
    this.backendProtocol = this.clientProtocol;
    this.backendDefinition = protocol;
    this.loginPipeline = loginPipeline; this.forwarder = forwarder; this.runtime = runtime;
    this.commands = runtime.commandManager(); this.players = runtime.playerManager(); this.selector = runtime.selector();
    this.handshake = handshake; this.originalHandshake = originalHandshake; this.originalLoginStart = originalLoginStart; this.address = address;
    this.modClassifier = runtime.modded().classifyHandshake(handshake.requestedHost(), address, this.clientProtocol);
    this.switchQueue = runtime.modded().settings().packetQueueEnabled()
        ? runtime.modded().newSwitchQueue()
        : new SwitchPacketQueue(1);
    runtime.modded().remember(address, this.clientProtocol, this.modClassifier);
  }
  public ModLoaderFamily modLoaderFamily() { return modClassifier.family(); }
  public HandshakeClassifier modClassifier() { return modClassifier; }
  public TranslationSupport translationSupport() { return translationSupport; }
  public ProtocolDefinition backendDefinition() { return backendDefinition; }
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
      PluginPayloadValidator.validateChannel(decoded.channel());
      PluginPayloadValidator.validatePayload(decoded.data(), configuration.maxFrameBytes());
      if (direction == gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY) {
        modClassifier.observeChannel(decoded.channel());
        var outcome = runtime.security().channelGuard().inspect(decoded.channel(), username());
        if (outcome == gg.tame.conduit.security.ChannelGuard.Outcome.DROP) return false;
        if (outcome == gg.tame.conduit.security.ChannelGuard.Outcome.KICK) {
          disconnect("Blocked plugin channel.");
          return false;
        }
        if ("minecraft:brand".equalsIgnoreCase(decoded.channel()) || "MC|Brand".equals(decoded.channel())) {
          try { modClassifier.observeBrand(decoded.brandText()); } catch (IOException ignored) { }
        }
      }
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
    for (BackendServer server : selector.candidatesFor(protocol.version().number(), modClassifier.family(), false)) {
      try {
        prepareTranslation(server);
        Socket socket = BackendConnection.open(server);
        writeBackendHandshake(socket, server);
        MinecraftFrames.write(socket.getOutputStream(), LoginStart.encode(profile(), backendDefinition));
        BackendConnection connection = new BackendConnection(server, socket, backendDefinition, forwarder, profile(), address, configuration, false);
        // MODERN forwarding always needs the login plugin exchange.
        // TRANSLATED (e.g. 393→765) must also finish LOGIN here so Set Compression is consumed
        // and Configuration can be absorbed before Play — otherwise mode=none leaks compression
        // to the client (observed with real 1.13 → Paper 1.20.4).
        if (forwarder.mode() == ForwardingMode.MODERN || translationSupport == TranslationSupport.TRANSLATED) {
          completeBackendLogin(connection, true);
        }
        return connection;
      } catch (IOException exception) {
        last = exception;
        System.err.println("Backend unavailable: " + server.name() + " (" + exception.getMessage() + ")");
      }
    }
    throw last == null ? new IOException("all configured backends refused the connection") : last;
  }

  private void prepareTranslation(BackendServer server) {
    int advertised = selector.advertisement(server.name()).map(ad -> ad.protocol()).orElse(clientProtocol);
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, advertised);
    closeViaTranslator();
    if (support == TranslationSupport.TRANSLATED) {
      this.translationSupport = support;
      this.backendProtocol = advertised;
      this.backendDefinition = ProtocolDefinition.hasCodec(advertised)
          ? ProtocolDefinition.forVersion(advertised)
          : protocol;
      String host = server.address().getHostString();
      int port = server.address().getPort();
      this.translator = Translators.forPair(clientProtocol, advertised, host, port);
      String engine = translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator ? "ViaVersion" : "native";
      ProtocolTrace.note("session translation " + clientProtocol + "→" + advertised + " TRANSLATED (" + engine + ")");
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          clientProtocol, advertised, "TRANSLATED", engine).render().replace("\n", " | "));
    } else if (support == TranslationSupport.DIRECT) {
      this.translationSupport = TranslationSupport.DIRECT;
      this.backendProtocol = clientProtocol;
      this.backendDefinition = protocol;
      this.translator = IdentityTranslator.INSTANCE;
      ProtocolTrace.note("session translation " + clientProtocol + "→" + backendProtocol + " DIRECT");
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          clientProtocol, backendProtocol, "DIRECT", "identity").render().replace("\n", " | "));
    } else {
      // UNSUPPORTED Via-style backends that accept the client wire protocol.
      this.translationSupport = TranslationSupport.DIRECT;
      this.backendProtocol = clientProtocol;
      this.backendDefinition = protocol;
      this.translator = IdentityTranslator.INSTANCE;
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          clientProtocol, advertised, "UNSUPPORTED", "none").render().replace("\n", " | "));
    }
  }

  private void closeViaTranslator() {
    if (translator instanceof AutoCloseable closeable) {
      try { closeable.close(); } catch (Exception ignored) { }
    }
  }

  private void writeBackendHandshake(Socket socket, BackendServer server) throws IOException {
    if (translationSupport == TranslationSupport.TRANSLATED) {
      String host = server.address().getHostString();
      var marker = gg.tame.conduit.modded.FmlAddressMarkers.markerFor(modClassifier.family(), modClassifier.marker());
      if (marker != gg.tame.conduit.modded.FmlAddressMarkers.MarkerKind.NONE) {
        host = gg.tame.conduit.modded.FmlAddressMarkers.append(host, marker);
      } else if (modClassifier.marker() != gg.tame.conduit.modded.FmlAddressMarkers.MarkerKind.NONE) {
        host = gg.tame.conduit.modded.FmlAddressMarkers.append(host, modClassifier.marker());
      }
      Handshake backendHandshake = new Handshake(backendProtocol, host, server.address().getPort(), 2);
      MinecraftFrames.write(socket.getOutputStream(), backendHandshake.encode());
    } else {
      MinecraftFrames.write(socket.getOutputStream(), originalHandshake);
    }
  }

  private byte[] towardBackend(ConnectionState state, byte[] packet) {
    if (translator == IdentityTranslator.INSTANCE) return packet;
    if (translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via) {
      if (ProtocolTrace.enabled()) {
        try {
          ProtocolTrace.note("via client→backend " + via.stateDescription() + " id=0x"
              + Integer.toHexString(PlayPackets.packetId(packet)) + " len=" + packet.length);
        } catch (Exception ignored) { }
      }
      return via.clientToBackend(clientState.state(), packet);
    }
    return translator.clientToBackend(state, packet);
  }

  /**
   * Sends anything the translator produced beyond the packet it was handed.
   *
   * <p>Translation is not always one-in one-out across this pair: six 1.16
   * equipment slots become six 1.13 packets, and a 1.13 client will not touch
   * its inventory again until it gets the transaction confirmation that a 1.20.4
   * server has no packet for. Those extras are queued by the translator and sent
   * here, immediately after the packet that produced them, so ordering holds.
   */
  private int flushTranslatorExtras(BackendConnection target) {
    if (translator == IdentityTranslator.INSTANCE) return 0;
    int toBackend = 0;
    for (byte[] extra : translator.drainToClient()) {
      if (ProtocolTrace.enabled()) {
        try {
          ProtocolTrace.note("extra →client id=0x"
              + Integer.toHexString(PlayPackets.packetId(extra)) + " len=" + extra.length);
        } catch (Exception ignored) { }
      }
      try { writeClient(extra, true); } catch (IOException exception) { return toBackend; }
    }
    if (target == null) return toBackend;
    for (byte[] extra : translator.drainToBackend()) {
      if (ProtocolTrace.enabled()) {
        try {
          ProtocolTrace.note("extra →backend " + target.state() + " id=0x"
              + Integer.toHexString(PlayPackets.packetId(extra)) + " len=" + extra.length);
        } catch (Exception ignored) { }
      }
      try { target.writeUncompressed(extra); toBackend++; } catch (IOException exception) { return toBackend; }
    }
    return toBackend;
  }

  private byte[] towardClient(ConnectionState state, byte[] packet) {
    if (translator == IdentityTranslator.INSTANCE) return packet;
    // Via is told where the *client* is, never where the backend is. Across the 1.20.2 boundary
    // the two genuinely differ — a 1.13 client is in Play while the backend is still in
    // Configuration — and that split is the thing Via's downgrade path is keyed on. The native
    // translators are written against the backend state and keep getting it.
    if (translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via) {
      if (ProtocolTrace.enabled()) {
        try {
          ProtocolTrace.note("via backend→client " + via.stateDescription() + " id=0x"
              + Integer.toHexString(PlayPackets.packetId(packet)) + " len=" + packet.length);
        } catch (Exception ignored) { }
      }
      byte[] out = via.backendToClient(clientState.state(), packet);
      if (ProtocolTrace.enabled()) {
        try {
          ProtocolTrace.note("via  →client result " + (out == null ? "cancelled"
              : "id=0x" + Integer.toHexString(PlayPackets.packetId(out)) + " len=" + out.length));
        } catch (Exception ignored) { }
      }
      return out;
    }
    if (ProtocolTrace.enabled()) {
      try {
        ProtocolTrace.note("backend→client attempt " + state + " id=0x"
            + Integer.toHexString(PlayPackets.packetId(packet)) + " len=" + packet.length);
      } catch (Exception ignored) { }
    }
    return translator.backendToClient(state, packet);
  }
  private void completeBackendLogin(BackendConnection connection, boolean forwardLoginSuccess) throws IOException {
    int viaLoginPacketsToBackend = 0;
    while (connection.state() == ConnectionState.LOGIN) {
      byte[] packet = connection.readUncompressed();
      PacketTrace.packet("backend-login", connection.state(), PacketDirection.SERVER_TO_CLIENT, backendDefinition, packet);
      byte[] response = connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      if (response != null) { connection.writeUncompressed(response); continue; }
      if (!connection.login().shouldForward()) continue;
      if (!forwardLoginSuccess) throw new IOException("backend login failed");
      byte[] toClient = towardClient(ConnectionState.LOGIN, packet);
      if (toClient == null) continue;
      writeClient(toClient);
      loginPipeline.observe(PacketDirection.SERVER_TO_CLIENT, toClient);
      // Via answers Login Success on the old client's behalf, and it has to be sent while the
      // backend is still reading Login. Leaving it queued until the next flush delivers a
      // one-byte Login Acknowledged after the backend reached Play, where that same id is a
      // packet with a body — which is how a real 1.20.4 server ends up reporting a decoder
      // underflow a whole join later.
      if (viaEngine()) viaLoginPacketsToBackend += flushTranslatorExtras(connection);
    }
    // Always ack configuration using the BACKEND protocol when the backend has that phase.
    if (backendDefinition.hasConfiguration()
        && backendDefinition.defines(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED)) {
      // Only one Login Acknowledged may reach the backend. When Via already produced one, sending
      // Conduit's as well puts a stray packet on the wire.
      if (viaLoginPacketsToBackend == 0) {
        connection.writeUncompressed(PlayPackets.loginAcknowledged(backendDefinition));
      } else {
        ProtocolTrace.note("Via acknowledged backend login (" + viaLoginPacketsToBackend
            + " packet(s)); Conduit did not send its own");
      }
      if (translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via) {
        via.backendEntered(ConnectionState.CONFIGURATION);
      }
      if (forwardLoginSuccess && protocol.hasConfiguration()) expectClientLoginAck = true;
    }
    // Legacy client + modern backend: Conduit absorbs Configuration; client stays LOGIN→PLAY.
    if (!protocol.hasConfiguration() && backendDefinition.hasConfiguration()
        && connection.state() == ConnectionState.CONFIGURATION) {
      if (translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator) {
        runBackendConfigurationThroughVia(connection);
      } else {
        absorbBackendConfiguration(connection);
      }
    }
  }

  /**
   * Hands the backend's Configuration phase to Via instead of absorbing it natively.
   *
   * <p>Via's downgrade path to a pre-1.20.2 client is built on top of that phase: the registry
   * data, tags and feature flags a modern server sends there are what it later needs to rewrite
   * Join Game and every dimension-shaped packet after it. Conduit's native absorber consumes those
   * packets itself, which leaves Via with an empty dimension registry and makes the very first
   * Play packet fail to remap. So when Via is the engine, the packets go through it, and whatever
   * it decides to emit — usually nothing until it has seen Join Game — is forwarded.
   */
  private void runBackendConfigurationThroughVia(BackendConnection connection) throws IOException {
    while (connection.state() != ConnectionState.PLAY) {
      byte[] packet = connection.readUncompressed();
      connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      byte[] toClient;
      try {
        toClient = towardClient(ConnectionState.CONFIGURATION, packet);
      } catch (TranslationException translation) {
        ProtocolTrace.note("FAIL (via configuration) " + translation.getMessage());
        if (ProtocolTrace.enabled()) translation.printStackTrace();
        throw new IOException("Via could not translate the backend configuration phase: "
            + translation.getMessage(), translation);
      }
      if (toClient != null && toClient.length > 0) writeClient(toClient, true);
      flushTranslatorExtras(connection);
    }
    ProtocolTrace.note("Via handled backend configuration for " + clientProtocol + "→" + backendProtocol
        + " (via state " + viaStateDescription() + ")");
  }

  /** True while ViaVersion, rather than a native Conduit translator, owns this session's wire. */
  private boolean viaEngine() {
    return translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator;
  }

  private String viaStateDescription() {
    return translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via
        ? via.stateDescription()
        : "n/a";
  }

  /**
   * Completes the backend Configuration phase without exposing Configuration packets to a
   * client that has no such state (e.g. 1.13).
   */
  private void absorbBackendConfiguration(BackendConnection connection) throws IOException {
    var absorber = new gg.tame.conduit.protocol.translate.ConfigurationAbsorber(backendDefinition);
    if (clientInformation != null) absorber.setClientInformation(clientInformation);
    var settings = absorber.initialClientInformation();
    if (settings.isPresent()) connection.writeUncompressed(settings.get());
    while (connection.state() != ConnectionState.PLAY) {
      byte[] packet = connection.readUncompressed();
      connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      var response = absorber.onBackendPacket(packet);
      if (response.isPresent()) connection.writeUncompressed(response.get());
    }
    ProtocolTrace.note("configuration absorption complete for " + clientProtocol + "→" + backendProtocol);
  }
  private void readClient() {
    clientReader = Thread.currentThread();
    try {
      while (!closed) {
        byte[] packet = client.read(configuration.maxFrameBytes());
        if (ProtocolTrace.enabled()) {
          try {
            ProtocolTrace.note("client packet " + clientState.state() + " id=0x"
                + Integer.toHexString(PlayPackets.packetId(packet)) + " len=" + packet.length);
          } catch (Exception ignored) { }
        }
        if (handleClientPacket(packet)) continue;
        if (lifecycle.get() == SessionLifecycle.SWITCHING) {
          BackendConnection target = switchingTarget;
          if (target != null && clientState.state() == ConnectionState.CONFIGURATION) {
            byte[] outbound = towardBackend(ConnectionState.CONFIGURATION, packet);
            if (outbound != null) target.writeUncompressed(outbound);
          } else if (configuration.modded().packetQueueEnabled() && target != null) {
            try {
              switchQueue.enqueue(SwitchPacketQueue.Destination.NEW_BACKEND, packet,
                  clientState.state() == ConnectionState.PLAY ? SwitchPacketQueue.ConnectionPhase.PLAY
                      : SwitchPacketQueue.ConnectionPhase.CONFIGURATION);
            } catch (SwitchPacketQueue.OverflowException overflow) {
              throw new IOException(overflow.getMessage(), overflow);
            }
          }
          continue;
        }
        BackendConnection target = switchingTarget != null ? switchingTarget : backend;
        if (target != null) {
          if (!forwardPluginMessage(packet, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY, target)) {
            continue;
          }
          byte[] outbound = towardBackend(clientState.state(), packet);
          if (outbound != null) target.writeUncompressed(outbound);
          flushTranslatorExtras(target);
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
      if (protocol.capabilities().legacyPlayChat() && command.startsWith("/")) {
        command = command.substring(1);
      } else if (protocol.capabilities().legacyPlayChat() && !command.startsWith("/")) {
        return false;
      }
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
      KnownPacksValidator.validate(PlayPackets.body(packet), runtime.modded().knownPacksLimit());
      BackendConnection target = switchingTarget != null ? switchingTarget : backend;
      if (target != null) {
        byte[] outbound = towardBackend(ConnectionState.CONFIGURATION, packet);
        if (outbound != null) target.writeUncompressed(outbound);
      }
      knownPacksAck.offer(Boolean.TRUE);
      return true;
    }
    if (clientState.state() == ConnectionState.CONFIGURATION && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.CONFIGURATION_FINISH)) {
      BackendConnection target = switchingTarget != null ? switchingTarget : backend;
      if (target != null) {
        byte[] outbound = towardBackend(ConnectionState.CONFIGURATION, packet);
        if (outbound != null) target.writeUncompressed(outbound);
      }
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
        byte[] translated;
        try {
          translated = towardClient(backendState, packet);
        } catch (TranslationException translation) {
          gg.tame.conduit.log.ConduitLog.warn("Translation failed: " + translation.getMessage());
          ProtocolTrace.note("FAIL " + translation.getMessage());
          close();
          return;
        }
        if (translated == null) { flushTranslatorExtras(current); continue; }
        if (!forwardPluginMessage(translated, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.BACKEND_TO_PROXY, null)) continue;
        // Everything below this point compensates for gaps in Conduit's own translators: a brand
        // the client never gets told, a command tree that has to be merged, a Configuration phase
        // one side of the pair does not have, a Play stream that must wait for the other side to
        // be ready. Via closes those gaps itself, and closing them twice is worse than not at all
        // — a second brand, a duplicated player entry or a synthesised transition the client has
        // already made is what a real 1.13 client drops the connection over. When Via is the
        // engine its output goes to the client as it stands.
        if (viaEngine()) {
          writeClient(translated, true);
          flushTranslatorExtras(current);
          if (isPlayDisconnect(translated)) { close(); return; }
          continue;
        }
        var brand = BrandRewriter.rewrite(protocol, brandState(backendState), translated, configuration.maxFrameBytes());
        byte[] outbound;
        if (brand.isPresent()) {
          current.markBrandSeen();
          outbound = brand.get();
        } else {
          outbound = translated;
        }
        if (clientState.state() == ConnectionState.CONFIGURATION && isFinishConfiguration(translated) && !current.brandSeen()) {
          writeClient(BrandRewriter.synthesize(protocol, ConnectionState.CONFIGURATION, ""), true);
          current.markBrandSeen();
        }
        outbound = maybeMergeCommands(outbound);
        // A pre-1.20.2 backend has no Configuration phase and will never send the
        // finish_configuration that a modern client waits for, so the client sits
        // in Configuration while backend Play packets pile up in deferredPlay
        // until the bound trips and the session dies. Conduit has to synthesise
        // that transition itself: the state exists on one side of this pair only.
        synthesizeConfigurationFinishIfNeeded(current.state());
        if (deferPlayUntilReady(translated, outbound, current.state())) {
          flushDeferredPlay();
          continue;
        }
        writeClient(outbound, true);
        flushTranslatorExtras(current);
        if (clientState.state() == ConnectionState.PLAY && isPlayLogin(translated)) {
          emitSelfPlayerInfoIfNeeded();
        }
        if (isPlayDisconnect(translated)) { close(); return; }
      } catch (IOException exception) {
        if (closed || lifecycle.get() != SessionLifecycle.CONNECTED) return;
        ProtocolTrace.note("backend I/O: " + exception.getMessage());
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
  /**
   * Drives a modern client out of Configuration when the backend has no such state.
   *
   * <p>Only fires for a cross-version pair where the client protocol has a
   * Configuration phase and the backend protocol does not (e.g. a 1.20.4 client
   * on a 1.13 backend). Before Finish Configuration, Conduit must also synthesize
   * Registry Data / Feature Flags / Update Tags — a bare finish leaves
   * {@code minecraft:dimension_type} null and the 1.20.4 client NPEs on Join Game.
   *
   * <p>Idempotent: the synthesis happens at most once per session.
   */
  private void synthesizeConfigurationFinishIfNeeded(ConnectionState backendState) throws IOException {
    if (configurationFinishSynthesized) return;
    if (!protocol.hasConfiguration()) return;                 // client has no Configuration to leave
    if (ProtocolDefinition.forVersion(backendProtocol).hasConfiguration()) return;  // backend will send its own
    if (clientState.state() != ConnectionState.CONFIGURATION) return;
    if (backendState != ConnectionState.PLAY) return;         // wait until the backend is actually in Play
    configurationFinishSynthesized = true;
    var synth = gg.tame.conduit.protocol.translate.ConfigurationSynthesizer.packetsFor(clientProtocol);
    if (synth.isEmpty()) {
      throw new IOException("no configuration synthesizer for client protocol " + clientProtocol
          + " on legacy backend " + backendProtocol);
    }
    ProtocolTrace.note("CONFIG SYNTH " + synth.size() + " registry/feature/tag packets + finish for "
        + clientProtocol + " client on non-configuration backend " + backendProtocol);
    for (byte[] packet : synth) {
      writeClient(packet, true);
    }
    writeClient(PlayPackets.idOnly(protocol.id(ConnectionState.CONFIGURATION,
        PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH)), true);
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
  private void flushSwitchQueue(BackendConnection target) throws IOException {
    if (target == null || !configuration.modded().packetQueueEnabled()) {
      switchQueue.clear();
      return;
    }
    for (SwitchPacketQueue.QueuedPacket queued : switchQueue.flush(SwitchPacketQueue.Destination.NEW_BACKEND)) {
      if (queued.phase() == SwitchPacketQueue.ConnectionPhase.CONFIGURATION) {
        byte[] outbound = towardBackend(ConnectionState.CONFIGURATION, queued.packet());
        if (outbound != null) target.writeUncompressed(outbound);
      }
    }
    switchQueue.clear();
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
    sendMessage(Text.of(lost.server().name() + " is unavailable.").color(TextColor.RED));
    ConduitMetrics.current().fallbackEvent();
    for (BackendServer server : selector.fallback(lost.server().name(), failed, clientProtocol, modClassifier.family(), false)) {
      try {
        Messages.connecting(this, server.name());
        switchTo(server, true);
        Messages.connected(this, server.name());
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
    if (Thread.currentThread() == clientReader) {
      Thread.startVirtualThread(() -> {
        if (runSwitch(server)) Messages.connected(this, server.name());
        else Messages.unavailable(this, server.name());
      });
      return true;
    }
    boolean ok = runSwitch(server);
    if (ok) Messages.connected(this, server.name());
    else Messages.unavailable(this, server.name());
    return ok;
  }
  private boolean runSwitch(BackendServer server) {
    try {
      switchTo(server, false);
      return true;
    } catch (Exception exception) {
      ConduitMetrics.current().failedSwitch();
      return false;
    }
  }
  private static final int SWITCH_BUDGET_MS = 4_000;
  private void switchTo(BackendServer server, boolean fallback) throws Exception {
    var targetView = runtime.registered(server.name()).orElse(null);
    var sourceView = backend == null ? java.util.Optional.<gg.tame.conduit.api.server.RegisteredServer>empty() : runtime.registered(backend.server().name());
    if (targetView != null) {
      var connect = new gg.tame.conduit.api.event.player.PlayerServerConnectEvent(this, sourceView, targetView);
      runtime.events().fire(connect);
      if (connect.cancelled()) throw new IOException("connection cancelled");
    }
    long started = System.nanoTime();
    long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SWITCH_BUDGET_MS);
    ensureCompatible(server);
    synchronized (lock) {
      if (lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
      if (!fallback && lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
      // Stay CONNECTED during prepare so the current backend keeps flowing packets.
    }
    BackendConnection previous = backend;
    Socket socket = null;
    BackendConnection next = null;
    boolean clientEnteredConfiguration = false;
    try {
      // PREPARE: open + login against the target while the current backend remains active.
      prepareTranslation(server);
      socket = BackendConnection.open(server);
      enforceDeadline(deadline, "connect");
      Handshake switchHandshake = translationSupport == TranslationSupport.TRANSLATED
          ? new Handshake(backendProtocol, handshake.requestedHost(), handshake.requestedPort(), 2)
          : handshake;
      BackendConnection.handshake(socket, switchHandshake, server, profile(), modClassifier.marker(), modClassifier.family());
      next = new BackendConnection(server, socket, backendDefinition, forwarder, profile(), address, configuration, true);
      next.setReadTimeoutMillis(remainingMillis(deadline));
      completeBackendLogin(next, false);
      enforceDeadline(deadline, "login");
      replayClientInformation(next, ConnectionState.CONFIGURATION);

      // COMMIT: only now pause the old backend reader and involve the client.
      synchronized (lock) {
        if (lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
        if (!fallback && lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
        lifecycle.set(SessionLifecycle.SWITCHING);
        switchQueue.clear();
        lock.notifyAll();
      }
      gg.tame.conduit.protocol.ProfileTrace.beginSequence("switch to " + server.name(), 60);
      switchingTarget = next;
      if (!protocol.hasConfiguration() && backendDefinition.hasConfiguration()) {
        // 393-style client: Configuration already absorbed during completeBackendLogin.
        synchronized (lock) { deferredPlay.clear(); playLoginSent = false; needSelfPlayerInfo = true; }
      } else if (protocol.hasConfiguration()) {
        configurationAck.clear();
        synchronized (lock) { deferredPlay.clear(); playLoginSent = false; needSelfPlayerInfo = true; }
        writeClient(PlayPackets.startConfiguration(protocol));
        clientEnteredConfiguration = true;
        int waitSeconds = Math.max(1, remainingMillis(deadline) / 1000);
        if (configurationAck.poll(waitSeconds, TimeUnit.SECONDS) == null) throw new IOException("client did not acknowledge reconfiguration");
        configurationAck.clear();
        knownPacksAck.clear();
        boolean registrySeen = !protocol.knownPacks();
        boolean knownPacksDone = !protocol.knownPacks();
        byte[] lastConfig = null;
        while (next.state() != ConnectionState.PLAY) {
          enforceDeadline(deadline, "configuration");
          next.setReadTimeoutMillis(remainingMillis(deadline));
          byte[] packet = next.readUncompressed();
          int id = PlayPackets.packetId(packet);
          next.login().onBackendPacket(packet, configuration.maxFrameBytes());
          byte[] translated = towardClient(ConnectionState.CONFIGURATION, packet);
          if (translated == null) continue;
          var brand = BrandRewriter.rewrite(protocol, ConnectionState.CONFIGURATION, translated, configuration.maxFrameBytes());
          byte[] outbound;
          if (brand.isPresent()) {
            next.markBrandSeen();
            outbound = brand.get();
          } else {
            outbound = translated;
          }
          if (protocol.knownPacks() && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(translated), PacketKind.CONFIGURATION_KNOWN_PACKS)) {
            writeClient(outbound);
            if (knownPacksAck.poll(Math.max(1, remainingMillis(deadline) / 1000), TimeUnit.SECONDS) == null) {
              throw new IOException("client did not reply to known packs");
            }
            knownPacksDone = true;
            continue;
          }
          if (protocol.knownPacks() && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(translated), PacketKind.CONFIGURATION_REGISTRY)) {
            registrySeen = true;
          }
          if (isFinishConfiguration(translated)) {
            if (translated.length > 2) continue;
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
        int finishWait = Math.max(1, remainingMillis(deadline) / 1000);
        if (protocol.knownPacks()) {
          if (!waitForClient(configurationAck, next, finishWait)) throw new IOException("client did not finish configuration");
        } else if (configurationAck.poll(finishWait, TimeUnit.SECONDS) == null) {
          throw new IOException("client did not finish configuration");
        }
      }
      commandsDeclared = false;
      synchronized (lock) {
        backend = next;
        backendProtocol = selector.advertisement(server.name()).map(ad -> ad.protocol()).orElse(protocol.version().number());
        switchingTarget = null;
        next = null;
        lifecycle.set(SessionLifecycle.CONNECTED);
        lock.notifyAll();
      }
      flushSwitchQueue(backend);
      replayClientInformation(backend, ConnectionState.PLAY);
      if (previous != null) previous.close();
      gg.tame.conduit.metrics.ConduitMetrics.current().serverSwitch(System.nanoTime() - started);
      if (targetView != null) {
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerConnectedEvent(this, sourceView, targetView));
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchEvent(this, sourceView, targetView));
      }
    } catch (Exception exception) {
      switchingTarget = null;
      switchQueue.clear();
      if (next != null) next.close();
      else if (socket != null) try { socket.close(); } catch (IOException ignored) { }
      gg.tame.conduit.log.ConduitLog.warn("Switch to " + server.name() + " failed: " + exception.getMessage());
      if (targetView != null) {
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent(this, sourceView, targetView,
            exception.getMessage() == null ? "switch failed" : exception.getMessage()));
      }
      if (clientEnteredConfiguration) {
        // Client left PLAY; cannot safely restore the old Play session.
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
  private static void enforceDeadline(long deadlineNanos, String stage) throws IOException {
    if (System.nanoTime() > deadlineNanos) throw new IOException("switch timed out during " + stage);
  }
  private static int remainingMillis(long deadlineNanos) {
    long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    return (int) Math.max(1, Math.min(SWITCH_BUDGET_MS, remaining));
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
  public int clientProtocol() { return clientProtocol; }
  public int backendProtocol() { return backendProtocol; }
  private void ensureCompatible(BackendServer server) throws IOException {
    if (!ProtocolDefinition.hasCodec(clientProtocol)
        && !gg.tame.conduit.viaversion.ConduitViaSupport.knowsProtocol(clientProtocol)) {
      throw new IOException("Unsupported Minecraft version.");
    }
    var advertisement = selector.advertisement(server.name());
    if (advertisement.isEmpty()) return;
    int targetProtocol = advertisement.get().protocol();
    var support = ProtocolCompatibility.between(clientProtocol, targetProtocol);
    if (support == TranslationSupport.DIRECT) return;
    if (support == TranslationSupport.TRANSLATED) {
      if (gg.tame.conduit.viaversion.ConduitViaSupport.supportsTranslation(clientProtocol, targetProtocol)) {
        ProtocolTrace.note("Client protocol " + clientProtocol + " → " + server.name() + " " + targetProtocol + " (TRANSLATED/Via)");
        return;
      }
      var entry = gg.tame.conduit.protocol.CompatibilityRegistry.resolve(clientProtocol, targetProtocol);
      if (!entry.selectable()) {
        throw new IOException(server.name() + " translation path is not selectable for your Minecraft version.");
      }
      ProtocolTrace.note("Client protocol " + clientProtocol + " → " + server.name() + " " + targetProtocol + " (TRANSLATED/native)");
      return;
    }
    if (ProtocolDefinition.hasCodec(targetProtocol) && targetProtocol != clientProtocol
        && support == TranslationSupport.UNSUPPORTED
        && runtime.versionGate().settings().strictBackendMatch()) {
      throw new IOException(server.name() + " is not compatible with your Minecraft version.");
    }
    System.out.println("Client protocol " + clientProtocol + " connecting to " + server.name()
        + " advertised as " + targetProtocol + " (no Conduit translator; backend must accept the client protocol).");
  }
  private void emitSelfPlayerInfoIfNeeded() throws IOException {
    if (!needSelfPlayerInfo) return;
    if (clientState.state() != ConnectionState.PLAY) return;
    if (!protocol.capabilities().playerInfoUpdate()
        || !protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE)) {
      needSelfPlayerInfo = false;
      return;
    }
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) System.out.println("TRACE conduit synthesizing self ADD_PLAYER");
    writeClient(gg.tame.conduit.protocol.PlayerInfoUpdate.selfAdd(protocol, profile()));
    needSelfPlayerInfo = false;
  }
  private void writeClient(byte[] packet) throws IOException { writeClient(packet, true); }
  private void writeClient(byte[] packet, boolean flush) throws IOException {
    // The Play-phase half of this adapter reads Join Game and Player Info against Conduit's own
    // notion of the client's protocol. Via has already rewritten both into the client's dialect,
    // often into a different packet than the one the backend sent, so re-parsing them here is
    // reading a packet Conduit did not produce with a layout it only assumes. Login Success still
    // goes through it in every mode: that one carries the profile Conduit authenticated.
    ConnectionState adaptAs = viaEngine() && clientState.state() != ConnectionState.LOGIN
        ? ConnectionState.CLOSED
        : clientState.state();
    byte[] outbound = ProtocolProfileAdapter.backendToClient(protocol, adaptAs, packet, profile());
    // Last stop before the socket: a recipe list the client cannot parse costs the whole session,
    // and a correct one passes through this untouched.
    outbound = gg.tame.conduit.protocol.RecipeListRepair.apply(protocol, outbound);
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
      String where = lifecycle.get() == SessionLifecycle.SWITCHING ? "switch" : "steady";
      gg.tame.conduit.protocol.ProfileTrace.clientbound(where, protocol, clientState.state(), outbound, profile());
    }
    gg.tame.conduit.protocol.ClientboundDump.record(outbound);
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
    closeViaTranslator();
    synchronized (lock) { lock.notifyAll(); }
    if (players != null) players.remove(this);
    BackendConnection current = backend;
    if (current != null) current.close();
    BackendConnection switching = switchingTarget;
    if (switching != null) switching.close();
    client.close();
  }
}

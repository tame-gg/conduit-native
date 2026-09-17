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
  /**
   * Guards the translator. A translator is one stateful decoder per session, not a pure function:
   * ViaVersion's connection carries entity trackers, world state and a partially written buffer.
   * Both reader threads reach it — the client thread translating serverbound packets and draining
   * whatever they produced, the backend thread translating clientbound ones — so the transforms
   * have to be serialised or two of them interleave inside one connection's state.
   */
  private final Object translatorLock = new Object();
  private final BlockingQueue<Boolean> configurationAck = new ArrayBlockingQueue<>(1);
  private final BlockingQueue<Boolean> knownPacksAck = new ArrayBlockingQueue<>(1);
  private volatile BackendConnection backend;
  private volatile BackendConnection switchingTarget;
  private volatile boolean closed;
  private volatile boolean expectClientLoginAck;
  /**
   * The command name a client with no command tree last sent the backend to complete, until the
   * backend's reply has had Conduit's matching commands added. That reply is the only place such a
   * client learns command names from, and the backend knows none of Conduit's.
   */
  private volatile String legacyCommandCompletion;
  /**
   * True while Conduit, rather than the translator, is the one moving the client out of Play for a
   * reconfiguration, so the client's acknowledgement is Conduit's own reply and must not also be
   * handed to a translator that has already put its half of the connection into Configuration.
   */
  private volatile boolean conduitOwnsReconfiguration;
  /**
   * Holds while a switched session's client has not yet been written the new backend's Join Game.
   *
   * <p>Only a client with no Configuration phase can be in this window: one that has a phase is
   * parked in it and sends nothing from Play meanwhile. A 1.8 client is never parked anywhere. It
   * keeps sending movement across the whole switch, and the translator those packets now go to was
   * built moments ago and has been told nothing about the world the client is standing in -- it has
   * no entity for the player and no dimension to place them in, and rewriting a position against
   * that is not a translation error but a crash inside the rewriter.
   */
  private final SwitchJoinGate awaitingBackendJoinGame;
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
    this.awaitingBackendJoinGame = new SwitchJoinGate(protocol);
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
        // Every pair and every forwarding mode, as a switch already does. This is where the backend's
        // Set Compression is consumed: the client's link is never compressed. Left to the relay, a
        // DIRECT 1.20.4 session with forwarding "none" handed that packet to the client, and Conduit
        // then read both sockets in the wrong format until the backend's Finish Configuration parsed
        // as a plugin message and ended the session.
        completeBackendLogin(connection, true);
        return connection;
      } catch (IOException exception) {
        last = exception;
        System.err.println("Backend unavailable: " + server.name() + " (" + exception.getMessage() + ")");
      }
    }
    throw last == null ? new IOException("all configured backends refused the connection") : last;
  }

  /**
   * One backend's share of the session: which protocol it speaks, the packet table for it, and the
   * translator that reaches it.
   *
   * <p>These four travel together because they describe one connection, not the session. A switch
   * has two backends alive at once — the old one still carrying the player while the new one logs
   * in — and each needs its own. Holding them as session fields instead meant the moment a switch
   * started preparing, every packet the client sent was encoded for a backend it was not being
   * written to: a real 1.20.4 client's Set Player Position reached the still-live 1.13 backend as
   * raw 1.20.4, and that backend closed the connection.
   */
  private record Translation(TranslationSupport support, int backendProtocol,
                             ProtocolDefinition definition, ProtocolTranslator translator) {
    void close() {
      if (translator instanceof AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { }
      }
    }
  }

  /** Works out the translation for {@code server} without disturbing the one already in use. */
  private Translation buildTranslation(BackendServer server) {
    int advertised = selector.resolveProtocol(server.name()).orElse(clientProtocol);
    TranslationSupport support = ProtocolCompatibility.between(clientProtocol, advertised);
    if (support == TranslationSupport.TRANSLATED) {
      ProtocolDefinition definition = ProtocolDefinition.hasCodec(advertised)
          ? ProtocolDefinition.forVersion(advertised)
          : protocol;
      ProtocolTranslator engine = Translators.forPair(clientProtocol, advertised,
          server.address().getHostString(), server.address().getPort());
      String name = engine instanceof gg.tame.conduit.viaversion.ConduitViaTranslator ? "ViaVersion" : "native";
      ProtocolTrace.note("session translation " + clientProtocol + "→" + advertised + " TRANSLATED (" + name + ")");
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          clientProtocol, advertised, "TRANSLATED", name).render().replace("\n", " | "));
      return new Translation(support, advertised, definition, engine);
    }
    if (support == TranslationSupport.DIRECT) {
      ProtocolTrace.note("session translation " + clientProtocol + "→" + clientProtocol + " DIRECT");
      System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
          clientProtocol, clientProtocol, "DIRECT", "identity").render().replace("\n", " | "));
      return new Translation(TranslationSupport.DIRECT, clientProtocol, protocol, IdentityTranslator.INSTANCE);
    }
    // UNSUPPORTED Via-style backends that accept the client wire protocol.
    System.out.println(gg.tame.conduit.viaversion.ConduitViaDiagnostics.of(
        clientProtocol, advertised, "UNSUPPORTED", "none").render().replace("\n", " | "));
    return new Translation(TranslationSupport.DIRECT, clientProtocol, protocol, IdentityTranslator.INSTANCE);
  }

  /** Makes {@code next} the session's translation and closes whatever it replaces. */
  private void install(Translation next) {
    Translation previous = new Translation(translationSupport, backendProtocol, backendDefinition, translator);
    this.translationSupport = next.support();
    this.backendProtocol = next.backendProtocol();
    this.backendDefinition = next.definition();
    this.translator = next.translator();
    if (previous.translator() != next.translator()) previous.close();
  }

  private void prepareTranslation(BackendServer server) {
    install(buildTranslation(server));
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
    return towardBackend(translator, state, packet);
  }

  private byte[] towardBackend(ProtocolTranslator translator, ConnectionState state, byte[] packet) {
    if (translator == IdentityTranslator.INSTANCE) return packet;
    synchronized (translatorLock) {
      return towardBackendLocked(translator, state, packet);
    }
  }

  private byte[] towardBackendLocked(ProtocolTranslator translator, ConnectionState state, byte[] packet) {
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
    return flushTranslatorExtras(translator, target);
  }

  private int flushTranslatorExtras(ProtocolTranslator translator, BackendConnection target) {
    if (translator == IdentityTranslator.INSTANCE) return 0;
    synchronized (translatorLock) {
      return flushTranslatorExtrasLocked(translator, target);
    }
  }

  private int flushTranslatorExtrasLocked(ProtocolTranslator translator, BackendConnection target) {
    int toBackend = 0;
    for (byte[] extra : translator.drainToClient()) {
      if (ProtocolTrace.enabled()) {
        try {
          ProtocolTrace.note("extra →client id=0x"
              + Integer.toHexString(PlayPackets.packetId(extra)) + " len=" + extra.length);
        } catch (Exception ignored) { }
      }
      try { writeClient(extra, true); } catch (IOException exception) {
        // Abandoning the rest of the queue silently is how a translator-built Configuration
        // phase can lose its Update Tags and Finish Configuration behind a Registry Data that
        // failed to reach the socket, leaving the client waiting in a phase nothing finishes.
        // Whatever is dropped here is named, so the next failure is readable rather than absent.
        String id;
        try { id = "0x" + Integer.toHexString(PlayPackets.packetId(extra)); }
        catch (Exception unreadable) { id = "(unreadable)"; }
        gg.tame.conduit.log.ConduitLog.warn("Dropped clientbound extra id=" + id
            + " len=" + extra.length + " while lifecycle=" + lifecycle.get()
            + " clientState=" + clientState.state()
            + "; the rest of the queue is discarded with it: " + exception);
        return toBackend;
      }
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

  private byte[] towardClient(ConnectionState state, byte[] packet) throws IOException {
    return towardClient(translator, state, packet);
  }

  private byte[] towardClient(ProtocolTranslator translator, ConnectionState state, byte[] packet) throws IOException {
    if (translator == IdentityTranslator.INSTANCE) return packet;
    synchronized (translatorLock) {
      return towardClientLocked(translator, state, packet);
    }
  }

  private byte[] towardClientLocked(ProtocolTranslator translator, ConnectionState state, byte[] packet) throws IOException {
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
      // What Via produced earlier goes out before anything it produces now. The other reader thread
      // can leave packets queued between its transform and its flush -- a client's Configuration
      // acknowledgement is what releases a switched Join Game -- and a chunk translated here in that
      // gap reached a 1.21.8 client ahead of the Join Game it belongs to.
      for (byte[] earlier : via.drainToClient()) {
        writeClient(earlier, true);
      }
      byte[] out = via.backendToClient(clientState.state(), packet);
      // Via sends some packets while handling another, and in its own pipeline those reach the wire
      // first. Every caller writes the result and only then the extras, so these go out here. A
      // 1.21.8 client on a 1.20.4 backend otherwise got its missing registries after Finish
      // Configuration and disconnected over registries that were never populated.
      for (byte[] ahead : via.drainAheadOfResult()) {
        if (ProtocolTrace.enabled()) {
          try {
            ProtocolTrace.note("ahead →client id=0x" + Integer.toHexString(PlayPackets.packetId(ahead)) + " len=" + ahead.length);
          } catch (Exception ignored) { }
        }
        writeClient(ahead, true);
      }
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
    completeBackendLogin(connection, forwardLoginSuccess,
        new Translation(translationSupport, backendProtocol, backendDefinition, translator));
  }

  /**
   * Finishes LOGIN against {@code connection} using {@code target}'s translation rather than the
   * session's. During a switch the session's translation still belongs to the backend the player is
   * currently on, and must not be used to talk to the one being prepared.
   */
  private void completeBackendLogin(BackendConnection connection, boolean forwardLoginSuccess,
                                    Translation target) throws IOException {
    ProtocolDefinition backendDefinition = target.definition();
    ProtocolTranslator translator = target.translator();
    int viaLoginPacketsToBackend = 0;
    while (connection.state() == ConnectionState.LOGIN) {
      byte[] packet = connection.readUncompressed();
      PacketTrace.packet("backend-login", connection.state(), PacketDirection.SERVER_TO_CLIENT, backendDefinition, packet);
      byte[] response = connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      if (response != null) { connection.writeUncompressed(response); continue; }
      if (!connection.login().shouldForward()) {
        armViaConfigurationBridge(translator, backendDefinition, packet);
        continue;
      }
      if (!forwardLoginSuccess) throw new IOException("backend login failed");
      byte[] toClient = towardClient(translator, ConnectionState.LOGIN, packet);
      if (toClient == null) continue;
      writeClient(toClient);
      loginPipeline.observe(PacketDirection.SERVER_TO_CLIENT, toClient);
      // Via answers Login Success on the old client's behalf, and it has to be sent while the
      // backend is still reading Login. Leaving it queued until the next flush delivers a
      // one-byte Login Acknowledged after the backend reached Play, where that same id is a
      // packet with a body — which is how a real 1.20.4 server ends up reporting a decoder
      // underflow a whole join later.
      if (translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator) {
        viaLoginPacketsToBackend += flushTranslatorExtras(translator, connection);
      }
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
        runBackendConfigurationThroughVia(connection, target);
      } else {
        absorbBackendConfiguration(connection, backendDefinition);
      }
    }
  }

  /**
   * Walks a switch's freshly opened Via session through the login exchange its Configuration
   * bridge is armed by.
   *
   * <p>Via does not carry a flag saying "this pair needs a Configuration phase built". It watches
   * for the exchange that creates one: a backend Login Success going out to the client, and the
   * client's Login Acknowledged coming back. Only a session that has seen both treats the packets
   * it later synthesises as Configuration packets. A session that has not sees the same work as
   * ordinary Play, and emits it with Play packet ids.
   *
   * <p>On a first connection that exchange happens on its own, because Conduit forwards the
   * backend's Login Success through the translator and the client's acknowledgement follows it
   * back. A <em>switch</em> has neither. Conduit performs the new backend's login itself and
   * deliberately withholds its Login Success, since the client is already logged in and must not be
   * sent a second one, so nothing that arms the bridge ever reaches Via. What Via then produces
   * from a 1.13.2 backend's Join Game is a Change Difficulty carrying the Play id {@code 0x0B},
   * which a 1.20.4 client sitting in Configuration disconnects on, before the Registry Data behind
   * it is ever written.
   *
   * <p>So the exchange is replayed into the session out of packets Conduit already holds: the
   * backend's real Login Success, and a Login Acknowledged in the client's own dialect, which is
   * exactly the packet Conduit is standing in for the client to send. Both halves of what Via
   * produces in reply are discarded on purpose. Its Login Success belongs to a login the client
   * already completed, and its Login Acknowledged would be a second one on a backend socket Conduit
   * has acknowledged itself.
   */
  private void armViaConfigurationBridge(ProtocolTranslator translator, ProtocolDefinition backendDefinition,
                                         byte[] backendPacket) throws IOException {
    if (!(translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via)) return;
    if (!backendDefinition.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT,
        PlayPackets.packetId(backendPacket), PacketKind.LOGIN_SUCCESS)) {
      return;
    }
    if (!protocol.hasConfiguration()) {
      if (backendDefinition.hasConfiguration()) {
        // This pair is relayed by runBackendConfigurationThroughVia, and that relay only works on a
        // session that watched the login it is downgrading the Configuration phase of. A first
        // connection supplies it: Conduit forwards the backend's Login Success through the
        // translator, which is what puts the translator's downgrade into the mode that buffers a
        // Configuration phase for a client that has none. A switch withholds that packet, and the
        // relay then fails on the first registry packet it is handed, with the 1.20.2 downgrade
        // remapping a Configuration id to nothing.
        //
        // So the packet is replayed, and only it: this client has no Login Acknowledged to follow
        // it with, and needs none -- it leaves Login when the translator writes it a Login Success,
        // which is the very packet being replayed. What the translator produces is discarded,
        // because the client completed this login against a different backend already.
        synchronized (translatorLock) {
          via.backendToClient(ConnectionState.LOGIN, backendPacket);
          via.drainToClient();
          via.drainToBackend();
        }
        ProtocolTrace.note("switch replayed login for a legacy client; via state " + via.stateDescription());
        return;
      }
      // Neither end has a Configuration phase, so there is no bridge to arm and no packet left to
      // arm it with: the Join Game that moved this client into Play belongs to the backend it is
      // leaving, and the backend is in Play the moment the Login Success Conduit is holding was
      // written. Both are handed over, which is safe precisely because the phase the guard above
      // excludes is the one that must not be skipped. Left in Login, the translator reads the new
      // backend's Join Game in a state it has no Join Game in and passes it through unchanged --
      // a 1.13 packet with a 1.13 id, to a 1.8 client.
      via.adoptClientState(clientState.state());
      via.backendEntered(ConnectionState.PLAY);
      ProtocolTrace.note("switch adopted both states; via state " + via.stateDescription());
      return;
    }
    if (!protocol.defines(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED)) return;
    // Replayed for a backend with its own Configuration phase too. That backend builds no bridge,
    // but the exchange is still the only thing that moves Via's client half out of Login: without
    // it a 1.21.8 client's Client Information reached a 1.20.4 backend untranslated, one field
    // longer than that backend reads, and the backend closed the connection.
    synchronized (translatorLock) {
      via.backendToClient(ConnectionState.LOGIN, backendPacket);
      via.drainToClient();
      via.drainToBackend();
      via.clientToBackend(ConnectionState.LOGIN, PlayPackets.loginAcknowledged(protocol));
      via.drainToClient();
      via.drainToBackend();
    }
    ProtocolTrace.note("switch armed the Via configuration bridge; via state " + via.stateDescription());
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
  private void runBackendConfigurationThroughVia(BackendConnection connection, Translation target) throws IOException {
    while (connection.state() != ConnectionState.PLAY) {
      byte[] packet = connection.readUncompressed();
      connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
      byte[] toClient;
      try {
        toClient = towardClient(target.translator(), ConnectionState.CONFIGURATION, packet);
      } catch (TranslationException translation) {
        ProtocolTrace.note("FAIL (via configuration) " + translation.getMessage());
        if (ProtocolTrace.enabled()) translation.printStackTrace();
        throw new IOException("Via could not translate the backend configuration phase: "
            + translation.getMessage(), translation);
      }
      if (toClient != null && toClient.length > 0) writeClient(toClient, true);
      flushTranslatorExtras(target.translator(), connection);
    }
    ProtocolTrace.note("Via handled backend configuration for " + clientProtocol + "→" + target.backendProtocol()
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
  private void absorbBackendConfiguration(BackendConnection connection, ProtocolDefinition backendDefinition) throws IOException {
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
        if (awaitingBackendJoinGame.holding()) {
          // Dropped rather than queued, deliberately. What a client sends in this window is its
          // position and look for a world it is about to be moved out of, and the new backend will
          // place it itself; replaying any of it afterwards would fight that. The client resends
          // both within a tick of arriving.
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
      // Withheld from the backend, because Conduit acknowledged that login itself and a second
      // acknowledgement is a stray packet. Shown to the translator anyway: this is the packet it
      // learns the client has left Login from, and it has no other source for that. Without it
      // the translator keeps reading the client as if it were still logging in, and the first
      // Configuration packet the client sends is transformed in the wrong state -- a 1.21 client's
      // config plugin message carries id 0x02, which is Finish Configuration on a 1.20.4 backend,
      // and the backend closes the connection over the 24 bytes that followed it.
      feedTranslatorClientLoginAck(packet);
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
      PlayPackets.TabRequest request = PlayPackets.tabRequest(protocol, packet);
      var command = gg.tame.conduit.command.ParsedCommand.parseKeepEmpty(request.text());
      if (request.text().startsWith("/") && commands.get(command.name()).isPresent()) {
        List<String> completions = commands.tabComplete(this, request.text());
        // A client with no command tree puts each match in place of the last word it typed, so a
        // command name has to come back with the slash it was typed with.
        if (!protocol.capabilities().commandTree() && request.text().indexOf(' ') < 0) {
          completions = completions.stream().map(name -> "/" + name).toList();
        }
        int start = request.text().lastIndexOf(' ') + 1;
        int length = Math.max(0, request.text().length() - start);
        writeClient(PlayPackets.tabComplete(protocol, request.transactionId(), start, length, completions));
        return true;
      }
      if (!protocol.capabilities().commandTree() && request.text().startsWith("/") && request.text().indexOf(' ') < 0) {
        legacyCommandCompletion = request.text();
      }
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED)) {
      if (viaEngine() && !conduitOwnsReconfiguration) {
        // When the translator is the one reconfiguring the client, this is the reply it is waiting
        // for: it holds the backend's entire world stream from Join Game onwards until it sees the
        // client leave Play. Answering it here and going no further leaves it holding forever, and
        // the client sits in a Configuration phase nothing ever finishes.
        BackendConnection target = switchingTarget != null ? switchingTarget : backend;
        byte[] outbound = towardBackend(ConnectionState.PLAY, packet);
        if (outbound != null && target != null) target.writeUncompressed(outbound);
        flushTranslatorExtras(target);
      }
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
      // Finishing Configuration is what makes the translator release everything it held behind the
      // backend's Join Game. Those packets are queued rather than returned, so without a flush here
      // they wait for whatever client packet happens to come next, and the client's world arrives
      // late or not at all if it is waiting on that world before it sends anything.
      flushTranslatorExtras(target);
      configurationAck.offer(Boolean.TRUE);
      flushDeferredPlay();
      return true;
    }
    return false;
  }
  /**
   * Advances the translator's view of the client past Login, without putting anything on a wire.
   *
   * <p>The translator moves the client between states by watching the packets that cause those
   * transitions. Conduit consumes this one, so the transition has to be handed over explicitly,
   * and this is the only place it can be: the packet exists here and nowhere else.
   */
  private void feedTranslatorClientLoginAck(byte[] packet) {
    if (!(translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via)) return;
    synchronized (translatorLock) {
      try {
        via.clientToBackend(ConnectionState.LOGIN, packet);
      } catch (RuntimeException failure) {
        ProtocolTrace.note("translator rejected the client's login acknowledgement: " + failure);
        return;
      }
      via.drainToClient();
      via.drainToBackend();
    }
    ProtocolTrace.note("client login acknowledged; via state " + via.stateDescription());
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
  /**
   * Replays the cached Client Information to a backend.
   *
   * <p>The cached body is the <em>client's</em>, in the client's layout, and the packet that carries
   * it has to reach the backend in the <em>backend's</em> dialect. Those are only the same thing on
   * a DIRECT pair. So this picks a state both ends actually have and then sends the packet the way
   * every other client packet is sent — through the session's translator — rather than stamping the
   * client's packet id onto the client's body and putting it on the backend socket.
   *
   * <p>Doing the latter is what a 1.13.2 backend received during a switch from a 1.20.4 client: the
   * 1.20.4 Configuration id {@code 0x00}, followed by a body two booleans longer than 1.13.2 defines,
   * on a backend with no Configuration phase at all. Its decoder read that as a second Login Start
   * and closed the connection over the seven bytes it could not account for.
   */
  private void replayClientInformation(BackendConnection target, ConnectionState state) {
    replayClientInformation(target, state,
        new Translation(translationSupport, backendProtocol, backendDefinition, translator));
  }

  private void replayClientInformation(BackendConnection target, ConnectionState state, Translation via) {
    ProtocolDefinition backendDefinition = via.definition();
    byte[] body = clientInformation;
    if (body == null || target == null) return;
    // A backend with no Configuration phase has nothing to seed before the client reconfigures,
    // and its Play state is reached in the same breath as Login Success. Sending anything into that
    // window risks the backend still decoding as Login. The post-commit Play replay covers it.
    if (state == ConnectionState.CONFIGURATION && !backendDefinition.hasConfiguration()) return;
    ConnectionState replayState = state;
    PacketKind kind = replayState == ConnectionState.CONFIGURATION
        ? PacketKind.CONFIGURATION_CLIENT_INFORMATION
        : PacketKind.PLAY_CLIENT_INFORMATION;
    // The body is the client's, so the client has to have this packet in this state to have sent it.
    if (!protocol.defines(replayState, PacketDirection.CLIENT_TO_SERVER, kind)) return;
    if (!backendDefinition.defines(replayState, PacketDirection.CLIENT_TO_SERVER, kind)) return;
    try {
      byte[] clientForm = PlayPackets.withId(protocol.id(replayState, PacketDirection.CLIENT_TO_SERVER, kind), body);
      byte[] outbound;
      try {
        outbound = towardBackend(via.translator(), replayState, clientForm);
      } catch (RuntimeException translation) {
        ProtocolTrace.note("could not translate replayed client information for "
            + target.server().name() + ": " + translation.getMessage());
        return;
      }
      if (outbound == null || outbound.length == 0) return;
      target.writeUncompressed(outbound);
      flushTranslatorExtras(via.translator(), target);
      if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
        System.out.println("TRACE replayed client information to " + target.server().name() + " in " + replayState);
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
        if (translated == null) {
          flushTranslatorExtras(current);
          // Via can cancel the backend's Join Game and emit the client's as an extra instead.
          resumeAfterSwitchedJoinGame(current);
          continue;
        }
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
          resumeAfterSwitchedJoinGame(current);
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
        resumeAfterSwitchedJoinGame(current);
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
  /** Ends a switched client's hold once the new backend's Join Game has been written to it. */
  private void resumeAfterSwitchedJoinGame(BackendConnection current) throws IOException {
    byte[] joinGame = awaitingBackendJoinGame.takeJoinGame();
    if (joinGame == null) return;
    ProtocolTrace.note("switched translator has the new backend's Join Game; client packets resume");
    // A client with no Configuration phase has no transition left that rebuilds its world,
    // so a second Join Game leaves it holding the previous backend's one. These move it.
    for (byte[] reload : gg.tame.conduit.protocol.LegacyWorldReload.afterSwitch(protocol, joinGame)) {
      writeClient(reload, true);
    }
    // Held back with them, and for the same reason: the replay is a client packet Conduit
    // sends on the player's behalf, and it goes through the same translator that had no
    // world to translate it against until this packet arrived.
    replayClientInformation(current, ConnectionState.PLAY);
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
  /**
   * Budget for each half of a translator-driven reconfiguration, measured after the switch has
   * already committed. It is separate from {@link #SWITCH_BUDGET_MS}, which covers connecting and
   * logging in to the new backend: by this point that work is done, and what is being waited on is
   * a real client loading a world it has just been handed.
   */
  private static final int RECONFIGURE_BUDGET_MS = 15_000;
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
    Translation pending = null;
    boolean clientEnteredConfiguration = false;
    boolean finishAfterCommit = false;
    try {
      // PREPARE: open + login against the target while the current backend remains active. The
      // target's translation is built but NOT installed: until commit, the session's translator
      // still belongs to the backend the player is actually on, and the client's packets have to
      // keep being encoded for that one.
      pending = buildTranslation(server);
      socket = BackendConnection.open(server);
      enforceDeadline(deadline, "connect");
      Handshake switchHandshake = pending.support() == TranslationSupport.TRANSLATED
          ? new Handshake(pending.backendProtocol(), handshake.requestedHost(), handshake.requestedPort(), 2)
          : handshake;
      BackendConnection.handshake(socket, switchHandshake, server, profile(), modClassifier.marker(), modClassifier.family());
      next = new BackendConnection(server, socket, pending.definition(), forwarder, profile(), address, configuration, true);
      next.setReadTimeoutMillis(remainingMillis(deadline));
      completeBackendLogin(next, false, pending);
      enforceDeadline(deadline, "login");
      replayClientInformation(next, ConnectionState.CONFIGURATION, pending);

      // COMMIT: only now pause the old backend reader and involve the client.
      synchronized (lock) {
        if (lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
        if (!fallback && lifecycle.get() != SessionLifecycle.CONNECTED) throw new IOException("session busy");
        lifecycle.set(SessionLifecycle.SWITCHING);
        switchQueue.clear();
        // The old backend stops receiving client packets here, so this is the first moment the
        // session's translation can move to the new one without misaddressing anything.
        install(pending);
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
        // Who reconfigures the client depends on who can. A backend with its own Configuration
        // phase supplies the packets and Conduit relays them. A backend without one supplies
        // nothing, and on the Via engine the translator builds that phase itself out of the
        // backend's Join Game — including the Start Configuration that begins it. Conduit sending
        // its own as well puts the client into a phase it is already entering, and the second
        // handshake never completes.
        finishAfterCommit = viaEngine() && !backendDefinition.hasConfiguration();
        if (finishAfterCommit) {
          clientEnteredConfiguration = true;
          // The translator reached Configuration on the client's behalf during prepare, out of the
          // login exchange replayed into it there. What that exchange cannot tell it is that the
          // real client is in Play: the connection it was shown looks like a first join, so it
          // never produces the Start Configuration a player who is already in a world needs.
          // Conduit sends that one itself, and therefore owns the acknowledgement, which is
          // answered here rather than passed on to a translator already in Configuration.
          //
          // The wait is what keeps the order right. The backend's Join Game is the packet the
          // translator turns into the whole Configuration phase, and it is read on the steady path
          // that the commit below starts. Committing first races that phase against a client still
          // in Play, which is the same disconnect by a different route.
          conduitOwnsReconfiguration = true;
          ProtocolTrace.note("switch reconfiguring client; via state " + viaStateDescription());
          writeClient(PlayPackets.startConfiguration(protocol));
          if (configurationAck.poll(RECONFIGURE_BUDGET_MS, TimeUnit.MILLISECONDS) == null) {
            throw new IOException("client did not acknowledge reconfiguration");
          }
          configurationAck.clear();
        } else {
        // Conduit asks for this reconfiguration, so the acknowledgement is Conduit's. The new
        // backend is already in Configuration and has no Play packet to receive it as.
        conduitOwnsReconfiguration = true;
        writeClient(PlayPackets.startConfiguration(protocol));
        clientEnteredConfiguration = true;
        int waitSeconds = Math.max(1, remainingMillis(deadline) / 1000);
        if (configurationAck.poll(waitSeconds, TimeUnit.SECONDS) == null) throw new IOException("client did not acknowledge reconfiguration");
        conduitOwnsReconfiguration = false;
        configurationAck.clear();
        knownPacksAck.clear();
        // Both checks watch what this loop relays, and on the Via engine that is not everything the
        // client gets: from a 1.20.4 backend's Registry Data Via writes Known Packs and every
        // registry itself, ahead of the result, answers the client's reply itself, and hands this
        // loop nothing. Enforced there, they failed a switch whose client had received both.
        boolean registrySeen = !protocol.knownPacks() || viaEngine();
        boolean knownPacksDone = !protocol.knownPacks() || viaEngine();
        byte[] lastConfig = null;
        while (!finishAfterCommit && next.state() != ConnectionState.PLAY) {
          enforceDeadline(deadline, "configuration");
          next.setReadTimeoutMillis(remainingMillis(deadline));
          byte[] packet = next.readUncompressed();
          int id = PlayPackets.packetId(packet);
          next.login().onBackendPacket(packet, configuration.maxFrameBytes());
          byte[] translated = towardClient(ConnectionState.CONFIGURATION, packet);
          if (translated == null) {
            // A cancelled packet can still have an answer: Via takes a 1.21.8 backend's Known Packs
            // away from a 1.20.4 client and replies to the backend itself, which waits for it.
            flushTranslatorExtras(next);
            continue;
          }
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
        // waitForClient reads the backend while it waits and relays what it reads untranslated. On
        // the Via engine that took a 1.20.4 backend's Join Game past the translator, and the client
        // disconnected on the Change Difficulty behind it; the read path after commit translates it.
        if (protocol.knownPacks() && !viaEngine()) {
          if (!waitForClient(configurationAck, next, finishWait)) throw new IOException("client did not finish configuration");
        } else if (configurationAck.poll(finishWait, TimeUnit.SECONDS) == null) {
          throw new IOException("client did not finish configuration");
        }
        }
      }
      commandsDeclared = false;
      // A client with a Configuration phase is held in it until the phase finishes, so its Play
      // packets cannot race the new backend's Join Game. One without a phase has nothing holding
      // it, and this is what holds it instead -- whatever carries the pair, not only Via. The new
      // backend has no Configuration phase either, and it only starts decoding Play when it sends
      // Join Game: a real 1.8.9 client switched DIRECT between two 1.8.9 servers had the replayed
      // Client Settings land in that gap, and both servers dropped it with "Bad packet id 21".
      if (!protocol.hasConfiguration()) awaitingBackendJoinGame.hold();
      else awaitingBackendJoinGame.release();
      synchronized (lock) {
        backend = next;
        switchingTarget = null;
        next = null;
        lifecycle.set(SessionLifecycle.CONNECTED);
        lock.notifyAll();
      }
      flushSwitchQueue(backend);
      if (finishAfterCommit) {
        // The client is already in Configuration; the phase itself is built by the translator out
        // of the backend's Join Game, which only reaches it once the steady read path the commit
        // above started delivers it. All that is left to wait for is the client finishing.
        try {
          if (configurationAck.poll(RECONFIGURE_BUDGET_MS, TimeUnit.MILLISECONDS) == null) {
            throw new IOException("client did not finish configuration");
          }
        } finally {
          conduitOwnsReconfiguration = false;
        }
      }
      // Not replayed yet when the translator is still waiting for the new backend's Join Game; the
      // read loop does it as soon as that arrives.
      if (!awaitingBackendJoinGame.holding()) replayClientInformation(backend, ConnectionState.PLAY);
      if (previous != null) previous.close();
      gg.tame.conduit.metrics.ConduitMetrics.current().serverSwitch(System.nanoTime() - started);
      if (targetView != null) {
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerConnectedEvent(this, sourceView, targetView));
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchEvent(this, sourceView, targetView));
      }
    } catch (Exception exception) {
      awaitingBackendJoinGame.release();
      conduitOwnsReconfiguration = false;
      switchingTarget = null;
      switchQueue.clear();
      // Only release the prepared translation if it never became the session's.
      if (pending != null && pending.translator() != translator) pending.close();
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
  /** Adds Conduit's matching command names to the backend's reply to a pre-1.13 command-name Tab. */
  private byte[] withProxyCommandNames(byte[] packet) {
    String typed = legacyCommandCompletion;
    if (typed == null || clientState.state() != ConnectionState.PLAY) return packet;
    try {
      if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_TAB_COMPLETE)) {
        return packet;
      }
      legacyCommandCompletion = null;
      List<String> backendMatches = PlayPackets.legacyTabMatches(packet);
      java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(backendMatches);
      for (String name : commands.tabComplete(this, typed)) merged.add("/" + name);
      return merged.size() == backendMatches.size() ? packet : PlayPackets.tabComplete(protocol, -1, 0, 0, List.copyOf(merged));
    } catch (IOException unreadable) {
      // The backend's reply goes through untouched rather than the session over a completion list.
      return packet;
    }
  }
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
    outbound = withProxyCommandNames(outbound);
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
      String where = lifecycle.get() == SessionLifecycle.SWITCHING ? "switch" : "steady";
      gg.tame.conduit.protocol.ProfileTrace.clientbound(where, protocol, clientState.state(), outbound, profile());
    }
    gg.tame.conduit.protocol.ClientboundDump.record(outbound);
    if (flush) client.write(outbound);
    else {
      client.writeUnflushed(outbound);
    }
    awaitingBackendJoinGame.written(outbound);
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
    new Translation(translationSupport, backendProtocol, backendDefinition, translator).close();
    synchronized (lock) { lock.notifyAll(); }
    if (players != null) players.remove(this);
    BackendConnection current = backend;
    if (current != null) current.close();
    BackendConnection switching = switchingTarget;
    if (switching != null) switching.close();
    client.close();
  }
}

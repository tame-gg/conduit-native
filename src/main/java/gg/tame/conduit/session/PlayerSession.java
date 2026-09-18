// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
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
  /** Channels the client announced. Registration is per connection, so a new backend needs telling. */
  private final gg.tame.conduit.modded.RegisteredChannels registeredChannels = new gg.tame.conduit.modded.RegisteredChannels();
  private final Object lock = new Object();
  private final AtomicReference<SessionLifecycle> lifecycle = new AtomicReference<>(SessionLifecycle.CONNECTING);
  private final java.util.concurrent.atomic.AtomicBoolean switchInFlight = new java.util.concurrent.atomic.AtomicBoolean();
  /** PlayerDisconnectEvent has fired; see {@link #leave}. */
  private final java.util.concurrent.atomic.AtomicBoolean left = new java.util.concurrent.atomic.AtomicBoolean();
  /** A newer login of the same player ended this session; see {@link #displace}. */
  private volatile boolean displaced;
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
  /**
   * Every backend this session has opened and not yet closed.
   *
   * <p>{@link #close()} used to close the two fields above, read one after the other and outside
   * the lock a switch commits under. A switch that installed its new backend between those two
   * reads left it open on a session that had already ended: the socket, and the player on the far
   * side of it, survived the disconnect. Closing what the session actually holds cannot miss it,
   * because a switch adds its connection before the commit that could hide it.
   */
  private final java.util.Set<BackendConnection> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile boolean closed;
  private volatile boolean expectClientLoginAck;
  /** The backend's login asked the client something, so an answer may still be in flight. */
  private volatile boolean loginQueriesRelayed;
  /**
   * What a client with no command tree last sent the backend to complete, until the backend's reply
   * has been through {@link #withLegacyTabCompletions}. A reply to a command name gets Conduit's
   * matching commands added: it is the only place such a client learns command names from, and the
   * backend knows none of Conduit's. Every such reply then goes past PlayerTabCompleteEvent.
   */
  private volatile String legacyTabRequest;
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
  private final KeepAliveClock keepAlive;
  private volatile boolean commandsDeclared;
  private static final int MAX_DEFERRED_PLAY = 512;
  /** Set once Conduit has synthesised finish_configuration for a non-configuration backend. */
  private volatile boolean configurationFinishSynthesized;
  private final List<byte[]> deferredPlay = new java.util.ArrayList<>();
  private boolean playLoginSent;
  /** Guarded by {@code lock}: a flush of {@link #deferredPlay} is writing to the client. */
  private boolean flushingDeferredPlay;
  private boolean needSelfPlayerInfo = true;
  /** The UUID the client was told is its own, by the Login Success that reached it. */
  private volatile java.util.UUID clientUuid;
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
  /** What the proxy shows this client itself: titles, action bar, boss bars, tab-list header and entries. */
  private final ClientDisplay display;
  /** The resource packs the client is offered through the proxy, the proxy's own and its server's. */
  private final ClientResourcePacks resourcePacks;
  /** What the client last sent on the brand channel. */
  private volatile String clientBrand;
  public PlayerSession(ConduitConfiguration configuration, PacketTransport client, ProtocolDefinition protocol, ProtocolSession clientState,
      LoginPipeline loginPipeline, PlayerInfoForwarder forwarder, gg.tame.conduit.runtime.ConduitRuntime runtime,
      Handshake handshake, byte[] originalHandshake, byte[] originalLoginStart, InetAddress address) {
    this.configuration = configuration; this.client = client; this.protocol = protocol; this.clientState = clientState;
    this.awaitingBackendJoinGame = new SwitchJoinGate(protocol);
    this.keepAlive = new KeepAliveClock(protocol);
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
    this.display = new ClientDisplay(this, protocol, this::writeClient);
    this.resourcePacks = new ClientResourcePacks(this, protocol, this::writeClient, event -> runtime.events().fire(event));
  }
  public ModLoaderFamily modLoaderFamily() { return modClassifier.family(); }
  public HandshakeClassifier modClassifier() { return modClassifier; }
  public TranslationSupport translationSupport() { return translationSupport; }
  public ProtocolDefinition backendDefinition() { return backendDefinition; }
  public PlayerProfile profile() { return AuthenticatedPlayerProfile.require(loginPipeline.player()); }
  /** Same object as {@link #profile()}; the session never recreates identity on {@code /server}. */
  public PlayerProfile authenticatedProfile() { return profile(); }
  /** The account that logged in; {@link #profile()} differs only when a GameProfileRequestEvent listener replaced it. */
  public PlayerProfile accountProfile() { return loginPipeline.account(); }
  @Override public gg.tame.conduit.api.player.GameProfile gameProfile() {
    PlayerProfile profile = profile();
    return new gg.tame.conduit.api.player.GameProfile(profile.uniqueId(), profile.username(), profile.properties().stream()
        .map(property -> new gg.tame.conduit.api.player.GameProfile.Property(property.name(), property.value(), property.signature())).toList());
  }
  @Override public boolean transferred() { return handshake.nextState() == Handshake.TRANSFER; }
  @Override public boolean transferToHost(String host, int port) {
    if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    return transfer(host, port, null);
  }
  /**
   * Sends a Transfer once PlayerTransferEvent allows it. {@code original} is a backend's own packet,
   * written as it came while nobody changed where it points; null for a plugin's transfer.
   */
  private boolean transfer(String host, int port, byte[] original) {
    ConnectionState state = clientState.state();
    PacketKind kind = state == ConnectionState.PLAY ? PacketKind.PLAY_TRANSFER
        : state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_TRANSFER : null;
    if (closed || kind == null || !protocol.defines(state, PacketDirection.SERVER_TO_CLIENT, kind)) return false;
    var event = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerTransferEvent(this, host, port, original != null));
    if (event.cancelled()) return false;
    try {
      if (original != null && event.host().equals(host) && event.port() == port) {
        writeClient(original);
      } else {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, protocol.id(state, PacketDirection.SERVER_TO_CLIENT, kind));
          gg.tame.conduit.protocol.MinecraftOutput.string(output, event.host());
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, event.port());
        }
        writeClient(bytes.toByteArray());
      }
      return true;
    } catch (IOException failed) {
      return false;
    }
  }
  /**
   * A backend's Transfer, which plugins hear of as they hear of their own; true when {@code packet} was
   * one, and it has been sent on, changed or dropped.
   */
  private boolean relayedTransfer(byte[] packet) throws IOException {
    ConnectionState state = clientState.state();
    PacketKind kind = state == ConnectionState.PLAY ? PacketKind.PLAY_TRANSFER
        : state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_TRANSFER : null;
    if (kind == null || !protocol.is(state, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), kind)) return false;
    String host;
    int port;
    try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet))) {
      gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      host = gg.tame.conduit.protocol.MinecraftInput.string(input, 32767);
      port = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
    } catch (IOException unreadable) {
      return false;
    }
    transfer(host, port, packet);
    return true;
  }
  @Override public java.util.UUID uniqueId() { return profile().uniqueId(); }
  @Override public boolean authenticated() { return profile().authenticated(); }
  @Override public String connectionState() { return clientState.state().name(); }
  @Override public OptionalServer currentServer() {
    BackendConnection current = backend;
    if (current == null) return new OptionalServerView(null);
    return new OptionalServerView(runtime.registered(current.server().name()).orElse(null));
  }
  @Override public int protocolVersion() { return clientProtocol; }
  @Override public InetAddress remoteAddress() { return address; }
  @Override public java.net.InetSocketAddress remoteSocketAddress() {
    return new java.net.InetSocketAddress(address, configuration.forwardedPlayerAddress().isPresent() ? 0 : client.remotePort());
  }
  @Override public java.net.InetSocketAddress virtualHost() {
    String host = handshake.requestedHost();
    int marker = host.indexOf('\0');
    return java.net.InetSocketAddress.createUnresolved(marker < 0 ? host : host.substring(0, marker), handshake.requestedPort());
  }
  @Override public java.util.concurrent.CompletableFuture<Boolean> connect(gg.tame.conduit.api.server.RegisteredServer server) {
    return connectWithResult(server).thenApply(gg.tame.conduit.api.player.ConnectResult::successful);
  }
  /**
   * Silent, unlike transferTo: the plugin that asked decides what the player hears. It used to run
   * on the common fork-join pool, whose few threads a handful of switches (each up to its whole
   * budget on a slow backend) could hold between them; each switch now gets a socket thread.
   */
  @Override public java.util.concurrent.CompletableFuture<gg.tame.conduit.api.player.ConnectResult> connectWithResult(
      gg.tame.conduit.api.server.RegisteredServer server) {
    BackendServer target = server == null ? null : selector.registry().get(server.getName()).orElse(null);
    if (target == null) {
      return java.util.concurrent.CompletableFuture.completedFuture(new gg.tame.conduit.api.player.ConnectResult(
          gg.tame.conduit.api.player.ConnectResult.Status.FAILED, "unknown server " + (server == null ? "" : server.getName())));
    }
    if (target.name().equalsIgnoreCase(currentBackend())) {
      return java.util.concurrent.CompletableFuture.completedFuture(new gg.tame.conduit.api.player.ConnectResult(
          gg.tame.conduit.api.player.ConnectResult.Status.ALREADY_CONNECTED, ""));
    }
    if (closed) {
      return java.util.concurrent.CompletableFuture.completedFuture(new gg.tame.conduit.api.player.ConnectResult(
          gg.tame.conduit.api.player.ConnectResult.Status.FAILED, "the player has left"));
    }
    var result = new java.util.concurrent.CompletableFuture<gg.tame.conduit.api.player.ConnectResult>();
    gg.tame.conduit.network.SocketThreads.start(() -> {
      try { result.complete(runSwitch(target)); }
      catch (RuntimeException | Error failure) {
        result.complete(new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.FAILED, String.valueOf(failure)));
        throw failure;
      }
    });
    return result;
  }
  @Override public void disconnect(String reason) {
    disconnect(Text.of(reason == null ? "" : reason));
  }
  /**
   * Written as the disconnect packet of whatever state the client is in. In Play this used to be a
   * system chat line followed by a closed socket, so a kicked player -- including every player a
   * shutdown kicked -- saw "Connection lost" and the reason, if at all, scrolled past in chat. In
   * Configuration the Play chat id was some other packet the client could not read; in Login, a
   * plugin refusing a player got nothing it could show.
   */
  @Override public void disconnect(Text reason) {
    Text shown = reason == null ? Text.empty() : reason;
    disconnectWith((output, nbt) -> gg.tame.conduit.text.TextCodec.write(output, shown, clientProtocol, nbt));
  }
  /**
   * Disconnects with a reason a backend wrote, as the JSON it came in. Going through Text would lose
   * what Text cannot hold: a vanilla server's refusals are translatable, and would reach the player
   * as bare keys such as multiplayer.disconnect.not_whitelisted.
   */
  private void disconnectJson(String json) {
    disconnectWith((output, nbt) -> {
      if (nbt) gg.tame.conduit.protocol.text.ComponentCodec.jsonToNbt(output, json);
      else gg.tame.conduit.protocol.MinecraftOutput.string(output, json);
    });
  }
  private interface Reason { void write(java.io.DataOutputStream output, boolean nbt) throws IOException; }
  private void disconnectWith(Reason reason) {
    try {
      ConnectionState state = clientState.state();
      PacketKind kind = switch (state) {
        case LOGIN -> PacketKind.LOGIN_DISCONNECT;
        case CONFIGURATION -> PacketKind.CONFIGURATION_DISCONNECT;
        case PLAY -> PacketKind.PLAY_DISCONNECT;
        default -> null;
      };
      if (kind != null && protocol.defines(state, PacketDirection.SERVER_TO_CLIENT, kind)) {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, protocol.id(state, PacketDirection.SERVER_TO_CLIENT, kind));
          // Login's reason is JSON text in every version; the other two became NBT with 1.20.3.
          boolean nbt = state != ConnectionState.LOGIN && gg.tame.conduit.protocol.ProtocolEras.textComponentNbt(clientProtocol);
          reason.write(output, nbt);
        }
        writeClient(bytes.toByteArray());
      }
    } catch (IOException ignored) { }
    close();
  }
  @Override public boolean sendPluginMessageToServer(String channel, byte[] data) {
    BackendConnection current = backend;
    ConnectionState state = clientState.state();
    if (current == null || closed || lifecycle.get() != SessionLifecycle.CONNECTED) return false;
    if (state != ConnectionState.PLAY && state != ConnectionState.CONFIGURATION) return false;
    PacketKind kind = state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE;
    if (!protocol.defines(state, PacketDirection.CLIENT_TO_SERVER, kind)) return false;
    try {
      // Built in the client's dialect and sent the way the client's own packets go, so the
      // translator, when there is one, gives the backend the packet in its own.
      byte[] packet = new gg.tame.conduit.protocol.PluginMessage(channel, data).encode(protocol.id(state, PacketDirection.CLIENT_TO_SERVER, kind));
      byte[] outbound = towardBackend(state, packet);
      if (outbound == null) return false;
      current.writeUncompressed(outbound);
      flushTranslatorExtras(current);
      return true;
    } catch (IOException | RuntimeException failed) {
      return false;
    }
  }
  /** A pre-1.19 chat line toward the backend, as if the client had typed it. */
  private boolean sendChatLineToServer(String line) {
    BackendConnection current = backend;
    if (current == null || closed || clientState.state() != ConnectionState.PLAY) return false;
    try {
      var bytes = new java.io.ByteArrayOutputStream();
      try (var output = new java.io.DataOutputStream(bytes)) {
        gg.tame.conduit.protocol.MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND));
        gg.tame.conduit.protocol.MinecraftOutput.string(output, line);
      }
      byte[] outbound = towardBackend(ConnectionState.PLAY, bytes.toByteArray());
      if (outbound == null) return false;
      current.writeUncompressed(outbound);
      flushTranslatorExtras(current);
      return true;
    } catch (IOException | RuntimeException failed) {
      return false;
    }
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
        registeredChannels.observe(decoded.channel(), decoded.data());
        var outcome = runtime.security().channelGuard().inspect(decoded.channel(), username());
        if (outcome == gg.tame.conduit.security.ChannelGuard.Outcome.DROP) return false;
        if (outcome == gg.tame.conduit.security.ChannelGuard.Outcome.KICK) {
          disconnect("Blocked plugin channel.");
          return false;
        }
        if ("minecraft:brand".equalsIgnoreCase(decoded.channel()) || "MC|Brand".equals(decoded.channel())) {
          try {
            String brand = decoded.brandText();
            modClassifier.observeBrand(brand);
            clientBrand = brand;
            runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerClientBrandEvent(this, brand));
          } catch (IOException ignored) { }
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
    // A login listener that kicked the player instead of denying the event closed the session, and
    // the proxy went on to dial a backend for a client that was already gone.
    BackendConnection initial = null;
    try {
      if (!closed) initial = connectInitial();
    } catch (IOException failed) {
      // Every candidate refused, was unreachable, or had its connection cancelled by a plugin. The
      // socket used to close with nothing written, and the player saw "Connection lost" and no reason.
      // A server that refused them had a reason of its own, and that is the more useful one to show.
      // A refusal in configuration has been shown already, as its kicked event decided.
      if (!closed) {
        if (failed instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused) disconnectJson(refused.reasonJson());
        else disconnect("Could not connect you to a server. Please try again later.");
      }
      throw failed;
    } finally {
      // Let in, never joined. Listeners that set something up for this player in PlayerLoginEvent
      // heard nothing more, and whatever they kept for the player was kept for good.
      if (initial == null) leave(gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus.PRE_SERVER_JOIN);
    }
    if (initial == null) return;
    // Login is over. Both links were read under a deadline until here, because a client or a
    // backend that stops halfway through a login parks this thread with two sockets, a connection
    // slot and a throttle lease held; from here on either end may sit quiet as long as it likes.
    client.setReadTimeoutMillis(0);
    initial.setReadTimeoutMillis(0);
    synchronized (lock) { backend = initial; lifecycle.set(SessionLifecycle.CONNECTED); lock.notifyAll(); }
    players.add(this);
    gg.tame.conduit.metrics.ConduitMetrics.current().playerJoined();
    long joined = System.nanoTime();
    runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerPostLoginEvent(this));
    runtime.registered(initial.server().name()).ifPresent(first -> runtime.events().fire(
        new gg.tame.conduit.api.event.player.PlayerServerConnectedEvent(this, java.util.Optional.empty(), first)));
    Thread backendReader = gg.tame.conduit.network.SocketThreads.start(this::readBackend);
    try { readClient(); }
    finally {
      closed = true;
      players.remove(this);
      gg.tame.conduit.metrics.ConduitMetrics.current().playerLeft(System.nanoTime() - joined);
      leave(gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
      backendReader.interrupt();
      close();
    }
  }
  /**
   * Tells plugins the player is gone: once, the first time any path that ends the session gets here,
   * so that everything set up for the player in PlayerSetupEvent is released exactly once. Only then is
   * the player's identity free for another login, so plugins hear this session end before they hear
   * of the next one.
   */
  public void leave(gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus status) {
    if (!left.compareAndSet(false, true)) return;
    try {
      if (displaced && status != gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
        status = gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus.CONFLICTING_LOGIN;
      }
      runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerDisconnectEvent(this, status));
    } finally {
      players.release(this);
    }
  }
  /** A newer login of this player is taking over (kick-existing-players): this session ends. */
  public void displace() {
    displaced = true;
    disconnect(Text.of("You logged in from another location."));
  }
  /** Whether the session has ended or is ending; its PlayerDisconnectEvent may still be on its way. */
  public boolean closed() { return closed; }
  private BackendConnection track(BackendConnection connection) {
    open.add(connection);
    return connection;
  }

  /** Closes a backend and forgets it; closing twice is harmless, forgetting to is not. */
  private void discard(BackendConnection connection) {
    if (connection == null) return;
    open.remove(connection);
    connection.close();
  }

  private BackendConnection connectInitial() throws IOException {
    List<BackendServer> candidates = new java.util.ArrayList<>(selector.candidatesFor(protocol.version().number(), modClassifier.family(), false));
    var choice = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerInitialServerEvent(this,
        candidates.isEmpty() ? null : runtime.registered(candidates.getFirst().name()).orElse(null)));
    choice.initialServer().flatMap(chosen -> selector.registry().get(chosen.getName())).ifPresent(chosen -> {
      candidates.removeIf(other -> other.name().equalsIgnoreCase(chosen.name()));
      candidates.addFirst(chosen);
    });
    IOException last = null;
    // What the disconnect screen says if a server refused the player and no later one took them.
    String refusal = null;
    for (int index = 0; index < candidates.size(); index++) {
      BackendServer server = candidates.get(index);
      var targetView = runtime.registered(server.name()).orElse(null);
      if (targetView != null) {
        var connect = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerConnectEvent(this, java.util.Optional.empty(), targetView));
        if (connect.cancelled()) { last = new IOException("connection to " + server.name() + " was cancelled"); continue; }
        server = selector.registry().get(connect.target().getName()).orElse(null);
        if (server == null) { last = new IOException("redirected to unknown server " + connect.target().getName()); continue; }
        targetView = runtime.registered(server.name()).orElse(connect.target());
      }
      Socket socket = null;
      BackendConnection connection = null;
      try {
        prepareTranslation(server);
        socket = selector.open(server);
        writeBackendHandshake(socket, server);
        MinecraftFrames.write(socket.getOutputStream(), LoginStart.encode(profile(), backendDefinition));
        connection = track(new BackendConnection(server, socket, backendDefinition, forwarder, profile(), address, configuration, false));
        // Per read, as the switch path already bounds its own login: a backend that accepts the
        // connection and then says nothing held the join thread, the client's socket and this one
        // open for as long as it cared to. A backend that keeps sending never sees this.
        connection.setReadTimeoutMillis(SWITCH_BUDGET_MS);
        // The client is still in LOGIN, so it -- and only it -- can answer a login query Conduit
        // has no answer for. Forge 1.13-1.20.1 asks for its mod list on fml:loginwrapper.
        connection.login().allowClientLoginQueries();
        // Every pair and every forwarding mode, as a switch already does. This is where the backend's
        // Set Compression is consumed: the client's link is never compressed. Left to the relay, a
        // DIRECT 1.20.4 session with forwarding "none" handed that packet to the client, and Conduit
        // then read both sockets in the wrong format until the backend's Finish Configuration parsed
        // as a plugin message and ended the session.
        completeBackendLogin(connection, true);
        return connection;
      } catch (IOException exception) {
        // The candidate that failed takes its socket with it. Left open, every backend that
        // refused a login mid-exchange cost the proxy a file descriptor and the backend a
        // half-finished connection, for as long as the process ran.
        discard(connection);
        if (connection == null && socket != null) try { socket.close(); } catch (IOException ignored) { }
        last = exception;
        System.err.println("Backend unavailable: " + server.name() + " (" + exception.getMessage() + ")");
        // A client with no Configuration phase is sent this server's Login Success before Conduit
        // runs the server's configuration for it. Once it has one, no other server can be tried: its
        // Login Success would be a second one, which such a client reads as a Play packet.
        boolean committed = clientState.state() != ConnectionState.LOGIN;
        if (targetView == null) {
          if (committed) throw exception;
          continue;
        }
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent(this, java.util.Optional.empty(), targetView,
            exception.getMessage() == null ? "connection failed" : exception.getMessage()));
        if (committed) {
          if (exception instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused) refusedInConfiguration(refused, server);
          throw exception;
        }
        if (exception instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused) {
          Refusal decided = refused(refused, targetView);
          // Disconnect ends the walk here; Redirect and Notify let it go on.
          if (decided.result() instanceof KickResult.Disconnect) throw new gg.tame.conduit.login.BackendLoginPipeline.Refused(decided.json());
          if (decided.result() instanceof KickResult.Redirect redirect) tryNext(candidates, index, redirect.server());
          refusal = decided.json();
        }
      }
    }
    if (refusal != null) throw new gg.tame.conduit.login.BackendLoginPipeline.Refused(refusal);
    throw last == null ? new IOException("all configured backends refused the connection") : last;
  }

  /**
   * A backend refused the player's login while they were being connected to it: what
   * {@link gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent} decided, the backend's
   * reason, and the reason to show if the player ends up disconnected over it.
   */
  private record Refusal(KickResult result, Text reason, String json) {}

  /**
   * A backend refused the player in its Configuration phase, after the client had left Login for it:
   * their first server, or a switch's target once the client had been moved into its configuration.
   * The client has no server left to stay on, and one configuration started on top of another's
   * half-finished one is not something it survives, so every result ends the session. The default is
   * Disconnect, showing the backend's reason exactly as it wrote it; a Redirect cannot be honoured.
   */
  private void refusedInConfiguration(gg.tame.conduit.login.BackendLoginPipeline.Refused refused, BackendServer server) {
    var view = runtime.registered(server.name()).orElse(null);
    Text reason = gg.tame.conduit.text.TextCodec.fromJson(refused.reasonJson());
    KickResult offered = new KickResult.Disconnect(reason);
    KickResult result = view == null ? offered : runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent(
        this, view, java.util.Optional.of(reason), true, offered)).result();
    switch (result) {
      case KickResult.Disconnect disconnect when disconnect != offered -> disconnect(disconnect.reason());
      case KickResult.Notify notify -> disconnect(notify.message());
      case KickResult.Redirect redirect -> {
        gg.tame.conduit.log.ConduitLog.warn("Cannot redirect " + username() + " to " + redirect.server().getName()
            + ": " + server.name() + " refused them in its configuration phase, which the client cannot leave for another server");
        disconnectJson(refused.reasonJson());
      }
      default -> disconnectJson(refused.reasonJson());
    }
  }

  /** Fires the kicked event for a refused login. Notify is the default: the caller carries on. */
  private Refusal refused(gg.tame.conduit.login.BackendLoginPipeline.Refused refused,
                          gg.tame.conduit.api.server.RegisteredServer server) {
    Text reason = gg.tame.conduit.text.TextCodec.fromJson(refused.reasonJson());
    var tell = new KickResult.Notify(reason);
    KickResult result = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent(
        this, server, java.util.Optional.of(reason), true, tell)).result();
    // The backend's own component while nobody changed it, because Text would lose its translations.
    String json = switch (result) {
      case KickResult.Disconnect disconnect -> gg.tame.conduit.text.TextCodec.toJson(disconnect.reason(), clientProtocol);
      case KickResult.Notify notify when notify != tell -> gg.tame.conduit.text.TextCodec.toJson(notify.message(), clientProtocol);
      default -> refused.reasonJson();
    };
    return new Refusal(result, reason, json);
  }

  /**
   * Puts {@code server} next in a walk of candidates, for a Redirect from one that refused the player,
   * unless the walk has already tried it. A listener that redirects every refusal to the lobby met a
   * lobby that refused the player, and the walk logged them in to it again after each refusal: 45,000
   * logins in fifteen seconds from one joining player, until the client gave up.
   */
  private void tryNext(List<BackendServer> order, int index, gg.tame.conduit.api.server.RegisteredServer server) {
    selector.registry().get(server.getName()).ifPresent(next -> {
      if (order.subList(0, index + 1).stream().anyMatch(tried -> tried.name().equalsIgnoreCase(next.name()))) return;
      order.subList(index + 1, order.size()).removeIf(other -> other.name().equalsIgnoreCase(next.name()));
      order.add(index + 1, next);
    });
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
      // A client that arrived by transfer is logged in to the backend as any other: the backend is
      // Conduit's, not the server that sent the client, and a vanilla one refuses transfers by default.
      MinecraftFrames.write(socket.getOutputStream(), transferred()
          ? new Handshake(handshake.protocolVersion(), handshake.requestedHost(), handshake.requestedPort(), 2).encode()
          : originalHandshake);
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
  /** Client poll while a login-query pump runs. Short, so closing it joins promptly. */
  private static final int LOGIN_QUERY_POLL_MS = 100;

  /**
   * Answers the backend's login queries from the client while the backend keeps asking.
   *
   * <p>A thread, because the exchange is not a conversation. A real NeoForge 20.2.93 server sent
   * Login Plugin Requests 0x00 through 0x14 in under a second without reading one reply, and FML's
   * client handler answers the batch rather than each request in turn. Reading the client between
   * requests, on the thread that reads the backend, deadlocks both ends: it did, and the login died
   * on a read deadline with query 0x00 delivered and twenty more unsent.
   */
  private final class LoginQueryPump {
    private final Thread thread;
    private volatile boolean running = true;
    private volatile IOException failure;

    LoginQueryPump(BackendConnection connection) throws IOException {
      client.setReadTimeoutMillis(LOGIN_QUERY_POLL_MS);
      this.thread = gg.tame.conduit.network.SocketThreads.start(() -> {
        while (running) {
          try {
            connection.writeUncompressed(
                connection.login().clientLoginQueryResponse(client.read(configuration.maxFrameBytes())));
          } catch (java.net.SocketTimeoutException poll) {
            // Nothing from the client yet. The backend's own deadline ends a login that stalls.
          } catch (IOException exception) {
            failure = exception;
            return;
          }
        }
      });
    }

    void close() throws IOException {
      running = false;
      try { thread.join(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      client.setReadTimeoutMillis(runtime.security().botFilter().settings().handshakeTimeoutMs());
    }

    void rethrow() throws IOException { if (failure != null) throw failure; }
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
    LoginQueryPump pump = null;
    try {
      while (connection.state() == ConnectionState.LOGIN) {
        byte[] packet = connection.readUncompressed();
        PacketTrace.packet("backend-login", connection.state(), PacketDirection.SERVER_TO_CLIENT, backendDefinition, packet);
        byte[] response = connection.login().onBackendPacket(packet, configuration.maxFrameBytes());
        // The backend has left LOGIN. What the client sends next is its Login Acknowledged, and
        // that is this loop's -- so the pump stops before Login Success ever reaches the client.
        if (connection.state() != ConnectionState.LOGIN && pump != null) {
          pump.close(); pump.rethrow(); pump = null;
        }
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
        if (pump == null && connection.login().awaitingLoginQuery()) {
          pump = new LoginQueryPump(connection);
          loginQueriesRelayed = true;
        }
        if (pump != null) pump.rethrow();
        // Via answers Login Success on the old client's behalf, and it has to be sent while the
        // backend is still reading Login. Leaving it queued until the next flush delivers a
        // one-byte Login Acknowledged after the backend reached Play, where that same id is a
        // packet with a body — which is how a real 1.20.4 server ends up reporting a decoder
        // underflow a whole join later.
        if (translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator) {
          viaLoginPacketsToBackend += flushTranslatorExtras(translator, connection);
        }
      }
    } finally {
      if (pump != null) try { pump.close(); } catch (IOException ignored) { }
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
        keepAlive.read(clientState.state(), packet);
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
    // An answer about one of the proxy's own packs: the server never offered it and must not hear of it.
    if (resourcePacks.fromClient(clientState.state(), packet)) return true;
    boolean loginAck = expectClientLoginAck
        && protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_ACKNOWLEDGED)
        && packet.length <= 2;
    if (expectClientLoginAck && loginQueriesRelayed && !loginAck) {
      // Conduit reads the client as configuring from the moment it writes Login Success, but the
      // client is still logging in until it acknowledges that. When the login asked the client
      // anything, what comes first may be an answer still in flight, whose id 0x02 is Finish
      // Configuration once read as Configuration. It went to the backend as one, and the backend's
      // Play followed to a client that had not finished configuring. The backend has left Login, so
      // there is nobody to answer.
      gg.tame.conduit.log.ConduitLog.warn("Dropped Login packet id=0x" + Integer.toHexString(id) + " from " + username()
          + " after the backend finished its login");
      return true;
    }
    if (loginAck) {
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
        // Plain chat, on the one packet that carries it before 1.19. The event used to fire for
        // commands instead, as "/name", and plain chat never raised it at all.
        var chat = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerChatEvent(this, command));
        if (chat.cancelled()) return true;
        // A rewrite goes on as a chat line of its own; the client's packet is withheld.
        return !chat.message().equals(command) && sendChatLineToServer(chat.message());
      }
      var execute = new gg.tame.conduit.api.event.command.CommandExecuteEvent(this, command);
      runtime.events().fire(execute);
      if (execute.cancelled()) return true;
      String effective = execute.command();
      if (!execute.forwardsToServer()) {
        // The client's own slash is gone already; dispatch strips one more, so it gets one back.
        // Without it "//lpv" ran "lpv", where Velocity runs the command registered as "/lpv", and a
        // backend's "//wand" was taken by any proxy command that happened to be called "wand".
        try { if (commands.dispatch(this, "/" + effective)) return true; }
        catch (RuntimeException exception) {
          gg.tame.conduit.log.ConduitLog.error("command failed", exception);
          return true;
        }
      }
      if (effective.equals(command)) return false;
      // Rewritten, and on its way to the backend. Before 1.19 the packet is the line alone; after,
      // the command may carry signatures over its arguments, which a rewrite would break.
      if (protocol.capabilities().legacyPlayChat() && sendChatLineToServer("/" + effective)) return true;
      gg.tame.conduit.log.ConduitLog.warn("A plugin rewrote /" + command + " for a client whose commands may be signed; the backend got it unchanged");
      return false;
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_TAB_COMPLETE_REQUEST)) {
      PlayPackets.TabRequest request = PlayPackets.tabRequest(protocol, packet);
      // Only this request's reply may take Conduit's names. A reply can fail to come at all -- Via
      // dropped a 1.20.4 backend's empty one for a real 1.12.2 client -- and a name left pending then
      // went into the next reply, Conduit's own list of servers, which Tab turned into "/server /server".
      legacyTabRequest = null;
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
      if (!protocol.capabilities().commandTree()) legacyTabRequest = request.text();
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
      try {
        KnownPacksValidator.validate(PlayPackets.body(packet), runtime.modded().knownPacksLimit());
      } catch (KnownPacksValidator.TooManyPacks tooMany) {
        // A heavily modded 1.20.5+ client declares a pack per mod. Refused silently, the player saw
        // only a dropped connection and nothing in the log said which setting to raise.
        gg.tame.conduit.log.ConduitLog.warn(username() + " declared " + tooMany.count + " known packs, over the limit of "
            + tooMany.limit + "; raise [modded] known-packs-limit to let this client in.");
        disconnect("Your client has more resource packs (" + tooMany.count + ") than this network allows (" + tooMany.limit + ").");
        return true;
      } catch (IOException malformed) {
        gg.tame.conduit.log.ConduitLog.warn(username() + " sent an invalid known-packs list: " + malformed.getMessage());
        disconnect("Your client sent an invalid resource pack list.");
        return true;
      }
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

  @Override public long ping() { return keepAlive.latencyMillis(); }
  /** Read from the cached Client Information, in the client's own layout, each time it is asked for. */
  @Override public java.util.Optional<gg.tame.conduit.api.player.ClientSettings> settings() {
    byte[] information = clientInformation;
    if (information == null) return java.util.Optional.empty();
    try { return java.util.Optional.of(gg.tame.conduit.protocol.ClientSettingsCodec.decode(clientProtocol, information)); }
    catch (IOException | RuntimeException unreadable) { return java.util.Optional.empty(); }
  }
  @Override public java.util.Optional<String> clientBrand() { return java.util.Optional.ofNullable(clientBrand); }
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
    runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerSettingsChangedEvent(this));
  }
  /** Tells a backend the channels the client announced; registration is per connection, not per player. */
  private void replayRegisteredChannels(BackendConnection target, ConnectionState state, ProtocolDefinition definition) {
    if (target == null || (state == ConnectionState.CONFIGURATION && !definition.hasConfiguration())) return;
    try {
      var packet = registeredChannels.replay(definition, state);
      if (packet.isPresent()) target.writeUncompressed(packet.get());
    } catch (IOException exception) {
      System.err.println("Could not replay registered channels to " + target.server().name() + ": " + exception.getMessage());
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
        if (relayedTransfer(translated)) continue;
        // Everything below this point compensates for gaps in Conduit's own translators: a brand
        // the client never gets told, a command tree that has to be merged, a Configuration phase
        // one side of the pair does not have, a Play stream that must wait for the other side to
        // be ready. Via closes those gaps itself, and closing them twice is worse than not at all
        // — a second brand, a duplicated player entry or a synthesised transition the client has
        // already made is what a real 1.13 client drops the connection over. When Via is the
        // engine its output goes to the client as it stands.
        if (viaEngine()) {
          if (isPlayDisconnect(translated)) {
            if (kickedWhilePlaying(current, translated)) continue;
            return;
          }
          writeClient(translated, true);
          flushTranslatorExtras(current);
          resumeAfterSwitchedJoinGame(current);
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
        if (isPlayDisconnect(translated)) {
          if (kickedWhilePlaying(current, outbound)) continue;
          return;
        }
        writeClient(outbound, true);
        flushTranslatorExtras(current);
        resumeAfterSwitchedJoinGame(current);
        if (clientState.state() == ConnectionState.PLAY && isPlayLogin(translated)) {
          emitSelfPlayerInfoIfNeeded();
        }
      } catch (gg.tame.conduit.login.BackendLoginPipeline.Refused refused) {
        // Only a first server's configuration is read here -- a switch reads its target's itself --
        // so the player is still being connected to it.
        refusedInConfiguration(refused, current.server());
        return;
      } catch (IOException exception) {
        if (closed || lifecycle.get() != SessionLifecycle.CONNECTED) return;
        ProtocolTrace.note("backend I/O: " + exception.getMessage());
        handleBackendLoss(current);
      }
    }
  }
  /**
   * The backend the player is on sent them a Play Disconnect: what used to be relayed as it was,
   * ending the session, now goes past {@link gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent}
   * first. Runs on the backend reader, outside {@code lock}. True when the player was moved to
   * another server and the reader carries on with it.
   */
  private boolean kickedWhilePlaying(BackendConnection kicker, byte[] disconnect) throws IOException {
    var server = runtime.registered(kicker.server().name()).orElse(null);
    synchronized (lock) {
      // A switch took the player off this backend between the read and here; its kick is moot.
      if (backend != kicker || lifecycle.get() != SessionLifecycle.CONNECTED) return true;
      // Nothing else may commit a switch while listeners decide, and the client's packets stop
      // going to a backend that is closing.
      if (server != null) lifecycle.set(SessionLifecycle.SWITCHING);
      lock.notifyAll();
    }
    var stay = new KickResult.Disconnect(Text.empty());
    KickResult result = stay;
    if (server != null) {
      java.util.Optional<Text> reason = playDisconnectReason(disconnect);
      stay = new KickResult.Disconnect(reason.orElse(Text.empty()));
      result = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent(
          this, server, reason, false, stay)).result();
    }
    // A redirect target that refuses the player has a kicked event of its own, decided as a fallback's
    // is: its Disconnect ends it with that reason, its Redirect names the next server, and Notify
    // gives up with the first server's reason. Its decision used to be dropped, whatever it said.
    String refusedWith = null;
    if (result instanceof KickResult.Redirect) discard(kicker);
    for (int hop = 0; result instanceof KickResult.Redirect redirect; hop++) {
      try {
        // ponytail: a fixed hop limit stops two listeners redirecting to each other forever.
        if (hop == MAX_KICK_REDIRECTS) throw new IOException("redirected " + hop + " times");
        BackendServer target = selector.registry().get(redirect.server().getName())
            .orElseThrow(() -> new IOException("unknown server " + redirect.server().getName()));
        switchTo(target, true);
        redirect.message().ifPresent(this::sendMessage);
        return true;
      } catch (Exception failed) {
        gg.tame.conduit.log.ConduitLog.warn("Redirect of kicked " + username() + " to " + redirect.server().getName()
            + " failed: " + failed.getMessage());
        // A target that refused in its configuration phase has already ended the session its own way.
        if (closed) return false;
        result = stay;
        if (failed instanceof RefusedSwitch refused) {
          switch (refused.refusal.result()) {
            case KickResult.Redirect next -> result = next;
            case KickResult.Disconnect ended -> refusedWith = refused.refusal.json();
            case KickResult.Notify told -> { }
          }
        }
      }
    }
    // Notify has nowhere to keep the player: the server they were on is the one that kicked them.
    if (refusedWith != null) disconnectJson(refusedWith);
    else if (result instanceof KickResult.Notify notify) disconnect(notify.message());
    else if (result != stay) disconnect(((KickResult.Disconnect) result).reason());
    else {
      // The backend's own packet, untouched: what the player saw before this event existed.
      try { writeClient(disconnect, true); } finally { close(); }
    }
    return false;
  }
  /** The reason in a Play Disconnect in the client's dialect: JSON text, or network NBT from 1.20.3. */
  private java.util.Optional<Text> playDisconnectReason(byte[] packet) {
    try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet))) {
      gg.tame.conduit.protocol.MinecraftInput.varInt(input);
      String json = gg.tame.conduit.protocol.ProtocolEras.textComponentNbt(clientProtocol)
          ? gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input)
          : gg.tame.conduit.protocol.MinecraftInput.string(input, configuration.maxFrameBytes());
      return java.util.Optional.of(gg.tame.conduit.text.TextCodec.fromJson(json));
    } catch (IOException | RuntimeException unreadable) {
      return java.util.Optional.empty();
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
      byte[] merged = CommandGraphs.mergeProxyCommands(protocol, packet, selector.registry().names(), commands.names(), commands.displacedBuiltIns());
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
      awaitDeferredFlush();
      if (backendState != ConnectionState.PLAY) return false;
      if (playLoginSent && clientState.state() == ConnectionState.PLAY) return false;
      if (deferredPlay.size() >= MAX_DEFERRED_PLAY) throw new IOException("deferred play queue exceeded");
      deferredPlay.add(outbound);
      return true;
    }
  }
  /**
   * Writes the Play packets held back until Join Game, Join Game first.
   *
   * <p>The writes happen outside {@code lock}. This used to write while holding it, so a client
   * that stopped reading also blocked every thread that needed the lock, including a
   * {@code close()} from a kick. Order is kept another way. Until the flush ends, anything that
   * would defer, flush or reset deferred Play waits in {@link #awaitDeferredFlush}, with the lock
   * released. Nothing can overtake the held packets, and the backend reader is still held back the
   * way the lock used to hold it.
   */
  private void flushDeferredPlay() throws IOException {
    List<byte[]> logins = new java.util.ArrayList<>();
    List<byte[]> rest = new java.util.ArrayList<>();
    synchronized (lock) {
      awaitDeferredFlush();
      if (clientState.state() != ConnectionState.PLAY) return;
      for (byte[] packet : deferredPlay) {
        if (isPlayLogin(packet)) logins.add(packet);
        else rest.add(packet);
      }
      if (!playLoginSent && logins.isEmpty()) return;
      deferredPlay.clear();
      playLoginSent = true;
      flushingDeferredPlay = true;
    }
    try {
      for (byte[] packet : logins) writeClient(packet);
      emitSelfPlayerInfoIfNeeded();
      for (byte[] packet : rest) writeClient(maybeMergeCommands(packet));
    } finally {
      synchronized (lock) { flushingDeferredPlay = false; lock.notifyAll(); }
    }
  }
  /** Called holding {@code lock}. Waits, with the lock released, for a flush of deferred Play to finish writing. */
  private void awaitDeferredFlush() throws IOException {
    while (flushingDeferredPlay) {
      if (closed) throw new IOException("session closed");
      try { lock.wait(); } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("interrupted waiting for deferred Play");
      }
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
    discard(lost);
    Set<String> failed = new HashSet<>();
    failed.add(ServerRegistry.normalize(lost.server().name()));
    sendMessage(Text.of(lost.server().name() + " is unavailable.").color(TextColor.RED));
    ConduitMetrics.current().fallbackEvent();
    List<BackendServer> order = new java.util.ArrayList<>(selector.fallback(lost.server().name(), failed, clientProtocol, modClassifier.family(), false));
    String refusal = null;
    KickResult.Redirect redirected = null;
    for (int index = 0; index < order.size(); index++) {
      // A fallback that refused the player in its configuration phase ended the session itself.
      if (closed) return;
      BackendServer server = order.get(index);
      try {
        Messages.connecting(this, server.name());
        switchTo(server, true);
        Messages.connected(this, server.name());
        if (redirected != null && redirected.server().getName().equalsIgnoreCase(server.name())) {
          redirected.message().ifPresent(this::sendMessage);
        }
        return;
      } catch (RefusedSwitch refused) {
        failed.add(ServerRegistry.normalize(server.name()));
        if (refused.refusal.result() instanceof KickResult.Disconnect) { disconnectJson(refused.refusal.json()); return; }
        if (refused.refusal.result() instanceof KickResult.Redirect redirect) {
          tryNext(order, index, redirect.server());
          redirected = redirect;
        }
        refusal = refused.refusal.json();
      }
      catch (Exception exception) { failed.add(ServerRegistry.normalize(server.name())); }
    }
    // A fallback that refused the player had a reason. With none, the socket used to close with nothing
    // written, and the player saw "Connection lost": the chat line above goes with the world it was in.
    if (refusal != null) disconnectJson(refusal);
    else disconnect(Text.of("Lost connection to " + lost.server().name() + ", and no other server could take you. Please try again later."));
  }
  public void requestSwitch(String name) { transferTo(name); }
  @Override public boolean transferTo(String name) {
    BackendServer server = selector.registry().get(name).orElse(null);
    if (server == null) return false;
    if (Thread.currentThread() == clientReader) {
      gg.tame.conduit.network.SocketThreads.start(() -> {
        if (runSwitch(server).successful()) Messages.connected(this, server.name());
        else Messages.unavailable(this, server.name());
      });
      return true;
    }
    boolean ok = runSwitch(server).successful();
    if (ok) Messages.connected(this, server.name());
    else Messages.unavailable(this, server.name());
    return ok;
  }
  /**
   * One client-requested switch at a time.
   *
   * <p>The session only becomes SWITCHING at the commit, and everything before it — opening the
   * target, logging in to it, building its translator — runs while the session is still CONNECTED.
   * So a client sending {@code /server} as fast as it can type had every one of those requests pass
   * the busy check and open a backend socket of its own, holding one connection at the target per
   * packet until each found out at the commit that another had won. Two that did reach the commit
   * were worse: the second installed its backend over the first's without closing it.
   *
   * <p>The fallback path takes no part in this. It is entered from the backend reader with the
   * session already SWITCHING and is the only thing that can save a player whose server just died.
   */
  private gg.tame.conduit.api.player.ConnectResult runSwitch(BackendServer server) {
    if (!switchInFlight.compareAndSet(false, true)) {
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.IN_PROGRESS, "another switch is running");
    }
    try {
      switchTo(server, false);
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.CONNECTED, "");
    } catch (SwitchCancelled cancelled) {
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.CANCELLED, cancelled.getMessage());
    } catch (RefusedSwitch refused) {
      ConduitMetrics.current().failedSwitch();
      // The player is still on the server they were switching from.
      switch (refused.refusal.result()) {
        case KickResult.Notify notify -> sendMessage(notify.message());
        case KickResult.Disconnect disconnect -> disconnect(disconnect.reason());
        case KickResult.Redirect redirect -> {
          try {
            switchTo(selector.registry().get(redirect.server().getName())
                .orElseThrow(() -> new IOException("unknown server " + redirect.server().getName())), false);
            redirect.message().ifPresent(this::sendMessage);
          } catch (Exception failed) {
            sendMessage(refused.refusal.reason());
          }
        }
      }
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.FAILED, refused.getMessage());
    } catch (Exception exception) {
      ConduitMetrics.current().failedSwitch();
      return new gg.tame.conduit.api.player.ConnectResult(gg.tame.conduit.api.player.ConnectResult.Status.FAILED,
          exception.getMessage() == null ? "switch failed" : exception.getMessage());
    } finally {
      switchInFlight.set(false);
    }
  }
  /** A PlayerServerConnectEvent listener said no; nothing was opened. */
  private static final class SwitchCancelled extends IOException {
    private SwitchCancelled(String server) { super("connection to " + server + " was cancelled"); }
  }
  /** The target refused the player's login; the kicked event has decided what happens next. */
  private static final class RefusedSwitch extends IOException {
    private final Refusal refusal;
    private RefusedSwitch(Refusal refusal, IOException cause) { super(cause.getMessage(), cause); this.refusal = refusal; }
  }
  private static final int SWITCH_BUDGET_MS = 4_000;
  private static final int MAX_KICK_REDIRECTS = 4;
  /**
   * Budget for each half of a translator-driven reconfiguration, measured after the switch has
   * already committed. It is separate from {@link #SWITCH_BUDGET_MS}, which covers connecting and
   * logging in to the new backend: by this point that work is done, and what is being waited on is
   * a real client loading a world it has just been handed.
   */
  private static final int RECONFIGURE_BUDGET_MS = 15_000;
  private void switchTo(BackendServer requested, boolean fallback) throws Exception {
    BackendServer server = requested;
    var targetView = runtime.registered(server.name()).orElse(null);
    var sourceView = backend == null ? java.util.Optional.<gg.tame.conduit.api.server.RegisteredServer>empty() : runtime.registered(backend.server().name());
    if (targetView != null) {
      var connect = new gg.tame.conduit.api.event.player.PlayerServerConnectEvent(this, sourceView, targetView);
      runtime.events().fire(connect);
      if (connect.cancelled()) throw new SwitchCancelled(server.name());
      if (connect.target() != targetView) {
        String redirect = connect.target().getName();
        server = selector.registry().get(redirect).orElseThrow(() -> new IOException("redirected to unknown server " + redirect));
        targetView = runtime.registered(server.name()).orElse(connect.target());
      }
    }
    long started = System.nanoTime();
    long deadline = started + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SWITCH_BUDGET_MS);
    // After the listeners, which may send the player somewhere that is up; with checks off or no
    // verdict yet the switch is tried as always.
    if (selector.knownDown(server.name())) throw new IOException(server.name() + " is unavailable");
    ensureCompatible(server);
    synchronized (lock) {
      // closed is set the moment the client's connection ends, before the lifecycle says so; a plugin
      // moving a player from their disconnect event had a backend dialled and logged in to for nobody.
      if (closed || lifecycle.get() == SessionLifecycle.CLOSED) throw new IOException("session closed");
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
      socket = selector.open(server);
      enforceDeadline(deadline, "connect");
      Handshake switchHandshake = pending.support() == TranslationSupport.TRANSLATED
          ? new Handshake(pending.backendProtocol(), handshake.requestedHost(), handshake.requestedPort(), 2)
          : handshake;
      BackendConnection.handshake(socket, switchHandshake, server, profile(), modClassifier.marker(), modClassifier.family());
      next = track(new BackendConnection(server, socket, pending.definition(), forwarder, profile(), address, configuration, true));
      next.setReadTimeoutMillis(remainingMillis(deadline));
      completeBackendLogin(next, false, pending);
      enforceDeadline(deadline, "login");
      replayClientInformation(next, ConnectionState.CONFIGURATION, pending);
      replayRegisteredChannels(next, ConnectionState.CONFIGURATION, pending.definition());

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
        synchronized (lock) { awaitDeferredFlush(); deferredPlay.clear(); playLoginSent = false; needSelfPlayerInfo = true; }
      } else if (protocol.hasConfiguration()) {
        configurationAck.clear();
        synchronized (lock) { awaitDeferredFlush(); deferredPlay.clear(); playLoginSent = false; needSelfPlayerInfo = true; }
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
      // Every read of the new backend so far was bounded by the switch budget. From here it is the
      // session's backend, which may be as quiet as it likes: left in place, that deadline read four
      // silent seconds on a limbo server as a lost backend and sent the player to the fallback.
      next.setReadTimeoutMillis(0);
      synchronized (lock) {
        backend = next;
        switchingTarget = null;
        next = null;
        lifecycle.set(SessionLifecycle.CONNECTED);
        lock.notifyAll();
      }
      // An FML1 client does not run its handshake twice. Forge's HandshakeReset puts it back to
      // the start so the new server's FML|HS exchange can happen at all.
      var reset = gg.tame.conduit.modded.FmlHandshakeReset.forSwitch(protocol, modClassifier.marker());
      if (reset.isPresent()) writeClient(reset.get());
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
      // A backend with a Configuration phase was already told above; one without has no phase to
      // be told in, and its Play state only exists after the commit.
      if (!backendDefinition.hasConfiguration()) replayRegisteredChannels(backend, ConnectionState.PLAY, backendDefinition);
      discard(previous);
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
      discard(next);
      if (next == null && socket != null) try { socket.close(); } catch (IOException ignored) { }
      gg.tame.conduit.log.ConduitLog.warn("Switch to " + server.name() + " failed: " + exception.getMessage());
      if (targetView != null) {
        runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerSwitchFailedEvent(this, sourceView, targetView,
            exception.getMessage() == null ? "switch failed" : exception.getMessage()));
      }
      if (clientEnteredConfiguration) {
        // Client left PLAY; cannot safely restore the old Play session.
        if (exception instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused) {
          refusedInConfiguration(refused, server);
        } else {
          try { writeClient(PlayPackets.configurationDisconnect(protocol, "Could not connect to " + server.name() + ".")); }
          catch (IOException ignored) { }
          close();
        }
        throw exception;
      }
      // A fallback's caller set SWITCHING itself, over a backend that is already gone, and either
      // tries the next server or closes. Handing the session back as CONNECTED here pointed it at that
      // dead backend between attempts, where a /server from the client could start a second switch.
      if (!fallback) synchronized (lock) {
        if (lifecycle.get() == SessionLifecycle.SWITCHING) {
          lifecycle.set(previous != null ? SessionLifecycle.CONNECTED : SessionLifecycle.CLOSED);
          lock.notifyAll();
        }
      }
      // Fired once the player is back where they were, so a listener sees them there. A refusal can
      // only come from the login, before the commit, so the client never left Play for it.
      if (exception instanceof gg.tame.conduit.login.BackendLoginPipeline.Refused refused && targetView != null) {
        throw new RefusedSwitch(refused(refused, targetView), refused);
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
    // The entry is the player as the client knows itself. With forwarding none an offline backend
    // names the player by a UUID of its own, which its Login Success carries to the client unchanged;
    // an entry under the session's UUID listed a real 1.21.3 client twice, once with no latency.
    PlayerProfile self = profile();
    java.util.UUID known = clientUuid;
    if (known != null && !known.equals(self.uniqueId())) {
      self = new PlayerProfile(known, self.username(), self.properties(), self.authenticated());
    }
    writeClient(gg.tame.conduit.protocol.PlayerInfoUpdate.selfAdd(protocol, self));
    needSelfPlayerInfo = false;
  }
  private void writeClient(byte[] packet) throws IOException { writeClient(packet, true); }
  private void rememberClientUuid(byte[] packet) {
    try {
      if (protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PlayPackets.peekId(packet), PacketKind.LOGIN_SUCCESS)) {
        clientUuid = gg.tame.conduit.protocol.codec.JoinGameCodec.decodeLoginSuccess(protocol, packet).uniqueId();
      }
    } catch (IOException | RuntimeException unreadable) {
      // The session's own UUID stands in for one that cannot be read.
    }
  }
  /**
   * The backend's reply to a pre-1.13 Tab press, with Conduit's matching command names added to a
   * command-name one, as PlayerTabCompleteEvent's listeners leave it.
   */
  private byte[] withLegacyTabCompletions(byte[] packet) {
    String typed = legacyTabRequest;
    if (typed == null || clientState.state() != ConnectionState.PLAY) return packet;
    try {
      if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_TAB_COMPLETE)) {
        return packet;
      }
      legacyTabRequest = null;
      List<String> backendMatches = PlayPackets.legacyTabMatches(packet);
      List<String> suggestions = new java.util.ArrayList<>(backendMatches);
      if (typed.startsWith("/") && typed.indexOf(' ') < 0) {
        for (String name : commands.tabComplete(this, typed)) if (!suggestions.contains("/" + name)) suggestions.add("/" + name);
      }
      runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerTabCompleteEvent(this, typed, suggestions));
      // A plugin's null would end the session in the encoder.
      suggestions.removeIf(java.util.Objects::isNull);
      return suggestions.equals(backendMatches) ? packet : PlayPackets.tabComplete(protocol, -1, 0, 0, suggestions);
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
    if (clientState.state() == ConnectionState.LOGIN) rememberClientUuid(outbound);
    // Last stop before the socket: a recipe list the client cannot parse costs the whole session,
    // and a correct one passes through this untouched.
    outbound = gg.tame.conduit.protocol.RecipeListRepair.apply(protocol, outbound);
    outbound = withLegacyTabCompletions(outbound);
    if (gg.tame.conduit.protocol.ProfileTrace.enabled()) {
      String where = lifecycle.get() == SessionLifecycle.SWITCHING ? "switch" : "steady";
      gg.tame.conduit.protocol.ProfileTrace.clientbound(where, protocol, clientState.state(), outbound, profile());
    }
    gg.tame.conduit.protocol.ClientboundDump.record(outbound);
    keepAlive.written(clientState.state(), outbound);
    ConnectionState writtenIn = clientState.state();
    display.beforeWrite(writtenIn, outbound);
    resourcePacks.beforeWrite(writtenIn, outbound);
    if (flush) client.write(outbound);
    else {
      client.writeUnflushed(outbound);
    }
    awaitingBackendJoinGame.written(outbound);
    display.afterWrite(writtenIn, outbound);
    resourcePacks.afterWrite(writtenIn, outbound);
  }
  @Override public String username() { return profile().username(); }
  @Override public boolean hasPermission(String permission) {
    // A plugin's provider runs on this session's threads; one that throws denies rather than
    // taking the command, or the join a maintenance check is part of, down with it.
    try { return runtime.permissions().hasPermission(this, permission); }
    catch (RuntimeException | LinkageError failure) {
      gg.tame.conduit.log.ConduitLog.error("permission provider failed on " + permission, failure);
      return false;
    }
  }
  @Override public void sendMessage(String message) {
    sendMessage(gg.tame.conduit.api.text.Text.of(message == null ? "" : message));
  }
  @Override public void sendMessage(gg.tame.conduit.api.text.Text text) {
    try {
      if (clientState.state() == ConnectionState.PLAY) writeClient(PlayPackets.systemChat(protocol, text));
    } catch (IOException ignored) { }
  }
  @Override public void sendActionBar(Text message) { display.actionBar(message); }
  @Override public void sendTitle(Text title) { display.title(title); }
  @Override public void sendSubtitle(Text subtitle) { display.subtitle(subtitle); }
  @Override public void sendTitleTimes(gg.tame.conduit.api.player.TitleTimes times) { display.titleTimes(java.util.Objects.requireNonNull(times, "times")); }
  @Override public void clearTitle() { display.clearTitle(false); }
  @Override public void resetTitle() { display.clearTitle(true); }
  @Override public void showBossBar(gg.tame.conduit.api.player.BossBar bar) { display.show(java.util.Objects.requireNonNull(bar, "bar")); }
  @Override public void hideBossBar(gg.tame.conduit.api.player.BossBar bar) { if (bar != null) display.hide(bar); }
  @Override public void sendPlayerListHeaderAndFooter(Text header, Text footer) { display.headerAndFooter(header, footer); }
  @Override public Text playerListHeader() { return display.header(); }
  @Override public Text playerListFooter() { return display.footer(); }
  @Override public void addTabListEntry(gg.tame.conduit.api.player.TabListEntry entry) { display.addEntry(java.util.Objects.requireNonNull(entry, "entry")); }
  @Override public boolean removeTabListEntry(java.util.UUID id) { return id != null && display.removeEntry(id); }
  @Override public List<gg.tame.conduit.api.player.TabListEntry> tabListEntries() { return display.entries(); }
  @Override public boolean sendResourcePack(gg.tame.conduit.api.player.ResourcePack pack) { return resourcePacks.offer(java.util.Objects.requireNonNull(pack, "pack")); }
  @Override public boolean removeResourcePack(java.util.UUID id) { return id != null && resourcePacks.remove(id); }
  @Override public boolean clearResourcePacks() { return resourcePacks.clear(); }
  @Override public List<gg.tame.conduit.api.player.ResourcePack.Offered> resourcePacks() { return resourcePacks.offered(); }
  @Override public void playSound(gg.tame.conduit.api.player.Sound sound) { display.playSound(java.util.Objects.requireNonNull(sound, "sound")); }
  @Override public void playSound(gg.tame.conduit.api.player.Sound sound, double x, double y, double z) {
    display.playSound(java.util.Objects.requireNonNull(sound, "sound"), x, y, z);
  }
  @Override public void stopSound(String name, gg.tame.conduit.api.player.Sound.Source source) { display.stopSound(name, source); }
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
    for (BackendConnection connection : open) discard(connection);
    client.close();
    // After the socket, which ends any display write still stuck on a client that stopped reading.
    display.close();
    resourcePacks.close();
  }
}

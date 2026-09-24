// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.session;

import gg.tame.conduit.brand.BrandRewriter;
import gg.tame.conduit.api.event.player.PlayerConfigurationEvent;
import gg.tame.conduit.api.event.player.PlayerKickedFromServerEvent.KickResult;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
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
  final ConduitConfiguration configuration;
  private final PacketTransport client;
  final ProtocolDefinition protocol;
  final int clientProtocol;
  volatile int backendProtocol;
  volatile ProtocolDefinition backendDefinition;
  volatile ProtocolTranslator translator = IdentityTranslator.INSTANCE;
  volatile TranslationSupport translationSupport = TranslationSupport.DIRECT;
  final ProtocolSession clientState;
  private final LoginPipeline loginPipeline;
  final PlayerInfoForwarder forwarder;
  final gg.tame.conduit.runtime.ConduitRuntime runtime;
  private final CommandManager commands;
  final PlayerManager players;
  final BackendSelector selector;
  final Handshake handshake;
  private final byte[] originalHandshake;
  private final byte[] originalLoginStart;
  /** The client's chat key (see chatSession()); null until it is known. */
  private volatile gg.tame.conduit.api.player.ChatSession chatSession;
  // Secure chat toward the backend: what it holds the client to having seen, and the lock that keeps
  // what the proxy writes for the client and what the client writes itself from interleaving.
  private final Object secureChatLock = new Object();
  private final gg.tame.conduit.protocol.SecureChat.Window chatWindow = new gg.tame.conduit.protocol.SecureChat.Window();
  private BackendConnection chatWindowBackend;
  /** The seen-messages list a 1.19.1-1.19.2 client last sent its backend; null before it has. */
  private volatile byte[] lastSeenList;
  final InetAddress address;
  final HandshakeClassifier modClassifier;
  final SwitchPacketQueue switchQueue;
  /** Channels the client announced. Registration is per connection, so a new backend needs telling. */
  private final gg.tame.conduit.modded.RegisteredChannels registeredChannels = new gg.tame.conduit.modded.RegisteredChannels();
  final Object lock = new Object();
  final AtomicReference<SessionLifecycle> lifecycle = new AtomicReference<>(SessionLifecycle.CONNECTING);
  private final SessionSwitch switching = new SessionSwitch(this);
  private static final int MAX_KICK_REDIRECTS = 4;
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
  final Object translatorLock = new Object();
  final BlockingQueue<Boolean> configurationAck = new ArrayBlockingQueue<>(1);
  final BlockingQueue<Boolean> knownPacksAck = new ArrayBlockingQueue<>(1);
  volatile BackendConnection backend;
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
  volatile boolean closed;
  private volatile boolean expectClientLoginAck;
  /** The backend's login asked the client something, so an answer may still be in flight. */
  private volatile boolean loginQueriesRelayed;
  /** A 1.13+ Forge client's mod list, read from its login answer; told to plugins once it joins. */
  private volatile gg.tame.conduit.api.server.ModInfo modInfo;
  /**
   * What a client with no command tree last sent the backend to complete, until the backend's reply
   * has been through {@link #withLegacyTabCompletions}. A reply to a command name gets Conduit's
   * matching commands added: it is the only place such a client learns command names from, and the
   * backend knows none of Conduit's. Every such reply then goes past PlayerTabCompleteEvent.
   */
  private volatile String legacyTabRequest;
  /** Keys of the cookies plugins asked the client for that it has not answered yet, once per request. */
  private final List<String> cookieRequests = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
  /**
   * True while Conduit, rather than the translator, is the one moving the client out of Play for a
   * reconfiguration, so the client's acknowledgement is Conduit's own reply and must not also be
   * handed to a translator that has already put its half of the connection into Configuration.
   */
  volatile boolean conduitOwnsReconfiguration;
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
  final SwitchJoinGate awaitingBackendJoinGame;
  private final KeepAliveClock keepAlive;
  volatile boolean commandsDeclared;
  /** The backend's own Declare Commands, before the merge, so the tree can be rebuilt and resent. */
  volatile byte[] lastBackendCommands;
  private static final int MAX_DEFERRED_PLAY = 512;
  /** Set once Conduit has synthesised finish_configuration for a non-configuration backend. */
  private volatile boolean configurationFinishSynthesized;
  final List<byte[]> deferredPlay = new java.util.ArrayList<>();
  boolean playLoginSent;
  /** Guarded by {@code lock}: a flush of {@link #deferredPlay} is writing to the client. */
  private boolean flushingDeferredPlay;
  boolean needSelfPlayerInfo = true;
  /** The UUID the client was told is its own, by the Login Success that reached it. */
  private volatile java.util.UUID clientUuid;
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
    this.resourcePacks = new ClientResourcePacks(this, protocol, this::writeClient, event -> runtime.events().fire(event), new ServerPacks());
  }
  /** Plugins' say over the packs the server the player is on, or is moving to, sends them. */
  private final class ServerPacks implements ClientResourcePacks.Server {
    private BackendConnection from() { return switching.switchingTarget != null ? switching.switchingTarget : backend; }
    private gg.tame.conduit.api.server.RegisteredServer server() {
      BackendConnection from = from();
      return from == null ? null : runtime.registered(from.server().name()).orElse(null);
    }
    @Override public gg.tame.conduit.api.player.ResourcePack offered(gg.tame.conduit.api.player.ResourcePack pack) {
      var server = server();
      if (server == null) return pack;
      var event = runtime.events().fire(new gg.tame.conduit.api.event.player.ServerResourcePackOfferEvent(PlayerSession.this, server, pack));
      return event.cancelled() ? null : event.pack();
    }
    @Override public boolean removed(java.util.Optional<java.util.UUID> id) {
      var server = server();
      return server == null || !runtime.events().fire(new gg.tame.conduit.api.event.player.ServerResourcePackRemoveEvent(PlayerSession.this, server, id)).cancelled();
    }
    /** As the client's own packet goes to its server, through whatever translates the pair. */
    @Override public void answer(byte[] answer) throws IOException {
      BackendConnection target = from();
      if (target == null) return;
      byte[] outbound = towardBackend(clientState.state(), answer);
      if (outbound != null) target.writeUncompressed(outbound);
      flushTranslatorExtras(target);
    }
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
  /** Replaces the profile every later BackendConnection forwards; the connection open now was built with the old one. */
  @Override public boolean setGameProfileProperties(List<gg.tame.conduit.api.player.GameProfile.Property> properties) {
    PlayerProfile profile = profile();
    loginPipeline.replace(new PlayerProfile(profile.uniqueId(), profile.username(), properties.stream()
        .map(property -> new gg.tame.conduit.login.ProfileProperty(property.name(), property.value(), property.signature())).toList(),
        profile.authenticated()));
    return true;
  }
  @Override public boolean closeDialog() {
    int id = pluginPacketId(PacketKind.PLAY_CLEAR_DIALOG, PacketKind.CONFIGURATION_CLEAR_DIALOG);
    return id >= 0 && writeForPlugin(() -> gg.tame.conduit.protocol.PlayerApiPackets.clearDialog(id));
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
  @Override public boolean spoofChatInput(String input) {
    if (input == null) throw new IllegalArgumentException("chat input is required");
    int limit = gg.tame.conduit.protocol.ProtocolEras.chatLimit(protocol.version().number());
    if (input.length() > limit) throw new IllegalArgumentException("chat input is longer than the " + limit + " characters this client's chat box takes");
    if (protocol.capabilities().legacyPlayChat()) return sendChatLineToServer(input);
    boolean command = input.startsWith("/");
    // The unsigned Chat Command is the command alone, which is all sendChatLineToServer writes.
    if (command && gg.tame.conduit.protocol.ProtocolEras.unsignedChatCommand(protocol.version().number())) {
      return sendChatLineToServer(input.substring(1));
    }
    // Everything else a 1.19+ client sends says which signed messages it has seen, and a backend
    // disconnects a player whose account of that does not match its own. What the proxy writes gives
    // the account the backend already has (see SecureChat.Window), under the lock the client's own
    // chat goes out under, so neither lands between the other's reading and writing.
    synchronized (secureChatLock) {
      BackendConnection current = backend;
      if (current == null) return false;
      int acknowledged = chatWindow(current).acknowledgedForProxy();
      try {
        return sendToServer(command
            ? gg.tame.conduit.protocol.SecureChat.unsignedCommand(protocol, input.substring(1), acknowledged, lastSeenList)
            : gg.tame.conduit.protocol.SecureChat.unsignedChat(protocol, input, acknowledged, lastSeenList));
      } catch (IOException impossible) {
        return false;
      }
    }
  }
  /** The client's chat key: from its 1.19-1.19.2 Login Start, or the 1.19.3+ Chat Session Update it last sent. */
  @Override public java.util.Optional<gg.tame.conduit.api.player.ChatSession> chatSession() {
    var session = chatSession;
    if (session == null && originalLoginStart != null
        && gg.tame.conduit.protocol.ProtocolEras.loginStartSignature(protocol.version().number())) {
      try { session = chatSession = gg.tame.conduit.protocol.SecureChat.loginKey(protocol.version().number(), PlayPackets.body(originalLoginStart)); }
      catch (IOException unreadable) { }
    }
    return java.util.Optional.ofNullable(session);
  }
  @Override public boolean deleteChatMessage(byte[] signature) {
    java.util.Objects.requireNonNull(signature, "signature");
    int id = pluginPacketId(PacketKind.PLAY_DELETE_MESSAGE, null);
    return id >= 0 && writeForPlugin(() -> gg.tame.conduit.protocol.SecureChat.deleteMessage(protocol, signature));
  }
  /** The backend's record of what this client has seen, started afresh for each backend. */
  private gg.tame.conduit.protocol.SecureChat.Window chatWindow(BackendConnection current) {
    synchronized (secureChatLock) {
      if (current != chatWindowBackend) {
        chatWindowBackend = current;
        chatWindow.reset();
        lastSeenList = null;
      }
      return chatWindow;
    }
  }
  /** A Play packet in the client's dialect on its way to {@code target}: what it acknowledges is now the backend's too. */
  private void sentSecureChat(BackendConnection target, byte[] packet) {
    try {
      var update = gg.tame.conduit.protocol.SecureChat.update(protocol, packet);
      if (update != null) chatWindow(target).apply(update);
      byte[] list = gg.tame.conduit.protocol.SecureChat.lastSeenList(protocol, packet);
      if (list != null) { chatWindow(target); lastSeenList = list; }
    } catch (IOException | RuntimeException unreadable) { }
  }
  @Override public boolean updateCustomChatCompletions(ChatCompletions action, java.util.Collection<String> completions) {
    java.util.Objects.requireNonNull(action, "action");
    List<String> entries = List.copyOf(completions);
    int id = pluginPacketId(PacketKind.PLAY_CHAT_SUGGESTIONS, null);
    return id >= 0 && writeForPlugin(() -> gg.tame.conduit.protocol.PlayerApiPackets.chatSuggestions(id, action.ordinal(), entries));
  }
  @Override public boolean setServerLinks(List<gg.tame.conduit.api.player.ServerLink> links) {
    List<gg.tame.conduit.api.player.ServerLink> copy = List.copyOf(links);
    int id = pluginPacketId(PacketKind.PLAY_SERVER_LINKS, PacketKind.CONFIGURATION_SERVER_LINKS);
    return id >= 0 && writeForPlugin(() -> gg.tame.conduit.protocol.PlayerApiPackets.serverLinks(id, protocol.version().number(), copy));
  }
  @Override public boolean storeCookie(String key, byte[] data) {
    String cookie = gg.tame.conduit.protocol.PlayerApiPackets.cookieKey(key);
    java.util.Objects.requireNonNull(data, "data");
    if (data.length > gg.tame.conduit.protocol.PlayerApiPackets.COOKIE_MAX_BYTES) {
      throw new IllegalArgumentException("a cookie holds at most " + gg.tame.conduit.protocol.PlayerApiPackets.COOKIE_MAX_BYTES + " bytes, not " + data.length);
    }
    byte[] copy = data.clone();
    int id = pluginPacketId(PacketKind.PLAY_STORE_COOKIE, PacketKind.CONFIGURATION_STORE_COOKIE);
    return id >= 0 && writeForPlugin(() -> gg.tame.conduit.protocol.PlayerApiPackets.storeCookie(id, cookie, copy));
  }
  @Override public boolean requestCookie(String key) {
    String cookie = gg.tame.conduit.protocol.PlayerApiPackets.cookieKey(key);
    int id = pluginPacketId(PacketKind.PLAY_COOKIE_REQUEST, PacketKind.CONFIGURATION_COOKIE_REQUEST);
    if (id < 0) return false;
    // Noted first: the answer can be back before the write returns.
    cookieRequests.add(cookie);
    if (writeForPlugin(() -> gg.tame.conduit.protocol.PlayerApiPackets.cookieRequest(id, cookie))) return true;
    cookieRequests.remove(cookie);
    return false;
  }
  /**
   * The client's answer to a cookie the proxy asked for, which goes to the plugins: the backend never
   * asked and must not hear it. Any other answer is the backend's and goes on untouched.
   */
  private boolean cookieForProxy(byte[] packet) {
    if (cookieRequests.isEmpty()) return false;
    var answer = gg.tame.conduit.protocol.PlayerApiPackets.cookieResponse(protocol, clientState.state(), packet);
    // ponytail: matched by key alone. When the backend and a plugin both ask for the same key at once,
    // the answers can go to each other, but they carry the same cookie.
    if (answer.isEmpty() || !cookieRequests.remove(answer.get().key())) return false;
    runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerCookieReceiveEvent(this, answer.get().key(), answer.get().data()));
    return true;
  }
  /**
   * The id of {@code play} or {@code configuration}, whichever state the client is in, for a packet a
   * plugin has the proxy write; -1 when the player has not joined, or the client has no such packet in
   * that state. Nothing is written during the login, whose configuration the client's answers would
   * not come back through.
   */
  private int pluginPacketId(PacketKind play, PacketKind configuration) {
    SessionLifecycle now = lifecycle.get();
    if (closed || (now != SessionLifecycle.CONNECTED && now != SessionLifecycle.SWITCHING)) return -1;
    ConnectionState state = clientState.state();
    PacketKind kind = state == ConnectionState.PLAY ? play : state == ConnectionState.CONFIGURATION ? configuration : null;
    return kind != null && protocol.defines(state, PacketDirection.SERVER_TO_CLIENT, kind)
        ? protocol.id(state, PacketDirection.SERVER_TO_CLIENT, kind) : -1;
  }
  private interface PluginPacket { byte[] build() throws IOException; }
  private boolean writeForPlugin(PluginPacket packet) {
    try {
      writeClient(packet.build());
      return true;
    } catch (IOException failed) {
      return false;
    }
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
  @Override public java.net.InetSocketAddress virtualHost() { return handshake.virtualHost(); }
  /**
   * {@code username (ip:port)}, as the connection log names a player. The port is left off when
   * the address was handed to us by a proxy in front of this one, because the port we hold then is
   * our own link to that proxy and says nothing about where the player is.
   */
  /**
   * The backend the connection log last announced this player on, which is not the same as the one
   * they are wired to: a session that ends during configuration has a backend already and never
   * reached the join line. Reporting this one keeps every "left" paired with a "joined".
   */
  volatile String loggedBackend;
  String origin() {
    String host = address.getHostAddress();
    if (configuration.forwardedPlayerAddress().isPresent()) return username() + " (" + host + ")";
    return username() + " (" + host + ":" + client.remotePort() + ")";
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
    // By address too: a raw server (ServerManager.raw) may share a registered one's name.
    BackendServer target = server == null ? null : selector.registry().get(server.getName())
        .filter(found -> found.address().equals(server.getAddress())).orElse(null);
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
      try { result.complete(switching.runSwitch(target)); }
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
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND));
      gg.tame.conduit.protocol.MinecraftOutput.string(output, line);
    } catch (IOException impossible) {
      return false;
    }
    return sendToServer(bytes.toByteArray());
  }
  /** A Play packet in the client's own dialect, sent to the backend as if the client had; false when it could not be. */
  private boolean sendToServer(byte[] packet) {
    BackendConnection current = backend;
    if (current == null || closed || clientState.state() != ConnectionState.PLAY) return false;
    try {
      byte[] outbound = towardBackend(ConnectionState.PLAY, packet);
      if (outbound == null) return false;
      synchronized (secureChatLock) {
        sentSecureChat(current, packet);
        current.writeUncompressed(outbound);
      }
      flushTranslatorExtras(current);
      return true;
    } catch (IOException | RuntimeException failed) {
      return false;
    }
  }
  /**
   * A 1.19.3+ client's chat line, put to PlayerChatEvent like an older client's. True when the
   * client's packet is not to be relayed as it stands.
   *
   * <p>Withholding a signed message is safe for the chain it belongs to: the next one carries a later
   * index, which is all a server asks of it. What would break is the count of messages the withheld
   * one acknowledged, which the server tracks and the next message builds on, so that count goes to
   * the server alone as a Message Acknowledgment. The text of a signed message cannot be changed
   * without its signature failing, so a rewrite is carried out only for an unsigned one -- a client
   * with chat signing off, or an offline-mode proxy's players -- and otherwise logged and not done.
   */
  private boolean relayModernChat(byte[] packet) throws IOException {
    boolean muted = muted();
    if (!muted && !runtime.events().listening(gg.tame.conduit.api.event.player.PlayerChatEvent.class)) return false;
    PlayPackets.SignedChat line = PlayPackets.signedChat(protocol.version().number(), packet);
    if (muted) { acknowledgeDroppedChat(line); return true; }
    var chat = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerChatEvent(this, line.message()));
    if (chat.cancelled()) {
      // A 1.19.1-1.19.2 signed message names the one before it, and the backend checks that chain:
      // one withheld breaks it for the next.
      if (line.signed() && gg.tame.conduit.protocol.ProtocolEras.chatLastSeenList(protocol.version().number())) {
        gg.tame.conduit.log.ConduitLog.warn("A plugin denied " + username() + "'s chat, which the client signed into a chain; the backend got it anyway");
        return false;
      }
      acknowledgeDroppedChat(line);
      return true;
    }
    if (chat.message().equals(line.message())) return false;
    if (line.signed()) {
      gg.tame.conduit.log.ConduitLog.warn("A plugin rewrote " + username() + "'s chat, which the client signed; the backend got it unchanged");
      return false;
    }
    var bytes = new java.io.ByteArrayOutputStream();
    try (var output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, PlayPackets.packetId(packet));
      gg.tame.conduit.protocol.MinecraftOutput.string(output, chat.message());
      output.write(packet, line.afterMessage(), packet.length - line.afterMessage());
    }
    return sendToServer(bytes.toByteArray());
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
        var registration = registeredChannels.record(decoded.channel(), decoded.data());
        var outcome = runtime.security().channelGuard().inspect(decoded.channel(), username());
        if (outcome == gg.tame.conduit.security.ChannelGuard.Outcome.DROP) return false;
        if (outcome == gg.tame.conduit.security.ChannelGuard.Outcome.KICK) {
          disconnect("Blocked plugin channel.");
          return false;
        }
        if (registration != null && !registration.channels().isEmpty()) {
          runtime.events().fire(registration.register()
              ? new gg.tame.conduit.api.event.player.PlayerChannelRegisterEvent(this, registration.channels())
              : new gg.tame.conduit.api.event.player.PlayerChannelUnregisterEvent(this, registration.channels()));
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
      // A backend asking the proxy for something, rather than a backend talking to the client. It is
      // addressed here, so it is answered here and never passed on: a client has no business being
      // handed the network's player list, and a plugin on the other side would not understand it
      // either. Plugins still see it first, so one may cancel or inspect it as it always could.
      var event = new gg.tame.conduit.api.event.messaging.PluginMessageEvent(this, decoded.channel(), decoded.data(), direction);
      runtime.events().fire(event);
      if (event.cancelled()) return false;
      if (direction == gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.BACKEND_TO_PROXY) {
        if (configuration.ops().messaging().bungeeCordChannel()
            && gg.tame.conduit.messaging.BungeeCordMessages.isChannel(decoded.channel())) {
          runtime.bungeeCord().handle(this, decoded.channel(), decoded.data());
          return false;
        }
        // Every other channel a backend sends on, named once per session. Without this, a hub
        // plugin whose message never arrives and one whose message arrives under a name Conduit
        // does not recognise look exactly alike from the log: both are silence. This says which.
        if (backendChannelsSeen.add(decoded.channel())) {
          gg.tame.conduit.log.ConduitLog.debug(origin() + ": backend '"
              + (backend == null ? "?" : backend.server().name()) + "' sent on plugin channel '"
              + decoded.channel() + "' (" + decoded.data().length + " bytes), passed to the client");
        }
      }
      return true;
    } catch (IOException malformed) {
      // Forwarded rather than dropped, as before -- but no longer in silence. A plugin message the
      // proxy cannot read is the one shape of this bug that leaves no other trace anywhere.
      gg.tame.conduit.log.ConduitLog.debug(origin() + ": could not read a plugin message ("
          + direction + "): " + malformed.getMessage());
      return true;
    }
  }
  /** Backend plugin channels already named in the log, so each is said once rather than per packet. */
  private final java.util.Set<String> backendChannelsSeen = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    // A shutdown flags itself before it takes the list of players to turn away, so one that began
    // while this login was dialling its first server -- a plugin can stop the proxy from Velocity's
    // PostLoginEvent, which comes before that -- either has this player on its list or is seen here.
    if (runtime.shuttingDown()) disconnect(runtime.gracefulShutdown().message());
    gg.tame.conduit.log.ConduitLog.info(origin() + " connected to the proxy");
    gg.tame.conduit.metrics.ConduitMetrics.current().playerJoined();
    long joined = System.nanoTime();
    if (modInfo != null) runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerModInfoEvent(this, modInfo));
    runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerPostLoginEvent(this));
    runtime.revealNodes(this);
    runtime.noteProtection(this);
    // The client is in Configuration from its Login Success on; the reader below relays the server's
    // phase, up to the Finish Configuration that waits for what plugins start here. A phase plugins
    // hear about ends before the player counts as on the server, as the Velocity events have it.
    if (expectClientLoginAck) enteredConfiguration(initial);
    firstConnected = runtime.registered(initial.server().name()).orElse(null);
    // A backend with no Configuration phase decodes Play only once it has sent its Join Game, and a
    // cold one can take a while over its first player: a 1.19 server read the channel registration
    // that landed in that gap as a Login packet and closed ("Index 12 out of bounds for length 3").
    announceAtJoinGame = configurationEntered == null && !backendDefinition.hasConfiguration();
    if (configurationEntered == null) connectedToFirst();
    this.joinedAtNanos = joined;
    try {
      // The session is handed to the selector and this thread is done: from here the player is read
      // by selector workers, and costs no thread of their own between packets. There is no second
      // path to fall back to -- see watch().
      watch();
    } catch (IOException | RuntimeException failed) {
      // PostLogin has already fired. A kick racing attachment still ends a completed login,
      // just as returning from the blocking relay did, and releases its accounting once.
      ended();
      throw failed;
    }
  }

  /** {@link System#nanoTime} at the join, for the played-for figure {@link #ended} reports. */
  private volatile long joinedAtNanos;
  /** Runs once the session is really over, whenever that turns out to be; see {@link #onEnded}. */
  private volatile Runnable onEnded = () -> { };
  private final java.util.concurrent.atomic.AtomicBoolean endedOnce = new java.util.concurrent.atomic.AtomicBoolean();
  volatile boolean watched;

  /**
   * What to run when the session ends, for a caller that cannot wait for it on the stack.
   *
   * <p>A watched session outlives the thread that logged it in -- that is the point -- so the
   * connection slot, the throttle lease and the login's own buffers cannot be released by that
   * thread's {@code finally} any more. They are released here instead, on whichever thread finds
   * the session over.
   */
  public void onEnded(Runnable action) { this.onEnded = action; }

  /** Whether the session is being watched rather than read by threads of its own. */
  public boolean watched() { return watched; }

  /**
   * Hands both sockets to the selector, so that a player who is merely online costs no thread.
   *
   * <p>False when either end cannot be watched -- a transport built over plain streams, as the
   * tests build one -- and the caller then reads them on two threads as it always did. Both or
   * neither: half a relay watched and half on a thread is a shape nothing else here expects.
   */
  private void watch() throws IOException {
    var selector = runtime.connectionSelector();
    // Every socket Conduit owns is channel-backed -- the listener accepts on a ServerSocketChannel
    // and a backend is dialled on a SocketChannel -- so these cannot be false. They used to mean
    // "keep a thread per socket instead"; now they mean this session is not what it claims to be,
    // and ending it with a reason beats relaying it down a path nothing else uses any more.
    if (!client.selectable()) throw new IOException("the client connection is not channel-backed");
    boolean endNow;
    // Under the lock the switch commits in, and watching whatever backend is current rather than
    // the one the login opened. Login fires PlayerPostLoginEvent and PlayerServerConnectedEvent
    // before reaching here, so a plugin that routes the player from either listener -- a lobby
    // router -- has already switched them by now, and the backend this used to be handed had been
    // closed and discarded by that switch. Attaching to it threw, and the player was disconnected
    // instead of moved. The same lock also settles who watches the new backend: a switch reads
    // {@code watched} while deciding, so both that read and this write have to be one decision.
    synchronized (lock) {
      BackendConnection live = backend;
      if (live == null) throw new IOException("the player has no backend to watch");
      if (!live.selectable()) throw new IOException("the backend connection is not channel-backed");
      var backendWatch = watchBackend(live);
      clientWatch = client.attachTo(selector, new gg.tame.conduit.network.ConnectionSelector.Handler() {
        @Override public boolean onReadable() throws IOException { return relayBufferedFromClient(); }
        @Override public void onClosed(String reason, boolean fault) {
          // The only account of why this player was dropped. Thrown away, a relay that failed -- a
          // peer that stopped reading, a translator that threw, a worker that took an error -- took
          // the player off the proxy and left nothing in the log to say so; a session read on a
          // thread of its own at least had its IOException named where the login was logged.
          if (fault) gg.tame.conduit.log.ConduitLog.warn(origin() + ": connection closed: " + reason);
          else gg.tame.conduit.log.ConduitLog.debug(origin() + ": connection closed: " + reason);
          ended();
        }
      });
      // Publish both transports before either callback can use them. A failed attachment propagates
      // to login cleanup: once a channel is non-blocking, falling back to blocking readers is unsafe.
      pair(clientWatch, backendWatch);
      watched = true;
      endNow = closed;
      if (!endNow) {
        clientWatch.start();
        backendWatch.start();
      }
    }
    // Outside the lock: ending closes both connections, and the client's close waits out the linger
    // that gives a kicked player their reason, which is not something to hold the switch lock for.
    if (endNow) ended();
  }

  /**
   * Watches one backend. Registered per connection, so a switch simply stops watching the backend
   * it replaced -- {@link BackendConnection#close()} does that -- and starts watching its own.
   */
  gg.tame.conduit.network.ConnectionSelector.Registration watchBackend(BackendConnection connection) throws IOException {
    var selector = runtime.connectionSelector();
    if (selector == null || !watchable(connection)) return null;
    return connection.attachTo(selector, new gg.tame.conduit.network.ConnectionSelector.Handler() {
      @Override public boolean onReadable() throws IOException { return relayBufferedFromBackend(connection); }
      @Override public void onClosed(String reason, boolean fault) {
        // A backend the player has already been switched off is expected to end, and says nothing
        // about the player -- but why it ended is still worth a line when the proxy gave up on it.
        boolean live = connection == backend && !closed;
        String said = origin() + ": backend '" + connection.server().name() + "' closed: " + reason;
        if (fault) gg.tame.conduit.log.ConduitLog.warn(said);
        else gg.tame.conduit.log.ConduitLog.debug(said);
        if (!live) return;
        // A backend that reset the connection -- it crashed, was killed, or closed with bytes from
        // Conduit still unread, which Windows turns into a reset -- is as gone as one that hung up,
        // and is fallen back from the same way. Ending the session here dropped the player where a
        // clean close would have moved them. On a thread of its own, as the walk dials backends.
        if (fault && lifecycle.get() == SessionLifecycle.CONNECTED) {
          gg.tame.conduit.network.SocketThreads.start(() -> handleBackendLoss(connection));
          return;
        }
        // Only the live backend going means the session is over.
        ended();
      }
    });
  }

  /** The client's watch, so a switch can point it at the backend the player moved to. */
  volatile gg.tame.conduit.network.ConnectionSelector.Registration clientWatch;

  static void pair(gg.tame.conduit.network.ConnectionSelector.Registration client,
                           gg.tame.conduit.network.ConnectionSelector.Registration backend) {
    if (client == null || backend == null) return;
    client.feeds(backend);
    backend.feeds(client);
  }

  private boolean watchable(BackendConnection connection) { return connection != null && connection.selectable(); }

  /**
   * The session is over, however it ended and on whatever thread noticed. Once, because a watched
   * session has two connections that can each be the one to notice.
   */
  /**
   * Whether this player's chat is withheld, and if so tells them: a muted player who sees nothing
   * happen otherwise assumes the network is broken. Commands are not chat and still go through.
   */
  private boolean muted() {
    var mute = runtime.mutes().find(uniqueId(), username());
    if (mute.isEmpty()) return false;
    String left = mute.get().remaining(System.currentTimeMillis()).map(when -> " for another " + when).orElse("");
    sendMessage(Text.of("You are muted" + left + ": " + mute.get().reason()).color(gg.tame.conduit.api.text.TextColor.RED));
    return true;
  }

  /**
   * A 1.19.3+ chat line that was not passed on still owes the backend the acknowledgement it carried,
   * or the client's chat chain and the server's fall out of step and the next line disconnects them.
   */
  /**
   * A 1.19.3-1.20.4 command the proxy kept from the backend (it ran it, or a plugin cancelled it): it
   * carried an acknowledgement, which goes on alone as for withheld chat. True, for the caller to return.
   */
  private boolean withheldCommand(byte[] packet) {
    try {
      var update = gg.tame.conduit.protocol.SecureChat.update(protocol, packet);
      if (update != null) acknowledgeDroppedChat(new PlayPackets.SignedChat("", false, update.offset(), 0));
    } catch (IOException unreadable) {
      // Nothing to pass on: the command itself was read, and a session is not ended over its tail.
    }
    return true;
  }
  private void acknowledgeDroppedChat(PlayPackets.SignedChat line) throws IOException {
    if (line.acknowledged() > 0
        && protocol.defines(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_ACKNOWLEDGEMENT)) {
      var bytes = new java.io.ByteArrayOutputStream();
      try (var output = new java.io.DataOutputStream(bytes)) {
        gg.tame.conduit.protocol.MinecraftOutput.varInt(output,
            protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_ACKNOWLEDGEMENT));
        gg.tame.conduit.protocol.MinecraftOutput.varInt(output, line.acknowledged());
      }
      sendToServer(bytes.toByteArray());
    }
  }

  private void ended() {
    if (!endedOnce.compareAndSet(false, true)) return;
    closed = true;
    players.remove(this);
    gg.tame.conduit.metrics.ConduitMetrics.current().playerLeft(System.nanoTime() - joinedAtNanos);
    leave(gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
    close();
    onEnded.run();
  }
  /**
   * Tells plugins the player is gone: once, the first time any path that ends the session gets here,
   * so that everything set up for the player in PlayerSetupEvent is released exactly once. Only then is
   * the player's identity free for another login, so plugins hear this session end before they hear
   * of the next one.
   */
  public void leave(gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus status) {
    if (!left.compareAndSet(false, true)) return;
    String last = loggedBackend;
    gg.tame.conduit.log.ConduitLog.info(last == null
        ? origin() + " disconnected from the proxy"
        : origin() + " left backend '" + last + "' (disconnected from the proxy)");
    // Before plugins hear they left: a Velocity permission function is dropped on that event.
    if (status == gg.tame.conduit.api.event.player.PlayerDisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) runtime.noteProtection(this);
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
  BackendConnection track(BackendConnection connection) {
    open.add(connection);
    connection.login().answerQueriesWith(request -> runtime.events().fire(new gg.tame.conduit.api.event.player.BackendLoginPluginMessageEvent(
        this, runtime.registered(connection.server().name()).orElse(null), request.channel(), request.data(), request.messageId()))
        .reply().orElse(null));
    return connection;
  }

  /**
   * Puts the servers {@code [forced-hosts]} names for the address this player typed at the front of
   * the list, in the order the file gives them, so lobby.example.com and pvp.example.com reach
   * different backends through one proxy.
   *
   * <p>They go in front of the rest rather than replacing it: a forced host decides where a player
   * lands, not whether they land anywhere, and a forced backend that is down or does not exist
   * leaves them with the routing they would have had. A host nothing matches changes nothing.
   */
  private void forceHost(List<BackendServer> candidates) {
    java.util.List<String> forced = runtime.configuration().forcedHosts().match(handshake.requestedHost());
    if (forced.isEmpty()) return;
    for (int index = forced.size() - 1; index >= 0; index--) {
      var server = selector.registry().get(forced.get(index));
      if (server.isEmpty()) continue;
      candidates.removeIf(other -> other.name().equalsIgnoreCase(server.get().name()));
      candidates.addFirst(server.get());
    }
  }

  /** Closes a backend and forgets it; closing twice is harmless, forgetting to is not. */
  void discard(BackendConnection connection) {
    if (connection == null) return;
    open.remove(connection);
    connection.close();
  }

  private BackendConnection connectInitial() throws IOException {
    List<BackendServer> candidates = new java.util.ArrayList<>(selector.candidatesFor(protocol.version().number(), modClassifier.family(), false));
    forceHost(candidates);
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
      if (server.full(players.byServer(server.name()).size())) { last = new IOException(server.name() + " is full"); continue; }
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
        switching.prepareTranslation(server);
        socket = selector.open(server);
        writeBackendHandshake(socket, server);
        MinecraftFrames.write(socket.getOutputStream(), LoginStart.encode(profile(), backendDefinition));
        connection = track(new BackendConnection(server, socket, backendDefinition, forwarder, profile(), address, configuration, false));
        // Per read, as the switch path already bounds its own login: a backend that accepts the
        // connection and then says nothing held the join thread, the client's socket and this one
        // open for as long as it cared to. A backend that keeps sending never sees this.
        connection.setReadTimeoutMillis(SessionSwitch.SWITCH_BUDGET_MS);
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
  record Refusal(KickResult result, Text reason, String json) {}

  /**
   * A backend refused the player in its Configuration phase, after the client had left Login for it:
   * their first server, or a switch's target once the client had been moved into its configuration.
   * The client has no server left to stay on, and one configuration started on top of another's
   * half-finished one is not something it survives, so every result ends the session. The default is
   * Disconnect, showing the backend's reason exactly as it wrote it; a Redirect cannot be honoured.
   */
  void refusedInConfiguration(gg.tame.conduit.login.BackendLoginPipeline.Refused refused, BackendServer server) {
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
  Refusal refused(gg.tame.conduit.login.BackendLoginPipeline.Refused refused,
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


  private void writeBackendHandshake(Socket socket, BackendServer server) throws IOException {
    if (translationSupport == TranslationSupport.TRANSLATED) {
      String host = server.address().getHostString();
      var marker = gg.tame.conduit.modded.FmlAddressMarkers.markerFor(modClassifier.family(), modClassifier.marker());
      if (marker != gg.tame.conduit.modded.FmlAddressMarkers.MarkerKind.NONE) {
        host = gg.tame.conduit.modded.FmlAddressMarkers.append(host, marker);
      } else if (modClassifier.marker() != gg.tame.conduit.modded.FmlAddressMarkers.MarkerKind.NONE) {
        host = gg.tame.conduit.modded.FmlAddressMarkers.append(host, modClassifier.marker());
      }
      host = forwarder.handshakeHost(host, profile(), address);
      Handshake backendHandshake = new Handshake(backendProtocol, host, server.address().getPort(), 2);
      MinecraftFrames.write(socket.getOutputStream(), backendHandshake.encode());
    } else {
      // A client that arrived by transfer is logged in to the backend as any other: the backend is
      // Conduit's, not the server that sent the client, and a vanilla one refuses transfers by default.
      // Legacy forwarding rewrites the host, and the client's bytes no longer say what the backend must read.
      String host = forwarder.handshakeHost(handshake.requestedHost(), profile(), address);
      MinecraftFrames.write(socket.getOutputStream(), transferred() || !host.equals(handshake.requestedHost())
          ? new Handshake(handshake.protocolVersion(), host, handshake.requestedPort(), 2).encode()
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
  int flushTranslatorExtras(BackendConnection target) {
    return flushTranslatorExtras(translator, target);
  }

  int flushTranslatorExtras(ProtocolTranslator translator, BackendConnection target) {
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

  byte[] towardClient(ConnectionState state, byte[] packet) throws IOException {
    return towardClient(translator, state, packet);
  }

  byte[] towardClient(ProtocolTranslator translator, ConnectionState state, byte[] packet) throws IOException {
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
  private static final int LOGIN_QUERY_POLL_MS = 10;

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
      this.thread = gg.tame.conduit.network.SocketThreads.start(() -> {
        while (running) {
          try {
            // Only between frames is there anything to poll: a timed read that gave up inside a
            // frame had already taken part of it off the socket, and the next read began mid-frame.
            // A read starts once a byte is pending and then runs under the login's own timeout.
            if (client.available() == 0) {
              if (client.hungUp()) throw new java.io.EOFException("the client closed during login");
              Thread.sleep(LOGIN_QUERY_POLL_MS);
              continue;
            }
            byte[] answer = connection.login().clientLoginQueryResponse(client.read(configuration.maxFrameBytes()));
            connection.writeUncompressed(answer);
            java.util.List<String> mods = gg.tame.conduit.modded.ForgeModList.clientMods(answer);
            if (mods != null) modInfo = new gg.tame.conduit.api.server.ModInfo(
                modClassifier.marker() == gg.tame.conduit.modded.FmlAddressMarkers.MarkerKind.FML3 ? "FML3" : "FML2",
                mods.stream().map(mod -> new gg.tame.conduit.api.server.ModInfo.Mod(mod, "")).toList());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
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

  void completeBackendLogin(BackendConnection connection, boolean forwardLoginSuccess) throws IOException {
    completeBackendLogin(connection, forwardLoginSuccess,
        new SessionSwitch.Translation(translationSupport, backendProtocol, backendDefinition, translator));
  }

  /**
   * Finishes LOGIN against {@code connection} using {@code target}'s translation rather than the
   * session's. During a switch the session's translation still belongs to the backend the player is
   * currently on, and must not be used to talk to the one being prepared.
   */
  void completeBackendLogin(BackendConnection connection, boolean forwardLoginSuccess,
                                    SessionSwitch.Translation target) throws IOException {
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
      if (forwardLoginSuccess && protocol.hasConfiguration()) {
        expectClientLoginAck = true;
        // From here Conduit reads the client as configuring, so Via has to as well, now rather than
        // when the client's own acknowledgement arrives: anything Conduit sends the backend first is
        // built with the client's Configuration ids. The first join announced the proxy's channels
        // in that gap, and Via, still reading the client as logging in, passed a 26.2 client's
        // plugin message (id 0x02) to a 1.20.4 backend untouched -- Finish Configuration there,
        // with 34 bytes after it, and the backend closed the connection. A switch arms its session
        // the same way; see armViaConfigurationBridge.
        advanceTranslatorPastClientLogin(translator);
      }
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
  private void runBackendConfigurationThroughVia(BackendConnection connection, SessionSwitch.Translation target) throws IOException {
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
  boolean viaEngine() {
    return translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator;
  }

  String viaStateDescription() {
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
  /**
   * Everything the client has sent that is already buffered, for a session the selector watches.
   *
   * <p>Only whole frames are read, so no read here waits: when the buffer runs out the worker
   * returns and the connection goes back to being watched, costing nothing until the next packet.
   * False ends the session.
   */
  private boolean relayBufferedFromClient() throws IOException {
    if (closed) return false;
    while (!closed && !client.sinkCongested() && client.nextFrameReady(configuration.maxFrameBytes())) {
      relayFromClient(client.read(configuration.maxFrameBytes()));
    }
    return !closed && !client.ended(configuration.maxFrameBytes());
  }

  /** One packet from the client, wherever it was read. */
  private void relayFromClient(byte[] packet) throws IOException {
    keepAlive.read(clientState.state(), packet);
    if (ProtocolTrace.enabled()) {
      try {
        ProtocolTrace.note("client packet " + clientState.state() + " id=0x"
            + Integer.toHexString(PlayPackets.packetId(packet)) + " len=" + packet.length);
      } catch (Exception ignored) { }
    }
    if (handleClientPacket(packet)) return;
    if (lifecycle.get() == SessionLifecycle.SWITCHING) {
      BackendConnection target = switching.switchingTarget;
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
      return;
    }
    if (awaitingBackendJoinGame.holding()) {
      // Dropped rather than queued, deliberately. What a client sends in this window is its
      // position and look for a world it is about to be moved out of, and the new backend will
      // place it itself; replaying any of it afterwards would fight that. The client resends
      // both within a tick of arriving.
      return;
    }
    BackendConnection target = switching.switchingTarget != null ? switching.switchingTarget : backend;
    if (target != null) {
      if (!forwardPluginMessage(packet, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.CLIENT_TO_PROXY, target)) {
        return;
      }
      byte[] outbound = towardBackend(clientState.state(), packet);
      if (outbound != null) {
        if (clientState.state() == ConnectionState.PLAY) {
          synchronized (secureChatLock) {
            sentSecureChat(target, packet);
            target.writeUncompressed(outbound);
          }
        } else {
          target.writeUncompressed(outbound);
        }
      }
      flushTranslatorExtras(target);
    }
  }
  private boolean handleClientPacket(byte[] packet) throws IOException {
    int id = PlayPackets.packetId(packet);
    rememberClientInformation(packet, id);
    // An answer about one of the proxy's own packs: the server never offered it and must not hear of it.
    if (resourcePacks.fromClient(clientState.state(), packet)) return true;
    if (cookieForProxy(packet)) return true;
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
      // acknowledgement is a stray packet. Withheld from the translator too: it was moved past
      // Login when Conduit was (see completeBackendLogin), and shown this one as well it would read
      // id 0x03 as the client finishing Configuration.
      return true;
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT_SESSION_UPDATE)) {
      // Kept for Player.getIdentifiedKey; the backend gets it as it came, being the one that checks it.
      try { chatSession = gg.tame.conduit.protocol.SecureChat.sessionUpdate(packet); } catch (IOException unreadable) { }
      return false;
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT_COMMAND)) {
      String command = PlayPackets.chatCommand(packet);
      if (protocol.capabilities().legacyPlayChat() && command.startsWith("/")) {
        command = command.substring(1);
      } else if (protocol.capabilities().legacyPlayChat() && !command.startsWith("/")) {
        // Plain chat, on the one packet that carries it before 1.19. The event used to fire for
        // commands instead, as "/name", and plain chat never raised it at all.
        if (muted()) return true;
        var chat = runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerChatEvent(this, command));
        if (chat.cancelled()) return true;
        // A rewrite goes on as a chat line of its own; the client's packet is withheld.
        return !chat.message().equals(command) && sendChatLineToServer(chat.message());
      }
      var execute = new gg.tame.conduit.api.event.command.CommandExecuteEvent(this, command);
      runtime.events().fire(execute);
      if (execute.cancelled()) return withheldCommand(packet);
      String effective = execute.command();
      if (!execute.forwardsToServer()) {
        // The client's own slash is gone already; dispatch strips one more, so it gets one back.
        // Without it "//lpv" ran "lpv", where Velocity runs the command registered as "/lpv", and a
        // backend's "//wand" was taken by any proxy command that happened to be called "wand".
        try { if (commands.dispatch(this, "/" + effective)) return withheldCommand(packet); }
        catch (RuntimeException exception) {
          gg.tame.conduit.log.ConduitLog.error("command failed", exception);
          return withheldCommand(packet);
        }
      }
      var forwarded = gg.tame.conduit.api.event.command.PostCommandEvent.Result.FORWARDED;
      if (effective.equals(command)) {
        commands.finished(this, "/" + command, forwarded);
        return false;
      }
      // Rewritten, and on its way to the backend. Before 1.19 the packet is the line alone; after,
      // the command may carry signatures over its arguments, which a rewrite would break.
      if (protocol.capabilities().legacyPlayChat() && sendChatLineToServer("/" + effective)) {
        commands.finished(this, "/" + effective, forwarded);
        return true;
      }
      gg.tame.conduit.log.ConduitLog.warn("A plugin rewrote /" + command + " for a client whose commands may be signed; the backend got it unchanged");
      commands.finished(this, "/" + command, forwarded);
      return false;
    }
    if (clientState.state() == ConnectionState.PLAY && !protocol.capabilities().legacyPlayChat()
        && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_CHAT)) {
      return relayModernChat(packet);
    }
    if (clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.PLAY_TAB_COMPLETE_REQUEST)) {
      PlayPackets.TabRequest request = PlayPackets.tabRequest(protocol, packet);
      // Only this request's reply may take Conduit's names. A reply can fail to come at all -- Via
      // dropped a 1.20.4 backend's empty one for a real 1.12.2 client -- and a name left pending then
      // went into the next reply, Conduit's own list of servers, which Tab turned into "/server /server".
      legacyTabRequest = null;
      var command = gg.tame.conduit.command.ParsedCommand.parseKeepEmpty(request.text());
      if (request.text().startsWith("/") && commands.handles(this, command.name(), command.arguments())) {
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
        BackendConnection target = switching.switchingTarget != null ? switching.switchingTarget : backend;
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
      BackendConnection target = switching.switchingTarget != null ? switching.switchingTarget : backend;
      if (target != null) {
        byte[] outbound = towardBackend(ConnectionState.CONFIGURATION, packet);
        if (outbound != null) target.writeUncompressed(outbound);
      }
      knownPacksAck.offer(Boolean.TRUE);
      return true;
    }
    if (clientState.state() == ConnectionState.CONFIGURATION && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.CONFIGURATION_FINISH)) {
      BackendConnection target = switching.switchingTarget != null ? switching.switchingTarget : backend;
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
      finishedConfiguration();
      return true;
    }
    return false;
  }
  /**
   * Advances the translator's view of the client past Login, without putting anything on a wire.
   *
   * <p>The translator moves the client between states by watching the packets that cause those
   * transitions. Conduit consumes the client's Login Acknowledged, so the transition is handed
   * over with one of its own, at the moment Conduit itself starts reading the client as configuring.
   */
  private void advanceTranslatorPastClientLogin(ProtocolTranslator translator) {
    if (!(translator instanceof gg.tame.conduit.viaversion.ConduitViaTranslator via)) return;
    synchronized (translatorLock) {
      try {
        via.clientToBackend(ConnectionState.LOGIN, PlayPackets.loginAcknowledged(protocol));
      } catch (IOException | RuntimeException failure) {
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
  /**
   * Tells the backend this connection listens on the BungeeCord channel, as a proxy that answers it.
   *
   * <p>A Paper or Spigot backend sends a plugin message only on a channel the connection has
   * registered, and drops anything else without a word. A vanilla client never registers
   * {@code bungeecord:main}, so without this every hub and queue plugin's request to the proxy was
   * dropped on the backend and never reached Conduit. Sent after each backend join, since a
   * registration belongs to one backend connection, and only while Conduit answers the channel.
   * The channels plugins listen on ({@code ConduitProxy#listenOnChannel}) go with it, for the same
   * reason: without them a backend plugin never reaches its proxy half.
   */
  void announceProxyChannels() {
    // The channels the client itself announced lead, since a backend after the first one is never
    // told them otherwise. They go the way the proxy's own do -- in the client's dialect, through
    // the translator -- and not as a packet built for the backend and written past it: that one was
    // written in Configuration to a backend a 1.8 client's switch had already taken into Play, which
    // a 1.20.4 backend read as Play packet 0x01 with the channel list left over, and closed.
    java.util.Set<String> channels = new java.util.LinkedHashSet<>(registeredChannels.channels());
    channels.addAll(runtime.proxyChannels());
    if (runtime.configuration().ops().messaging().bungeeCordChannel()) {
      channels.add(clientProtocol < 393 ? gg.tame.conduit.messaging.BungeeCordMessages.LEGACY_CHANNEL
          : gg.tame.conduit.messaging.BungeeCordMessages.MODERN_CHANNEL);
    }
    announceProxyChannels(java.util.List.copyOf(channels));
  }

  /** Registers these channels with the backend the player is on now, in the client's release's names. */
  public void announceProxyChannels(java.util.List<String> channels) {
    if (channels.isEmpty()) return;
    String register = clientProtocol < 393 ? gg.tame.conduit.modded.RegisteredChannels.LEGACY_REGISTER
        : gg.tame.conduit.modded.RegisteredChannels.REGISTER;
    sendPluginMessageToServer(register, String.join("\0", channels).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
  void replayClientInformation(BackendConnection target, ConnectionState state) {
    replayClientInformation(target, state,
        new SessionSwitch.Translation(translationSupport, backendProtocol, backendDefinition, translator));
  }

  void replayClientInformation(BackendConnection target, ConnectionState state, SessionSwitch.Translation via) {
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
  /**
   * Everything this backend has sent that is already buffered, for a session the selector watches.
   *
   * <p>Registered per backend, so the question the blocking reader answered by waiting on {@code
   * lock} -- which backend is the live one -- is answered by which registration woke: a frame from a
   * backend a switch has replaced is read and dropped, as it always was. False ends the session.
   */
  private boolean relayBufferedFromBackend(BackendConnection current) throws IOException {
    if (closed) return false;
    while (!closed && !current.sinkCongested() && current.nextFrameReady()) {
      if (!relayFromBackend(current)) return false;
    }
    if (current.ended()) {
      // The backend hung up. Not the session ending by itself: a lost backend may be fallen back
      // from, which is what the blocking reader's own IOException path decides.
      if (closed || lifecycle.get() != SessionLifecycle.CONNECTED) return false;
      handleBackendLoss(current);
    }
    return !closed;
  }

  /** One packet from a backend, wherever it was read. False ends the session. */
  private boolean relayFromBackend(BackendConnection current) {
    try {
      byte[] packet = current.readUncompressed();
        // The engine is taken with the backend it belongs to: read afterwards, a switch committing
        // between the check and the translation handed this backend's packet to its successor's.
        ProtocolTranslator engine;
        synchronized (lock) {
          if (lifecycle.get() != SessionLifecycle.CONNECTED || current != backend) return true;
          engine = translator;
        }
        ConnectionState backendState = current.state();
        if (backendState == ConnectionState.CONFIGURATION) current.login().onBackendPacket(packet, configuration.maxFrameBytes());
        byte[] translated;
        try {
          translated = towardClient(engine, backendState, packet);
        } catch (TranslationException translation) {
          gg.tame.conduit.log.ConduitLog.warn("Translation failed: " + translation.getMessage());
          ProtocolTrace.note("FAIL " + translation.getMessage());
          close();
          return false;
        }
        if (translated == null) {
          flushTranslatorExtras(current);
          // Via can cancel the backend's Join Game and emit the client's as an extra instead.
          resumeAfterSwitchedJoinGame(current);
          return true;
        }
        if (!forwardPluginMessage(translated, gg.tame.conduit.api.event.messaging.PluginMessageEvent.Direction.BACKEND_TO_PROXY, null)) return true;
        if (relayedTransfer(translated)) return true;
        if (clientState.state() == ConnectionState.PLAY && gg.tame.conduit.protocol.SecureChat.signedPlayerChat(protocol, translated)) {
          chatWindow(current).signedMessageSent();
        }
        // Everything below this point compensates for gaps in Conduit's own translators: a brand
        // the client never gets told, a command tree that has to be merged, a Configuration phase
        // one side of the pair does not have, a Play stream that must wait for the other side to
        // be ready. Via closes those gaps itself, and closing them twice is worse than not at all
        // — a second brand, a duplicated player entry or a synthesised transition the client has
        // already made is what a real 1.13 client drops the connection over. When Via is the
        // engine its output goes to the client as it stands.
        if (viaEngine()) {
          if (isPlayDisconnect(translated)) {
            if (kickedWhilePlaying(current, translated)) return true;
            return false;
          }
          writeClient(translated, true);
          flushTranslatorExtras(current);
          resumeAfterSwitchedJoinGame(current);
          return true;
        }
        var brand = BrandRewriter.rewrite(protocol, brandState(backendState), translated, configuration.maxFrameBytes());
        byte[] outbound;
        if (brand.isPresent()) {
          current.markBrandSeen();
          outbound = brand.get();
        } else {
          outbound = translated;
        }
        if (clientState.state() == ConnectionState.CONFIGURATION && isFinishConfiguration(translated)) {
          if (gg.tame.conduit.network.ConnectionSelector.onWorkerThread() && holdsPending()) {
            // A plugin's hold is waited out for up to MAX_HOLD_MILLIS, which is not a worker's to
            // spend. The wait and the Finish Configuration it gates go to a thread of their own;
            // the backend sends nothing but keep-alives until the client acknowledges, and it
            // cannot acknowledge before that thread writes.
            byte[] finish = translated;
            byte[] held = outbound;
            gg.tame.conduit.network.SocketThreads.start(() -> {
              try {
                finishingConfiguration();
                if (!deliverToClient(current, finish, held)) close();
              } catch (IOException | RuntimeException failed) {
                ProtocolTrace.note("finish configuration failed: " + failed.getMessage());
                close();
              }
            });
            return true;
          }
          finishingConfiguration();
        }
        return deliverToClient(current, translated, outbound);
      } catch (gg.tame.conduit.login.BackendLoginPipeline.Refused refused) {
        // Only a first server's configuration is read here -- a switch reads its target's itself --
        // so the player is still being connected to it.
        refusedInConfiguration(refused, current.server());
        return false;
      } catch (IOException exception) {
        if (closed || lifecycle.get() != SessionLifecycle.CONNECTED) return false;
        ProtocolTrace.note("backend I/O: " + exception.getMessage());
        handleBackendLoss(current);
      }
      return !closed;
  }
  /** The tail of {@link #relayFromBackend}: everything after a backend packet has been translated. */
  private boolean deliverToClient(BackendConnection current, byte[] translated, byte[] outbound) throws IOException {
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
      return true;
    }
    if (isPlayDisconnect(translated)) {
      return kickedWhilePlaying(current, outbound);
    }
    writeClient(outbound, true);
    flushTranslatorExtras(current);
    resumeAfterSwitchedJoinGame(current);
    if (clientState.state() == ConnectionState.PLAY && isPlayLogin(translated)) {
      emitSelfPlayerInfoIfNeeded();
    }
    return !closed;
  }
  /** Whether a plugin still holds the client's configuration open. */
  private boolean holdsPending() {
    PlayerConfigurationEvent entered = configurationEntered;
    return entered != null && entered.holds().stream().anyMatch(hold -> !hold.toCompletableFuture().isDone());
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
    // What follows dials a backend and waits for the client's configuration acknowledgement. On a
    // worker that answer never comes: the packet asking for it sits in the worker's deferred flush
    // set until this pass ends, so on 1.20.2+ every kick redirect timed out. Off the pool, as
    // handleBackendLoss is; the session is already SWITCHING, so nothing else commits meanwhile,
    // and whichever way it goes the session is closed from there.
    // Off the selector before this thread goes back to relaying, as handleBackendLoss does. A server
    // that kicks everyone as it stops closes their sockets right behind the kick, and the kicker's
    // end of stream, dispatched while the walk below was still dialling, was read as the session
    // being over: the client was closed under the fallback, and only whichever player's walk had
    // finished first was moved. Nothing below reads from the kicker; the reason is in the packet.
    discard(kicker);
    if (gg.tame.conduit.network.ConnectionSelector.onWorkerThread()) {
      gg.tame.conduit.network.SocketThreads.start(() -> {
        try {
          decideKick(kicker, disconnect, server);
        } catch (IOException | RuntimeException failed) {
          gg.tame.conduit.log.ConduitLog.error("Kick of " + username() + " from " + kicker.server().name()
              + " could not be handled: " + failed, failed);
          close();
        }
      });
      return true;
    }
    return decideKick(kicker, disconnect, server);
  }

  /** The listeners' decision and what it takes to carry it out; see {@link #kickedWhilePlaying}. */
  private boolean decideKick(BackendConnection kicker, byte[] disconnect,
                             gg.tame.conduit.api.server.RegisteredServer server) throws IOException {
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
        switching.switchTo(target, true);
        redirect.message().ifPresent(this::sendMessage);
        return true;
      } catch (Exception failed) {
        gg.tame.conduit.log.ConduitLog.warn("Redirect of kicked " + username() + " to " + redirect.server().getName()
            + " failed: " + failed.getMessage());
        // A target that refused in its configuration phase has already ended the session its own way.
        if (closed) return false;
        result = stay;
        if (failed instanceof SessionSwitch.RefusedSwitch refused) {
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
      // A backend that kicks a player while they are playing is, far more often than not, a backend
      // shutting down: "Server closed" is the last thing it says before the socket goes. Throwing
      // the player off the whole network for that is what a single server has to do and what a
      // proxy exists so that it need not do -- so the player is moved somewhere else and told why,
      // which is what an operator coming from BungeeCord expects and what a hub network needs.
      //
      // Only when there is somewhere to go. A kick with no candidate left still ends the session
      // with the backend's own packet, untouched, which is what the player saw before this existed.
      if (fallbackAfterKick(kicker, playDisconnectReason(disconnect))) return true;
      try { writeClient(disconnect, true); } finally { close(); }
    }
    return false;
  }

  /**
   * Moves a kicked player to another server rather than off the proxy.
   *
   * <p>The session is already SWITCHING and the kicker is discarded here, so the walk is the same
   * one a lost backend takes. True when somebody took the player; false when nothing did, and the
   * caller then ends the session the way it always did. Nothing is disconnected from in here: a
   * failure has to leave the original kick to be reported, not replace it with a worse message.
   */
  private boolean fallbackAfterKick(BackendConnection kicker, java.util.Optional<Text> reason) {
    if (!configuration.fallbackOnKick()) return false;
    discard(kicker);
    Set<String> failed = new HashSet<>();
    failed.add(ServerRegistry.normalize(kicker.server().name()));
    List<BackendServer> order = new java.util.ArrayList<>(
        selector.fallback(kicker.server().name(), failed, clientProtocol, modClassifier.family(), false));
    for (BackendServer server : order) {
      if (closed) return false;
      try {
        switching.switchTo(server, true);
        // The reason first, so the player reads why they moved before being told where to.
        reason.ifPresent(text -> sendMessage(Text.of("Kicked from " + kicker.server().name() + ": ")
            .color(TextColor.RED).append(text)));
        Messages.connected(this, server.name());
        ConduitMetrics.current().fallbackEvent();
        return true;
      } catch (SessionSwitch.RefusedSwitch refused) {
        failed.add(ServerRegistry.normalize(server.name()));
      } catch (Exception exception) {
        failed.add(ServerRegistry.normalize(server.name()));
      }
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
    announceProxyChannels();
  }
  ConnectionState brandState(ConnectionState backendState) {
    if (backendState == ConnectionState.PLAY) return ConnectionState.PLAY;
    if (protocol.hasConfiguration()) return ConnectionState.CONFIGURATION;
    return ConnectionState.PLAY;
  }
  /**
   * Declares the command tree again, built from the backend's last one and whatever is registered
   * and permitted now. Without this the tree a 1.13+ client parses against only changed when the
   * backend next sent one -- on a server switch -- so a command registered, unregistered or newly
   * permitted mid-session stayed invisible to the client until then.
   *
   * <p>Nothing to send before the backend has declared a tree: the merge happens when it does, and
   * picks up the change on its own.
   */
  @Override public void refreshCommands() {
    byte[] backendTree = lastBackendCommands;
    if (backendTree == null || !commandsDeclared || clientState.state() != ConnectionState.PLAY) return;
    // ponytail: a switch racing this may drop the resend or send one the new backend then replaces;
    // either way the switch declares its own tree right after, so the client ends up correct.
    try { writeClient(maybeMergeCommands(backendTree)); }
    catch (IOException | RuntimeException unsent) {
      ProtocolTrace.note("command tree resend skipped: " + unsent);
    }
  }
  byte[] maybeMergeCommands(byte[] packet) throws IOException {
    if (clientState.state() != ConnectionState.PLAY) return packet;
    int id = PlayPackets.packetId(packet);
    if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_DECLARE_COMMANDS)) return packet;
    try {
      byte[] merged = commands.declare(this, protocol, packet, selector.registry().names());
      commandsDeclared = true;
      lastBackendCommands = packet;
      gg.tame.conduit.protocol.ProfileTrace.dumpCommandMerge(protocol, packet, merged);
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

  boolean isFinishConfiguration(byte[] packet) throws IOException {
    return protocol.hasConfiguration() && protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.CONFIGURATION_FINISH);
  }
  private boolean isPlayDisconnect(byte[] packet) throws IOException {
    return clientState.state() == ConnectionState.PLAY && protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_DISCONNECT);
  }
  private boolean isPlayLogin(byte[] packet) throws IOException {
    return protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PlayPackets.packetId(packet), PacketKind.PLAY_LOGIN);
  }
  /** Hold backend Play until Join Game has been written; change_difficulty/entities crash if levelData is still null. */
  boolean deferPlayUntilReady(byte[] original, byte[] outbound, ConnectionState backendState) throws IOException {
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
  void flushDeferredPlay() throws IOException {
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
  void awaitDeferredFlush() throws IOException {
    while (flushingDeferredPlay) {
      if (closed) throw new IOException("session closed");
      try { lock.wait(); } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new java.io.InterruptedIOException("interrupted waiting for deferred Play");
      }
    }
  }
  void flushSwitchQueue(BackendConnection target) throws IOException {
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
    // The walk below opens a socket and logs in to it, once per candidate, each with its own
    // connect timeout. That is far too long to hold a relay thread, and on a watched session this
    // runs on one: a backend dying takes every player on it down this path at once, and the worker
    // pool is bounded, so the players still on healthy backends would stop being relayed while
    // their neighbours were dialled somewhere else. The same reasoning, and the same thread, as
    // transferTo. Everything above this point is immediate and stays where it is: the session is
    // SWITCHING and the lost backend is off the selector before this thread goes back to relaying,
    // so its end of stream cannot be dispatched again and read as the session being over.
    if (gg.tame.conduit.network.ConnectionSelector.onWorkerThread()) {
      gg.tame.conduit.network.SocketThreads.start(() -> fallbackFrom(lost));
      return;
    }
    fallbackFrom(lost);
  }

  /** The candidates, in order, until one takes the player or there is nothing left to try. */
  private void fallbackFrom(BackendConnection lost) {
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
        switching.switchTo(server, true);
        Messages.connected(this, server.name());
        if (redirected != null && redirected.server().getName().equalsIgnoreCase(server.name())) {
          redirected.message().ifPresent(this::sendMessage);
        }
        return;
      } catch (SessionSwitch.RefusedSwitch refused) {
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
    // A switch opens a socket and logs in to it, which is far too long to spend on a worker off the
    // bounded pool: that is a thread the other players are relayed on.
    if (gg.tame.conduit.network.ConnectionSelector.onWorkerThread()) {
      gg.tame.conduit.network.SocketThreads.start(() -> {
        if (switching.runSwitch(server).successful()) Messages.connected(this, server.name());
        else Messages.unavailable(this, server.name());
      });
      return true;
    }
    boolean ok = switching.runSwitch(server).successful();
    if (ok) Messages.connected(this, server.name());
    else Messages.unavailable(this, server.name());
    return ok;
  }

  // ---- PlayerConfigurationEvent: plugins' view of a Configuration phase Conduit relays itself ----
  /** The ENTERED event of the phase in progress, whose holds its finish waits for; null outside one or when none was fired. */
  private volatile PlayerConfigurationEvent configurationEntered;
  /**
   * Only for a phase this session relays packet by packet between two ends that both have one. Via
   * builds or translates its own, including the Finish Configuration, which is not Conduit's to hold.
   */
  private boolean configurationEvents() {
    return protocol.hasConfiguration() && backendDefinition.hasConfiguration() && !viaEngine();
  }
  private gg.tame.conduit.api.server.RegisteredServer registered(BackendConnection target) {
    return target == null ? null : runtime.registered(target.server().name()).orElse(null);
  }
  /** A switch, before the client is asked to reconfigure. Returns the nanoseconds listeners took. */
  long enteringConfiguration(BackendConnection target) {
    var server = registered(target);
    if (server == null || !configurationEvents()) return 0;
    long started = System.nanoTime();
    runtime.events().fire(new PlayerConfigurationEvent(this, server, PlayerConfigurationEvent.Stage.ENTERING));
    return System.nanoTime() - started;
  }
  /** The client is in Configuration: until it is told to finish, the proxy's packs go to it at once. */
  void enteredConfiguration(BackendConnection target) {
    var server = registered(target);
    if (server == null || !configurationEvents()) return;
    resourcePacks.configuring(true);
    configurationEntered = runtime.events().fire(new PlayerConfigurationEvent(this, server, PlayerConfigurationEvent.Stage.ENTERED));
  }
  /**
   * Just before Finish Configuration goes to the client: waits for the holds, bounded, then fires
   * FINISHING. Returns the nanoseconds it took. The backend meanwhile waits for the client's answer.
   */
  long finishingConfiguration() {
    PlayerConfigurationEvent entered = configurationEntered;
    if (entered == null) return 0;
    long started = System.nanoTime();
    // A hold is usually waiting on the client's answer to something just written to it.
    gg.tame.conduit.network.ConnectionSelector.flushPendingWrites();
    // A hold that failed is over as much as one that completed.
    var holds = entered.holds().stream().map(hold -> {
      var over = new java.util.concurrent.CompletableFuture<Void>();
      hold.whenComplete((result, failure) -> over.complete(null));
      return over;
    }).toArray(java.util.concurrent.CompletableFuture[]::new);
    try {
      java.util.concurrent.CompletableFuture.allOf(holds).get(PlayerConfigurationEvent.MAX_HOLD_MILLIS, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException slow) {
      gg.tame.conduit.log.ConduitLog.warn("Plugins held " + username() + "'s configuration for "
          + PlayerConfigurationEvent.MAX_HOLD_MILLIS + " ms; finishing it without them");
    } catch (java.util.concurrent.ExecutionException impossible) {
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    runtime.events().fire(new PlayerConfigurationEvent(this, entered.server(), PlayerConfigurationEvent.Stage.FINISHING));
    resourcePacks.configuring(false);
    return System.nanoTime() - started;
  }
  /** The client has finished the phase and is in Play. */
  private void finishedConfiguration() {
    PlayerConfigurationEvent entered = configurationEntered;
    if (entered == null) return;
    configurationEntered = null;
    resourcePacks.configuring(false);
    runtime.events().fire(new PlayerConfigurationEvent(this, entered.server(), PlayerConfigurationEvent.Stage.FINISHED));
    connectedToFirst();
  }
  /** A first join onto a backend without Configuration: its channels go once its Join Game is written. */
  private volatile boolean announceAtJoinGame;
  /** The first server, until the player counts as on it. */
  private volatile gg.tame.conduit.api.server.RegisteredServer firstConnected;
  private void connectedToFirst() {
    var first = firstConnected;
    if (first == null) return;
    firstConnected = null;
    loggedBackend = first.getName();
    gg.tame.conduit.log.ConduitLog.info(origin() + " joined backend '" + first.getName() + "'");
    if (!announceAtJoinGame) announceProxyChannels();
    runtime.events().fire(new gg.tame.conduit.api.event.player.PlayerServerConnectedEvent(this, java.util.Optional.empty(), first));
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
  void ensureCompatible(BackendServer server) throws IOException {
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
  void writeClient(byte[] packet) throws IOException { writeClient(packet, true); }
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
  void writeClient(byte[] packet, boolean flush) throws IOException {
    // The Play-phase half of this adapter reads Join Game and Player Info against Conduit's own
    // notion of the client's protocol. Via has already rewritten both into the client's dialect,
    // often into a different packet than the one the backend sent, so re-parsing them here is
    // reading a packet Conduit did not produce with a layout it only assumes. Login Success still
    // goes through it in every mode: that one carries the profile Conduit authenticated.
    ConnectionState adaptAs = viaEngine() && clientState.state() != ConnectionState.LOGIN
        ? ConnectionState.CLOSED
        : clientState.state();
    byte[] outbound = ProtocolProfileAdapter.backendToClient(protocol, adaptAs, packet, profile());
    // Join Game's online-mode flag is the exception: Via passes on the offline backend's false, and a
    // 26.x client then draws no player-list faces. The rewrite checks the id against the client's own
    // table and touches only the second-to-last byte after both trailing bytes read as booleans.
    if (adaptAs == ConnectionState.CLOSED && clientState.state() == ConnectionState.PLAY) {
      outbound = gg.tame.conduit.protocol.JoinGame.markOnlineMode(protocol, outbound, profile());
    }
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
    // A server's resource pack may be kept from the client, or another put in its place.
    outbound = resourcePacks.beforeWrite(writtenIn, outbound);
    if (outbound == null) return;
    display.beforeWrite(writtenIn, outbound);
    if (writtenIn == ConnectionState.LOGIN) compressBeforeLoginSuccess(outbound);
    try {
      if (flush) client.write(outbound);
      else client.writeUnflushed(outbound);
    } catch (gg.tame.conduit.network.PacketTransport.FrameTooLargeException tooLarge) {
      // Sent anyway, the client misreads the frame and drops with "Connection lost". Thrown on, it
      // reads as the backend going away and the player is moved to a fallback over it.
      gg.tame.conduit.log.ConduitLog.warn(origin() + ": " + tooLarge.getMessage() + "; disconnecting");
      disconnect("A server sent you a packet too large to deliver (" + tooLarge.bytes() + " bytes).");
      return;
    }
    awaitingBackendJoinGame.written(outbound);
    if (announceAtJoinGame && writtenIn == ConnectionState.PLAY && isPlayLogin(outbound)) {
      announceAtJoinGame = false;
      announceProxyChannels();
    }
    display.afterWrite(writtenIn, outbound);
    resourcePacks.afterWrite(writtenIn, outbound);
  }
  /**
   * Puts the client's link into the compressed format just ahead of its Login Success, the packet
   * Set Compression has to come before. The backend's own Set Compression never reaches the client
   * -- it is consumed on the backend link -- so without this every packet, chunk data included, went
   * to players at full size: the proxy's costliest direction, sent several times over.
   */
  private void compressBeforeLoginSuccess(byte[] packet) throws IOException {
    int threshold = configuration.compressionThreshold();
    if (threshold < 0 || client.compressing()
        || !protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PlayPackets.peekId(packet), PacketKind.LOGIN_SUCCESS)
        || !protocol.defines(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION)) {
      return;
    }
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(8);
    try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output,
          protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION));
      gg.tame.conduit.protocol.MinecraftOutput.varInt(output, threshold);
    }
    client.writeUnflushed(bytes.toByteArray());
    client.enableCompression(threshold, configuration.maxFrameBytes());
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
  /** As above, keeping an outright denial apart from silence so conduit.admin does not undo it. */
  @Override public Boolean permissionValue(String permission) {
    try { return runtime.permissions().permissionValue(this, permission); }
    catch (RuntimeException | LinkageError failure) {
      gg.tame.conduit.log.ConduitLog.error("permission provider failed on " + permission, failure);
      // Denied outright, not left unsaid: a provider that failed must not fall through to admin.
      return Boolean.FALSE;
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
  @Override public List<gg.tame.conduit.api.player.TabListEntry> backendTabListEntries() { return display.backendEntries(); }
  @Override public boolean updateBackendTabListEntry(gg.tame.conduit.api.player.TabListEntry entry) { return display.updateBackendEntry(java.util.Objects.requireNonNull(entry, "entry")); }
  @Override public boolean removeBackendTabListEntry(java.util.UUID id) { return id != null && display.removeBackendEntry(id); }
  @Override public boolean sendResourcePack(gg.tame.conduit.api.player.ResourcePack pack) { return resourcePacks.offer(java.util.Objects.requireNonNull(pack, "pack")); }
  @Override public boolean removeResourcePack(java.util.UUID id) { return id != null && resourcePacks.remove(id); }
  @Override public boolean clearResourcePacks() { return resourcePacks.clear(); }
  @Override public List<gg.tame.conduit.api.player.ResourcePack.Offered> resourcePacks() { return resourcePacks.offered(); }
  @Override public void playSound(gg.tame.conduit.api.player.Sound sound) { display.playSound(java.util.Objects.requireNonNull(sound, "sound")); }
  @Override public void playSound(gg.tame.conduit.api.player.Sound sound, double x, double y, double z) {
    display.playSound(java.util.Objects.requireNonNull(sound, "sound"), x, y, z);
  }
  /** The emitter's entity id is the one its backend gave it, which is also what this client sees it as there. */
  @Override public void playSound(gg.tame.conduit.api.player.Sound sound, gg.tame.conduit.api.player.Player emitter) {
    java.util.Objects.requireNonNull(sound, "sound");
    String here = currentBackend();
    if (!(emitter instanceof PlayerSession other) || other.closed || here.isEmpty() || !here.equalsIgnoreCase(other.currentBackend())) return;
    display.playSound(sound, other.display.entityId());
  }
  @Override public void stopSound(String name, gg.tame.conduit.api.player.Sound.Source source) { display.stopSound(name, source); }
  @Override public String currentBackend() {
    BackendConnection current = backend;
    return current == null ? "" : current.server().name();
  }
  @Override public void close() {
    closed = true;
    lifecycle.set(SessionLifecycle.CLOSED);
    synchronized (translatorLock) {
      new SessionSwitch.Translation(translationSupport, backendProtocol, backendDefinition, translator).close();
    }
    synchronized (lock) { lock.notifyAll(); }
    if (players != null) players.remove(this);
    for (BackendConnection connection : open) discard(connection);
    // The client's close waits out the linger its disconnect is sent in, and ended() runs the
    // disconnect event past every plugin listening. A mass kick reaches here from the workers
    // relaying everyone else, so that part goes to the selector's own ending threads instead.
    if (gg.tame.conduit.network.ConnectionSelector.onWorkerThread()) {
      // A worker only exists because the selector does, so this cannot be the call that opens it.
      gg.tame.conduit.network.ConnectionSelector selector = null;
      try { selector = runtime.connectionSelector(); } catch (IOException none) { }
      if (selector != null) {
        selector.runOffWorker(this::closeClient);
        return;
      }
    }
    closeClient();
  }
  private void closeClient() {
    client.close();
    // After the socket, which ends any display write still stuck on a client that stopped reading.
    display.close();
    resourcePacks.close();
    // A watched session has no thread of its own whose return means it is over, so a close from
    // anywhere -- a plugin disconnecting the player, a graceful shutdown, a newer login displacing
    // this one -- is the end of it, and what the connection owes back is owed from here. ended()
    // calls close() in turn, which is why it may only run once.
    if (watched) ended();
  }
}

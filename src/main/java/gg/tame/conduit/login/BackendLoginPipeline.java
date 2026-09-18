// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.login;

import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.forwarding.ForwardingRequest;
import gg.tame.conduit.forwarding.PlayerInfoForwarder;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketCompression;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;

/** Handles backend-only login packets that must never be handed to the client. */
public final class BackendLoginPipeline {
  public static final String MODERN_CHANNEL = "velocity:player_info";
  private final ProtocolDefinition protocol;
  private final PlayerInfoForwarder forwarder;
  private final PlayerProfile player;
  private final InetAddress clientAddress;
  private final PacketCompression compression;
  private final boolean hideLoginSuccess;
  private boolean forwardToClient = true;
  private ConnectionState state = ConnectionState.LOGIN;
  /**
   * Message ids of login queries handed to the client and not yet answered.
   *
   * <p>A set, not one id, because the exchange is pipelined. A real NeoForge 20.2.93 server sent
   * 21 {@code fml:loginwrapper} requests back to back in under a second without reading a single
   * reply, and FML's client handler answers the batch rather than each one in turn.
   *
   * <p>Guarded by this pipeline's monitor: the queries are added by whoever reads the backend and
   * settled by whoever reads the client, and those cannot be the same thread without deadlocking.
   */
  private final java.util.LinkedHashSet<Integer> pendingQueries = new java.util.LinkedHashSet<>();
  /** A backend cannot ask without bound: each outstanding query costs the client an answer. */
  public static final int MAX_PENDING_QUERIES = 256;
  private boolean clientCanAnswerQueries;
  public BackendLoginPipeline(ProtocolDefinition protocol, PlayerInfoForwarder forwarder, PlayerProfile player, InetAddress clientAddress, int maximumPacketBytes) {
    this(protocol, forwarder, player, clientAddress, maximumPacketBytes, false);
  }
  public BackendLoginPipeline(ProtocolDefinition protocol, PlayerInfoForwarder forwarder, PlayerProfile player, InetAddress clientAddress, int maximumPacketBytes, boolean hideLoginSuccess) {
    this.protocol = protocol; this.forwarder = forwarder; this.player = player; this.clientAddress = clientAddress;
    this.compression = new PacketCompression(maximumPacketBytes); this.hideLoginSuccess = hideLoginSuccess;
  }
  public boolean shouldForward() { return forwardToClient; }
  /**
   * Lets login queries Conduit has no answer for pass to the client. Only a connection whose client
   * is still in LOGIN may enable it: nobody else can produce a Login Plugin Response.
   */
  public void allowClientLoginQueries() { clientCanAnswerQueries = true; }
  /** Answers a backend's login query of the proxy's own choosing: the reply, or null to leave it as it would go. */
  public interface QueryAnswerer { byte[] answer(LoginPluginRequest request); }
  private volatile QueryAnswerer answerer;
  /**
   * Who is asked first about a login query Conduit has no answer of its own for (anything but modern
   * forwarding's). A reply goes to the backend as the player's; without one the query is relayed as
   * before, or fails a login with no client to relay it to.
   */
  public void answerQueriesWith(QueryAnswerer answerer) { this.answerer = answerer; }
  /** True while any login query handed to the client is still owed an answer to the backend. */
  public synchronized boolean awaitingLoginQuery() { return !pendingQueries.isEmpty(); }
  /** How many queries the client has been given and not yet answered. */
  public synchronized int outstandingLoginQueries() { return pendingQueries.size(); }
  /**
   * Checks the client's answer to the outstanding query and returns it for the backend.
   *
   * <p>The bytes are the client's own. Conduit never rewrites a mod list it did not author, and
   * the id check is against the backend's table so a mismatched pair fails here rather than
   * putting a packet the backend cannot read on the wire.
   */
  public synchronized byte[] clientLoginQueryResponse(byte[] packet) throws IOException {
    if (pendingQueries.isEmpty()) throw new IOException("no login plugin query is outstanding");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_PLUGIN_RESPONSE)) {
        throw new IOException("client answered a login query with packet id " + id);
      }
      int messageId = MinecraftInput.varInt(input);
      // Answers may come back in any order; only one that answers nothing is a fault.
      if (!pendingQueries.remove(messageId)) {
        throw new IOException("login plugin response answers no outstanding query (" + messageId + ")");
      }
    }
    return packet;
  }
  /**
   * The backend refused the player with a Login Disconnect, or a Configuration Disconnect before it
   * finished configuring them. The reason is JSON text, whatever form the packet carried it in.
   */
  public static final class Refused extends IOException {
    private final String reasonJson;
    public Refused(String reasonJson) {
      super("backend disconnected during login: " + gg.tame.conduit.text.TextCodec.fromJson(reasonJson).plain());
      this.reasonJson = reasonJson;
    }
    public String reasonJson() { return reasonJson; }
  }
  public ConnectionState state() { return state; }
  public PacketCompression compression() { return compression; }
  /** Returns a response packet only when a forwarding request was consumed. */
  public byte[] onBackendPacket(byte[] packet, int maximumPacketBytes) throws IOException {
    forwardToClient = true;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input); byte[] body = input.readAllBytes();
      if (state == ConnectionState.LOGIN) return handleLogin(id, body, maximumPacketBytes);
      if (state == ConnectionState.CONFIGURATION) return handleConfiguration(id, body, maximumPacketBytes);
      return null;
    }
  }
  private byte[] handleLogin(int id, byte[] body, int maximumPacketBytes) throws IOException {
    if (protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_DISCONNECT)) {
      // Never forwarded, first connection included: the session decides what the player is shown.
      // Relayed as it was, a first server's refusal closed the client with the next candidate still
      // untried, and a switch's refusal lost its reason altogether.
      forwardToClient = false;
      throw new Refused(MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(body)), maximumPacketBytes));
    }
    if (protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_ENCRYPTION_REQUEST)) {
      throw new IOException("backend requested encryption; Conduit does not terminate Minecraft online-mode encryption yet");
    }
    if (protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_SET_COMPRESSION)) {
      compression.enable(MinecraftInput.varInt(new DataInputStream(new ByteArrayInputStream(body))));
      forwardToClient = false;
      return null;
    }
    if (protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_SUCCESS)) {
      state = protocol.hasConfiguration() ? ConnectionState.CONFIGURATION : ConnectionState.PLAY;
      if (hideLoginSuccess) forwardToClient = false;
      return null;
    }
    if (!protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_PLUGIN_REQUEST)) {
      throw new IOException("unexpected backend login packet id " + id);
    }
    LoginPluginRequest request = LoginPluginRequest.decode(body, maximumPacketBytes);
    if (!MODERN_CHANNEL.equals(request.channel())) {
      QueryAnswerer answering = answerer;
      byte[] reply = answering == null ? null : answering.answer(request);
      if (reply == null) return relayToClient(request);
      forwardToClient = false;
      return new LoginPluginResponse(request.messageId(), true, reply).encode(protocol.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE));
    }
    if (forwarder.mode() != ForwardingMode.MODERN) throw new IOException("backend requested modern forwarding but Conduit is not configured for it");
    // The request names the highest forwarding version the backend reads. A backend from before that
    // byte existed sends no data and reads only the first version: Paper 1.13.1 does.
    if (request.data().length > 1) throw new IOException("malformed modern forwarding request payload");
    int requested = request.data().length == 0
        ? gg.tame.conduit.forwarding.ModernForwardingVersion.V1_DEFAULT
        : Byte.toUnsignedInt(request.data()[0]);
    int version = gg.tame.conduit.forwarding.ModernForwardingVersion.answerFor(requested);
    PlayerProfile canonical = AuthenticatedPlayerProfile.require(player);
    System.out.println("BACKEND LOGIN forwarding=" + forwarder.mode()
        + " forwardingVersion=" + version
        + (version == requested ? "" : " (backend reads up to " + requested + ")")
        + " clientProtocol=" + protocol.version().number()
        + " hideLoginSuccess=" + hideLoginSuccess
        + " " + canonical.summary());
    byte[] data;
    try { data = forwarder.payload(new ForwardingRequest(canonical, clientAddress, protocol.version().number(), version)); }
    catch (IllegalArgumentException exception) { throw new IOException("unsupported modern forwarding version", exception); }
    if (data.length < 32) throw new IOException("invalid authentication material");
    return new LoginPluginResponse(request.messageId(), true, data).encode(protocol.id(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE));
  }
  /**
   * A login query Conduit has no answer for — Forge's {@code fml:loginwrapper} above all — belongs
   * to the client. Answering it would be inventing a mod list; dropping it leaves the backend
   * waiting for a reply that never comes. It is forwarded, and the client's response goes back.
   */
  private synchronized byte[] relayToClient(LoginPluginRequest request) throws IOException {
    if (!clientCanAnswerQueries) {
      // A switch, or any caller with no client left in LOGIN: the player is already in Play on
      // another backend. Faking a mod list breaks a modded switch silently; failing does not.
      throw new IOException("backend asked login plugin channel " + request.channel()
          + " with no client login phase to answer it");
    }
    if (pendingQueries.size() >= MAX_PENDING_QUERIES) {
      throw new IOException("backend has " + pendingQueries.size() + " unanswered login queries outstanding");
    }
    pendingQueries.add(request.messageId());
    return null;
  }
  private byte[] handleConfiguration(int id, byte[] body, int maximumPacketBytes) throws IOException {
    if (protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_DISCONNECT)) {
      // A refusal like a Login Disconnect, only later -- NeoForge refusing a client's mods, a plugin
      // refusing a resource pack -- and the session's to decide in the same way. Relayed as it was,
      // no plugin heard of it, and the proxy took the dead backend for a lost one and ran a fallback
      // for a client that had already been shown the disconnect screen.
      DataInputStream input = new DataInputStream(new ByteArrayInputStream(body));
      boolean nbt = gg.tame.conduit.protocol.ProtocolEras.textComponentNbt(protocol.version().number());
      String reason;
      try {
        // A component is a string, list or compound tag, and nothing may follow it.
        if (nbt && (body.length == 0 || body[0] < 8 || body[0] > 10)) throw new IOException("not a text component");
        reason = nbt ? gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input) : MinecraftInput.string(input, maximumPacketBytes);
        if (input.available() != 0) throw new IOException("trailing bytes after the reason");
      } catch (IOException | RuntimeException unreadable) {
        // Not a reason Conduit can read, so not one it can decide on; the packet goes on as it came.
        return null;
      }
      forwardToClient = false;
      throw new Refused(reason);
    }
    if (protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_FINISH) && body.length == 0) {
      state = ConnectionState.PLAY;
    }
    return null;
  }
}

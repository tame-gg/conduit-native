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
  public BackendLoginPipeline(ProtocolDefinition protocol, PlayerInfoForwarder forwarder, PlayerProfile player, InetAddress clientAddress, int maximumPacketBytes) {
    this(protocol, forwarder, player, clientAddress, maximumPacketBytes, false);
  }
  public BackendLoginPipeline(ProtocolDefinition protocol, PlayerInfoForwarder forwarder, PlayerProfile player, InetAddress clientAddress, int maximumPacketBytes, boolean hideLoginSuccess) {
    this.protocol = protocol; this.forwarder = forwarder; this.player = player; this.clientAddress = clientAddress;
    this.compression = new PacketCompression(maximumPacketBytes); this.hideLoginSuccess = hideLoginSuccess;
  }
  public boolean shouldForward() { return forwardToClient; }
  public ConnectionState state() { return state; }
  public PacketCompression compression() { return compression; }
  /** Returns a response packet only when a forwarding request was consumed. */
  public byte[] onBackendPacket(byte[] packet, int maximumPacketBytes) throws IOException {
    forwardToClient = true;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input); byte[] body = input.readAllBytes();
      if (state == ConnectionState.LOGIN) return handleLogin(id, body, maximumPacketBytes);
      if (state == ConnectionState.CONFIGURATION) return handleConfiguration(id, body);
      return null;
    }
  }
  private byte[] handleLogin(int id, byte[] body, int maximumPacketBytes) throws IOException {
    if (protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_DISCONNECT)) {
      if (hideLoginSuccess) { forwardToClient = false; throw new IOException("backend disconnected during login"); }
      return null;
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
    if (!MODERN_CHANNEL.equals(request.channel())) throw new IOException("unknown login plugin channel");
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
  private byte[] handleConfiguration(int id, byte[] body) {
    if (protocol.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.CONFIGURATION_FINISH) && body.length == 0) {
      state = ConnectionState.PLAY;
    }
    return null;
  }
}

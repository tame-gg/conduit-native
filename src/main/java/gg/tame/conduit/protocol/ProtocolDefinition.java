package gg.tame.conduit.protocol;

import java.util.Map;

/** Packet-id registry for one supported protocol version. Add versions here, not to sessions. */
public final class ProtocolDefinition {
  private final ProtocolVersion version;
  private final Map<Key, Integer> ids;
  private ProtocolDefinition(ProtocolVersion version, Map<Key, Integer> ids) { this.version = version; this.ids = Map.copyOf(ids); }
  public ProtocolVersion version() { return version; }
  public int id(ConnectionState state, PacketDirection direction, PacketKind kind) {
    Integer id = ids.get(new Key(state, direction, kind));
    if (id == null) throw new IllegalArgumentException("packet is not defined for " + state + "/" + direction + ": " + kind);
    return id;
  }
  public boolean is(ConnectionState state, PacketDirection direction, int id, PacketKind kind) { return this.id(state, direction, kind) == id; }
  public static ProtocolDefinition forVersion(int number) {
    if (number != ProtocolVersion.MINECRAFT_1_20_4.number()) throw new IllegalArgumentException("unsupported Minecraft protocol: " + number + "; currently supported: 765 (1.20.4)");
    return MODERN_1_20_4;
  }
  private static final ProtocolDefinition MODERN_1_20_4 = new ProtocolDefinition(ProtocolVersion.MINECRAFT_1_20_4, Map.ofEntries(
      Map.entry(new Key(ConnectionState.AWAITING_HANDSHAKE, PacketDirection.CLIENT_TO_SERVER, PacketKind.HANDSHAKE), 0),
      Map.entry(new Key(ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_REQUEST), 0),
      Map.entry(new Key(ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING), 1),
      Map.entry(new Key(ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST), 0),
      Map.entry(new Key(ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING), 1),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START), 0),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT), 0),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST), 1),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE), 1),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS), 2),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION), 3),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE), 2),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED), 3),
      Map.entry(new Key(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST), 4),
      Map.entry(new Key(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH), 2),
      Map.entry(new Key(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH), 2),
      Map.entry(new Key(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN), 0x29)
  ));
  private record Key(ConnectionState state, PacketDirection direction, PacketKind kind) { }
}

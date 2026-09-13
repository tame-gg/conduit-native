package gg.tame.conduit.protocol;

import java.util.HashMap;
import java.util.Map;

/** Packet-id registry for one supported protocol version. Add versions here, not to sessions. */
public final class ProtocolDefinition {
  private final ProtocolVersion version;
  private final boolean configurationPhase;
  private final Map<Key, Integer> ids;
  private ProtocolDefinition(ProtocolVersion version, boolean configurationPhase, Map<Key, Integer> ids) {
    this.version = version; this.configurationPhase = configurationPhase; this.ids = Map.copyOf(ids);
  }
  public ProtocolVersion version() { return version; }
  public boolean hasConfiguration() { return configurationPhase; }
  public int id(ConnectionState state, PacketDirection direction, PacketKind kind) {
    Integer id = ids.get(new Key(state, direction, kind));
    if (id == null) throw new IllegalArgumentException("packet is not defined for " + version.displayName() + " " + state + "/" + direction + ": " + kind);
    return id;
  }
  public boolean defines(ConnectionState state, PacketDirection direction, PacketKind kind) {
    return ids.containsKey(new Key(state, direction, kind));
  }
  public boolean is(ConnectionState state, PacketDirection direction, int id, PacketKind kind) {
    Integer expected = ids.get(new Key(state, direction, kind));
    return expected != null && expected == id;
  }
  public static ProtocolDefinition forVersion(int number) {
    if (number == ProtocolVersion.MINECRAFT_1_20_1.number()) return V1_20_1;
    if (number == ProtocolVersion.MINECRAFT_1_20_4.number()) return V1_20_4;
    throw new IllegalArgumentException("unsupported Minecraft protocol: " + number + "; supported: 763 (1.20.1), 765 (1.20.4)");
  }
  private static ProtocolDefinition define(ProtocolVersion version, boolean configuration, Object... entries) {
    Map<Key, Integer> ids = new HashMap<>();
    for (int index = 0; index < entries.length; index += 4) {
      ids.put(new Key((ConnectionState) entries[index], (PacketDirection) entries[index + 1], (PacketKind) entries[index + 2]), (Integer) entries[index + 3]);
    }
    return new ProtocolDefinition(version, configuration, ids);
  }
  private static final ProtocolDefinition V1_20_4 = define(ProtocolVersion.MINECRAFT_1_20_4, true,
      ConnectionState.AWAITING_HANDSHAKE, PacketDirection.CLIENT_TO_SERVER, PacketKind.HANDSHAKE, 0,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_REQUEST, 0,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING, 1,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST, 0,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING, 1,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START, 0,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT, 0,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST, 1,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE, 1,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS, 2,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION, 3,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE, 2,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ACKNOWLEDGED, 3,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST, 4,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH, 2,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH, 2,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE, 0,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x29,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x18,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION, 0x67,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x69,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x11,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x0A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x0B
  );
  private static final ProtocolDefinition V1_20_1 = define(ProtocolVersion.MINECRAFT_1_20_1, false,
      ConnectionState.AWAITING_HANDSHAKE, PacketDirection.CLIENT_TO_SERVER, PacketKind.HANDSHAKE, 0,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_REQUEST, 0,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING, 1,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST, 0,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING, 1,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START, 0,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT, 0,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST, 1,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE, 1,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS, 2,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION, 3,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_PLUGIN_RESPONSE, 2,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST, 4,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x28,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x17,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x64,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x0F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x10,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x09
  );
  private record Key(ConnectionState state, PacketDirection direction, PacketKind kind) { }
}

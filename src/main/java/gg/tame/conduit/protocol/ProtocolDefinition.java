package gg.tame.conduit.protocol;

import java.util.HashMap;
import java.util.Map;

/** Packet-id registry for one supported protocol version. Add versions here, not to sessions. */
public final class ProtocolDefinition {
  private final ProtocolVersion version;
  private final ProtocolCapabilities capabilities;
  private final Map<Key, Integer> ids;
  private ProtocolDefinition(ProtocolVersion version, ProtocolCapabilities capabilities, Map<Key, Integer> ids) {
    this.version = version; this.capabilities = capabilities; this.ids = Map.copyOf(ids);
  }
  public ProtocolVersion version() { return version; }
  public ProtocolCapabilities capabilities() { return capabilities; }
  public boolean hasConfiguration() { return capabilities.configurationPhase(); }
  public boolean loginShouldAuthenticate() { return capabilities.loginShouldAuthenticate(); }
  public boolean knownPacks() { return capabilities.knownPacks(); }
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
    ProtocolDefinition definition = BY_NUMBER.get(number);
    if (definition == null) {
      throw new IllegalArgumentException("unsupported Minecraft protocol: " + number
          + "; codecs: 763 (1.20.1), 765 (1.20.4), 776 (26.2)");
    }
    return definition;
  }
  public static boolean hasCodec(int number) { return BY_NUMBER.containsKey(number); }
  private static ProtocolDefinition define(ProtocolVersion version, ProtocolCapabilities capabilities, Object... entries) {
    Map<Key, Integer> ids = new HashMap<>();
    for (int index = 0; index < entries.length; index += 4) {
      ids.put(new Key((ConnectionState) entries[index], (PacketDirection) entries[index + 1], (PacketKind) entries[index + 2]), (Integer) entries[index + 3]);
    }
    return new ProtocolDefinition(version, capabilities, ids);
  }
  private static final ProtocolDefinition V1_20_4 = define(ProtocolVersion.MINECRAFT_1_20_4, new ProtocolCapabilities(true, false),
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
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_DISCONNECT, 1,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KEEP_ALIVE, 3,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x29,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x18,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION, 0x67,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x69,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x11,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x3E,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x0A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x0B
  );
  private static final ProtocolDefinition V1_20_1 = define(ProtocolVersion.MINECRAFT_1_20_1, new ProtocolCapabilities(false, false),
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
  private static final ProtocolDefinition V26_2 = define(ProtocolVersion.MINECRAFT_26_2, new ProtocolCapabilities(true, true, true),
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
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE, 1,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH, 3,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH, 3,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KNOWN_PACKS, 0x0E,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_KNOWN_PACKS, 0x07,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_REGISTRY, 0x07,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_RESET_CHAT, 0x06,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_DISCONNECT, 2,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KEEP_ALIVE, 4,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_KEEP_ALIVE, 4,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x31,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x18,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION, 0x76,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x79,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x0F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x46,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x07,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x0F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x10
  );
  private static final Map<Integer, ProtocolDefinition> BY_NUMBER = Map.of(
      ProtocolVersion.MINECRAFT_1_20_1.number(), V1_20_1,
      ProtocolVersion.MINECRAFT_1_20_4.number(), V1_20_4,
      ProtocolVersion.MINECRAFT_26_2.number(), V26_2);
  private record Key(ConnectionState state, PacketDirection direction, PacketKind kind) { }
}

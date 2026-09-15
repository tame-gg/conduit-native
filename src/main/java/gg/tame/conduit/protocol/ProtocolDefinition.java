package gg.tame.conduit.protocol;

import java.util.Map;

/** Packet-id registry for one supported protocol version. Add versions here, not to sessions. */
public final class ProtocolDefinition {
  private final ProtocolVersion version;
  private final ProtocolCapabilities capabilities;
  private final int[][][] ids;
  private ProtocolDefinition(ProtocolVersion version, ProtocolCapabilities capabilities, int[][][] ids) {
    this.version = version; this.capabilities = capabilities; this.ids = ids;
  }
  public ProtocolVersion version() { return version; }
  public ProtocolCapabilities capabilities() { return capabilities; }
  public boolean hasConfiguration() { return capabilities.configurationPhase(); }
  public boolean loginShouldAuthenticate() { return capabilities.loginShouldAuthenticate(); }
  public boolean knownPacks() { return capabilities.knownPacks(); }
  public int id(ConnectionState state, PacketDirection direction, PacketKind kind) {
    int id = lookup(state, direction, kind);
    if (id < 0) throw new IllegalArgumentException("packet is not defined for " + version.displayName() + " " + state + "/" + direction + ": " + kind);
    return id;
  }
  public boolean defines(ConnectionState state, PacketDirection direction, PacketKind kind) {
    return lookup(state, direction, kind) >= 0;
  }
  public boolean is(ConnectionState state, PacketDirection direction, int id, PacketKind kind) {
    int expected = lookup(state, direction, kind);
    return expected >= 0 && expected == id;
  }
  private int lookup(ConnectionState state, PacketDirection direction, PacketKind kind) {
    return ids[state.ordinal()][direction.ordinal()][kind.ordinal()];
  }
  public static ProtocolDefinition forVersion(int number) {
    ProtocolDefinition definition = BY_NUMBER.get(number);
    if (definition == null) {
      throw new IllegalArgumentException("unsupported Minecraft protocol: " + number
          + "; codecs: 393 (1.13), 763 (1.20.1), 765 (1.20.4), 766 (1.20.5), 776 (26.2)");
    }
    return definition;
  }
  public static boolean hasCodec(int number) { return BY_NUMBER.containsKey(number); }
  public ProtocolFamily family() { return version.family(); }
  private static ProtocolDefinition define(ProtocolVersion version, ProtocolCapabilities capabilities, Object... entries) {
    int[][][] ids = new int[ConnectionState.values().length][PacketDirection.values().length][PacketKind.values().length];
    for (int[][] byDirection : ids) {
      for (int[] byKind : byDirection) java.util.Arrays.fill(byKind, -1);
    }
    for (int index = 0; index < entries.length; index += 4) {
      ConnectionState state = (ConnectionState) entries[index];
      PacketDirection direction = (PacketDirection) entries[index + 1];
      PacketKind kind = (PacketKind) entries[index + 2];
      ids[state.ordinal()][direction.ordinal()][kind.ordinal()] = (Integer) entries[index + 3];
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
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_PLUGIN_MESSAGE, 1,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_DISCONNECT, 1,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KEEP_ALIVE, 3,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_KEEP_ALIVE, 3,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_REGISTRY, 0x05,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_CLIENT_INFORMATION, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x29,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x18,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION, 0x67,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x69,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x11,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x3C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE, 0x3B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x24,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x15,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x0A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x0B,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x09,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_POSITION, 0x3E,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TELEPORT_CONFIRM, 0x00,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION, 0x17,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION_LOOK, 0x18,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_LOOK, 0x19,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_FLYING, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA, 0x25,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UNLOAD_CHUNK, 0x1F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_LIGHT, 0x28,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DIFFICULTY, 0x0B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_GAME_EVENT, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ABILITIES, 0x36,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_HELD_ITEM, 0x51,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_POSITION, 0x54,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_DESTROY, 0x40,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_RECIPES, 0x73,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAGS, 0x74,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_VIEW_POSITION, 0x52,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_VIEW_DISTANCE, 0x53,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SIMULATION_DISTANCE, 0x60,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_BATCH_START, 0x0D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_BATCH_FINISHED, 0x0C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UNLOCK_RECIPES, 0x3F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1D
  );
  /** Protocol 766 = Minecraft 1.20.5/1.20.6. IDs from public PrismarineJS minecraft-data. */
  private static final ProtocolDefinition V1_20_5 = define(ProtocolVersion.MINECRAFT_1_20_5,
      new ProtocolCapabilities(true, false, true, false, true, true, true, true),
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
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE, 0x01,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_PLUGIN_MESSAGE, 0x02,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_DISCONNECT, 0x02,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH, 0x03,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH, 0x03,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KEEP_ALIVE, 0x04,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_KEEP_ALIVE, 0x04,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_RESET_CHAT, 0x06,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_REGISTRY, 0x07,
      ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_KNOWN_PACKS, 0x0E,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_KNOWN_PACKS, 0x07,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_CLIENT_INFORMATION, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x2B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x19,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x12,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION, 0x69,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x6C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x11,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x3E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE, 0x3D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x26,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x18,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x0B,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x0C,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x0A
  );
  /**
   * Protocol 393 = Minecraft 1.13. Packet IDs from PrismarineJS minecraft-data {@code 1.13/protocol.json}.
   * States: HANDSHAKING / STATUS / LOGIN / PLAY — no CONFIGURATION.
   */
  private static final ProtocolDefinition V1_13 = define(ProtocolVersion.MINECRAFT_1_13, ProtocolCapabilities.flattening113(),
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
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x25,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x19,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x0A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x0E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHAT, 0x0E,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT, 0x02,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x10,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x05,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_COMMANDS, 0x11,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x30,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x21,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x0E,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_FLYING, 0x0F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION, 0x10,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION_LOOK, 0x11,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_LOOK, 0x12,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_POSITION, 0x32,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TELEPORT_CONFIRM, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_SEND, 0x37,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_RESOURCE_PACK_STATUS, 0x1D,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x04,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA, 0x22,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UNLOAD_CHUNK, 0x1F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DIFFICULTY, 0x0D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_GAME_EVENT, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ABILITIES, 0x2E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_HELD_ITEM, 0x3D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_POSITION, 0x49,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_DESTROY, 0x35,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_RECIPES, 0x54,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAGS, 0x55,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1C
  );
  private static final ProtocolDefinition V1_20_1 = define(ProtocolVersion.MINECRAFT_1_20_1, ProtocolCapabilities.legacyPlay(),
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
  private static final ProtocolDefinition V26_2 = define(ProtocolVersion.MINECRAFT_26_2,
      new ProtocolCapabilities(true, true, true, true, true, true, true, true),
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
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE, 0x45,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x07,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x0F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED, 0x10,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x0E,
      ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_CLIENT_INFORMATION, 0x00
  );
  private static final Map<Integer, ProtocolDefinition> BY_NUMBER = Map.ofEntries(
      Map.entry(ProtocolVersion.MINECRAFT_1_13.number(), V1_13),
      Map.entry(ProtocolVersion.MINECRAFT_1_20_1.number(), V1_20_1),
      Map.entry(ProtocolVersion.MINECRAFT_1_20_4.number(), V1_20_4),
      Map.entry(ProtocolVersion.MINECRAFT_1_20_5.number(), V1_20_5),
      Map.entry(ProtocolVersion.MINECRAFT_26_2.number(), V26_2));
}

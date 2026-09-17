package gg.tame.conduit.protocol;

import java.util.Map;

/**
 * Packet-id registry for one supported protocol version. Add versions here, not to sessions.
 *
 * <p>Versions enter the registry one of two ways. A <em>declared</em> definition
 * spells out its whole table, which is what a version with a genuinely novel
 * layout needs. A <em>derived</em> definition ({@link #derive}) inherits an
 * existing table and applies a {@link ProtocolRevision} delta, which is what the
 * long tail of point releases needs &mdash; most of them move a handful of play
 * ids and leave handshake, status and login alone. Deriving keeps the cost of a
 * new version proportional to what the version actually changed, instead of
 * requiring a full hand-copied table per protocol number across 1.13&ndash;26.2.
 *
 * <p>Every definition carries a {@link CodecStatus} and a provenance note, and
 * neither is merged with translation status: having a codec for a protocol says
 * nothing about whether a translator to some other protocol exists.
 */
public final class ProtocolDefinition {
  private final ProtocolVersion version;
  private final ProtocolCapabilities capabilities;
  private final int[][][] ids;
  private final CodecStatus codecStatus;
  private final String source;
  private ProtocolDefinition(ProtocolVersion version, ProtocolCapabilities capabilities, int[][][] ids,
                             CodecStatus codecStatus, String source) {
    this.version = version; this.capabilities = capabilities; this.ids = ids;
    this.codecStatus = codecStatus; this.source = source;
  }
  public ProtocolVersion version() { return version; }
  public ProtocolCapabilities capabilities() { return capabilities; }
  /** How far this protocol's packet table has been authored and validated. */
  public CodecStatus codecStatus() { return codecStatus; }
  /** Where this protocol's packet ids came from. */
  public String source() { return source; }
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
          + "; codecs: " + describeCodecs());
    }
    return definition;
  }
  public static boolean hasCodec(int number) { return BY_NUMBER.containsKey(number); }

  /** Codec status for any protocol number, including ones with no table at all. */
  public static CodecStatus codecStatus(int number) {
    ProtocolDefinition definition = BY_NUMBER.get(number);
    return definition == null ? CodecStatus.NONE : definition.codecStatus();
  }

  /** Every protocol number that has a packet table, in registration order. */
  public static Map<Integer, ProtocolDefinition> all() {
    return Map.copyOf(BY_NUMBER);
  }

  private static String describeCodecs() {
    StringBuilder text = new StringBuilder();
    BY_NUMBER.values().stream()
        .sorted(java.util.Comparator.comparingInt(d -> d.version().number()))
        .forEach(d -> {
          if (text.length() > 0) text.append(", ");
          text.append(d.version().number()).append(" (").append(d.version().displayName())
              .append('/').append(d.codecStatus()).append(')');
        });
    return text.toString();
  }

  public ProtocolFamily family() { return version.family(); }

  /**
   * Builds a table for a version that inherits {@code base} and applies the
   * revision's deltas. Only the mappings the release actually changed need to be
   * listed; {@link PacketMapping#removed} drops a packet the release deleted so a
   * derived table never keeps exposing a kind its protocol no longer has.
   */
  public static ProtocolDefinition derive(ProtocolDefinition base, ProtocolRevision revision) {
    if (base == null) throw new IllegalArgumentException("derive requires a base definition");
    int[][][] ids = new int[base.ids.length][][];
    for (int state = 0; state < base.ids.length; state++) {
      ids[state] = new int[base.ids[state].length][];
      for (int direction = 0; direction < base.ids[state].length; direction++) {
        ids[state][direction] = base.ids[state][direction].clone();
      }
    }
    for (PacketMapping delta : revision.deltas()) {
      ids[delta.state().ordinal()][delta.direction().ordinal()][delta.kind().ordinal()] = delta.id();
    }
    String provenance = revision.source() + " (derived from " + base.version().displayName() + ")";
    // A null capability set means "inherit the base version's"; revisions cannot
    // look the base up themselves because the registry is still being built.
    ProtocolCapabilities capabilities =
        revision.capabilities() == null ? base.capabilities() : revision.capabilities();
    return new ProtocolDefinition(revision.version(), capabilities, ids, revision.status(), provenance);
  }

  private static ProtocolDefinition define(ProtocolVersion version, ProtocolCapabilities capabilities, Object... entries) {
    return define(version, capabilities, CodecStatus.DECLARED, "authored packet table", entries);
  }

  private static ProtocolDefinition define(ProtocolVersion version, ProtocolCapabilities capabilities,
                                           CodecStatus status, String source, Object... entries) {
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
    return new ProtocolDefinition(version, capabilities, ids, status, source);
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
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT, 0x05,
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
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SERVER_DATA, 0x49,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_BORDER_INIT, 0x23,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_TIME, 0x62,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_TICKING_STATE, 0x6E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_STEP_TICK, 0x6F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_CONTENT, 0x13,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_SLOT, 0x15,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ENTITY_METADATA, 0x56,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ATTRIBUTES, 0x71,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ADVANCEMENTS, 0x70,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_HEALTH, 0x5B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_EXPERIENCE, 0x5A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_UPDATE, 0x09,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLAYER_DIGGING, 0x21,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MULTI_BLOCK_CHANGE, 0x47,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SWING_ARM, 0x33,
      // Entity/world packets a real 1.20.4 server sends during ordinary play,
      // observed on the wire while proxying an official 1.13 client.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BUNDLE_DELIMITER, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_ENTITY, 0x01,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ACKNOWLEDGE_BLOCK_CHANGE, 0x05,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DAMAGE_EVENT, 0x19,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_PARTICLES, 0x27,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_RELATIVE_MOVE, 0x2C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_MOVE_LOOK, 0x2D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_LOOK, 0x2E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_CHAT, 0x37,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_HEAD_ROTATION, 0x46,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_VELOCITY, 0x58,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_EQUIPMENT, 0x59,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SOUND_EFFECT, 0x66,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_COLLECT_ITEM, 0x6C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_TELEPORT, 0x6D,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLOSE_WINDOW, 0x0E,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_ENTITY_ACTION, 0x22,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_BLOCK_PLACE, 0x35,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ANIMATION, 0x03,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_HURT_ANIMATION, 0x22,
      // 1.17 split the 1.13 combat_event into these three standalone packets.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_END_COMBAT, 0x38,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTER_COMBAT, 0x39,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DEATH_COMBAT, 0x3A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_COMMAND, 0x08,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_EVENT, 0x26,
      // Inventory / container / item interaction.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_WINDOW, 0x31,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CLOSE_WINDOW_CLIENTBOUND, 0x12,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WINDOW_PROPERTY, 0x14,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLICK_WINDOW, 0x0D,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SET_CARRIED_ITEM, 0x2C,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CREATIVE_SLOT, 0x2F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PICK_ITEM, 0x1D,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_USE_ITEM, 0x36,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_INTERACT_ENTITY, 0x13,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_ABILITIES, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_EXPLOSION, 0x1E,
      // The remainder of the 1.20.4 clientbound table Conduit needs so that every
      // packet a 1.13 backend can send has somewhere to go.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_PASSENGERS, 0x5D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN, 0x45,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_EXPERIENCE_ORB, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_EFFECT, 0x72,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_REMOVE_ENTITY_EFFECT, 0x41,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_COOLDOWN, 0x16,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_BREAK_ANIMATION, 0x06,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_SIGN_EDITOR, 0x32,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_LIST_HEADER, 0x6A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_STATISTICS, 0x04,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BOSS_BAR, 0x0A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_NBT_QUERY_RESPONSE, 0x6B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_ENTITY_DATA, 0x07,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_ACTION, 0x08,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SCOREBOARD_OBJECTIVE, 0x5C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TEAMS, 0x5E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_SCORE, 0x5F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISPLAY_SCOREBOARD, 0x55,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_STOP_SOUND, 0x68,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CAMERA, 0x50,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ATTACH_ENTITY, 0x57,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_FACE_PLAYER, 0x3D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CRAFT_RECIPE_RESPONSE, 0x35,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SELECT_ADVANCEMENT_TAB, 0x48,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_VEHICLE_MOVE, 0x2F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MAP_DATA, 0x2A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TRADE_LIST, 0x2B,
      // 1.20.4-only clientbound packets, mapped so the id is recognised.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISGUISED_CHAT, 0x1C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DELETE_MESSAGE, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHAT_SUGGESTIONS, 0x17,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_BIOMES, 0x0E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CLEAR_TITLES, 0x0F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_HORSE_SCREEN, 0x21,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_BOOK, 0x30,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PING, 0x33,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PONG_RESPONSE, 0x34,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESET_SCORE, 0x42,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_POP, 0x43,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_PUSH, 0x44,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ACTION_BAR, 0x4A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BORDER_CENTER, 0x4B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BORDER_LERP_SIZE, 0x4C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BORDER_SIZE, 0x4D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BORDER_WARNING_DELAY, 0x4E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BORDER_WARNING_DISTANCE, 0x4F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_SUBTITLE, 0x61,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_TITLE_TEXT, 0x63,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_TITLE_TIMES, 0x64
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
  // ---------------------------------------------------------------------------
  // Pre-flattening releases. These exist so a 1.7.6, 1.8.x or 1.12.2 client can be
  // admitted at all: the proxy needs a table for the client's own protocol before a
  // session exists, and without one such a client was refused at the handshake even
  // though Via has a translation path from it to every modern backend.
  //
  // They are deliberately partial, and that is the design rather than a shortfall.
  // Conduit does not translate these versions -- ViaRewind and ViaVersion do -- so
  // the only ids it needs are the ones it acts on itself. A kind left out reads as
  // "this release has no such packet", which is what the lookup returns for it and
  // what every caller guards on, and is far safer than deriving a full table from a
  // neighbour and inheriting ids that were never checked against the release.
  // ---------------------------------------------------------------------------
  private static final ProtocolDefinition V1_7_6 = define(ProtocolVersion.MINECRAFT_1_7_10,
      ProtocolCapabilities.preFlattening(false, false), CodecStatus.DECLARED,
      "published packet ids for 1.7.6-1.7.10; the subset Conduit inspects on its own behalf, with translation left to ViaRewind",
      ConnectionState.AWAITING_HANDSHAKE, PacketDirection.CLIENT_TO_SERVER, PacketKind.HANDSHAKE, 0x00,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE, 0x01,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START, 0x00,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT, 0x00,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST, 0x01,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS, 0x02,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT, 0x01,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x01,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x15,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_FLYING, 0x03,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x00,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_LOOK, 0x05,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLAYER_DIGGING, 0x07,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x17,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION_LOOK, 0x06,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SWING_ARM, 0x0A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x14,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ABILITIES, 0x39,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_UPDATE, 0x23,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHAT, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA, 0x21,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x40,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_DESTROY, 0x13,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_GAME_EVENT, 0x2B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_HELD_ITEM, 0x09,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x01,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MULTI_BLOCK_CHANGE, 0x22,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x38,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN, 0x07,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_POSITION, 0x08,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x3F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_CONTENT, 0x30,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_SLOT, 0x2F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ENTITY_METADATA, 0x1C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_EXPERIENCE, 0x1F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_POSITION, 0x05,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x3A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ATTRIBUTES, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_HEALTH, 0x06,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_TIME, 0x03,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING, 0x01,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_REQUEST, 0x00,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING, 0x01,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST, 0x00);

  private static final ProtocolDefinition V1_8 = define(ProtocolVersion.MINECRAFT_1_8_9,
      ProtocolCapabilities.preFlattening(true, true), CodecStatus.DECLARED,
      "published packet ids for 1.8.x; the subset Conduit inspects on its own behalf, with translation left to ViaRewind",
      ConnectionState.AWAITING_HANDSHAKE, PacketDirection.CLIENT_TO_SERVER, PacketKind.HANDSHAKE, 0x00,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE, 0x01,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START, 0x00,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT, 0x00,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST, 0x01,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION, 0x03,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS, 0x02,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT, 0x01,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x01,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x15,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_FLYING, 0x03,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x00,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_LOOK, 0x05,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLAYER_DIGGING, 0x07,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x17,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION_LOOK, 0x06,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_RESOURCE_PACK_STATUS, 0x19,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SWING_ARM, 0x0A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x14,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ABILITIES, 0x39,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_UPDATE, 0x23,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHAT, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA, 0x21,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DIFFICULTY, 0x41,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x40,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_DESTROY, 0x13,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_GAME_EVENT, 0x2B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_HELD_ITEM, 0x09,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x01,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MULTI_BLOCK_CHANGE, 0x22,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x38,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN, 0x07,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_POSITION, 0x08,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x3F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_SEND, 0x48,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_CONTENT, 0x30,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_SLOT, 0x2F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ENTITY_METADATA, 0x1C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_EXPERIENCE, 0x1F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_POSITION, 0x05,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x3A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ATTRIBUTES, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_HEALTH, 0x06,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_TIME, 0x03,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_BORDER_INIT, 0x44,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING, 0x01,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_REQUEST, 0x00,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING, 0x01,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST, 0x00);

  private static final ProtocolDefinition V1_12_2 = define(ProtocolVersion.MINECRAFT_1_12_2,
      ProtocolCapabilities.preFlattening(true, true), CodecStatus.DECLARED,
      "published packet ids for 1.12.2; the subset Conduit inspects on its own behalf, with translation left to ViaVersion",
      ConnectionState.AWAITING_HANDSHAKE, PacketDirection.CLIENT_TO_SERVER, PacketKind.HANDSHAKE, 0x00,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_ENCRYPTION_RESPONSE, 0x01,
      ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, PacketKind.LOGIN_START, 0x00,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_DISCONNECT, 0x00,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_ENCRYPTION_REQUEST, 0x01,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SET_COMPRESSION, 0x03,
      ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_SUCCESS, 0x02,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT, 0x02,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND, 0x02,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_INFORMATION, 0x04,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_FLYING, 0x0C,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE, 0x0B,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_LOOK, 0x0F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLAYER_DIGGING, 0x14,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLUGIN_MESSAGE, 0x09,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION, 0x0D,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_POSITION_LOOK, 0x0E,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_RESOURCE_PACK_STATUS, 0x18,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SWING_ARM, 0x1D,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST, 0x01,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TELEPORT_CONFIRM, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ABILITIES, 0x2C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_UPDATE, 0x0B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHAT, 0x0F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA, 0x20,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DIFFICULTY, 0x0D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISCONNECT, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_DESTROY, 0x32,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_GAME_EVENT, 0x1E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_HELD_ITEM, 0x3A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_KEEP_ALIVE, 0x1F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN, 0x23,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MULTI_BLOCK_CHANGE, 0x10,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE, 0x2E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN, 0x35,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_POSITION, 0x2F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE, 0x18,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_SEND, 0x34,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_CONTENT, 0x14,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_SLOT, 0x16,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ENTITY_METADATA, 0x3C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_EXPERIENCE, 0x40,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_POSITION, 0x46,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SYSTEM_CHAT, 0x0F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE, 0x0E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UNLOAD_CHUNK, 0x1D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UNLOCK_RECIPES, 0x31,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ADVANCEMENTS, 0x4D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ATTRIBUTES, 0x4E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_HEALTH, 0x41,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_TIME, 0x47,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_BORDER_INIT, 0x38,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_PING, 0x01,
      ConnectionState.STATUS, PacketDirection.CLIENT_TO_SERVER, PacketKind.STATUS_REQUEST, 0x00,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_PING, 0x01,
      ConnectionState.STATUS, PacketDirection.SERVER_TO_CLIENT, PacketKind.STATUS_REQUEST, 0x00);

  private static final ProtocolDefinition V1_13 = define(ProtocolVersion.MINECRAFT_1_13,
      ProtocolCapabilities.flattening113(), CodecStatus.VERIFIED,
      "authored from published 1.13 packet ids; exercised end-to-end by the official "
          + "Minecraft 1.13 client against the official 1.13 server through Conduit "
          + "(login, chunks, movement, combat, death, respawn, advancements; ~3 minutes, no disconnect)",
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
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_STATUS, 0x1C,
      // 1.13 multiplexes every border operation behind an action enum on this single id.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_BORDER_INIT, 0x3B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_TIME, 0x4A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_HEALTH, 0x44,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_EXPERIENCE, 0x43,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_UPDATE, 0x0B,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PLAYER_DIGGING, 0x18,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MULTI_BLOCK_CHANGE, 0x0F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SWING_ARM, 0x27,
      // Entity/world packets needed to carry real 1.20.4 gameplay to a 1.13 client.
      // 1.13 has no bundle delimiter, player chat, damage event or block-change ack.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_ENTITY, 0x00,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_RELATIVE_MOVE, 0x28,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_MOVE_LOOK, 0x29,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_LOOK, 0x2A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_HEAD_ROTATION, 0x39,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_VELOCITY, 0x41,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_COLLECT_ITEM, 0x4F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_TELEPORT, 0x50,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SOUND_EFFECT, 0x4D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_PARTICLES, 0x24,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_EQUIPMENT, 0x42,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLOSE_WINDOW, 0x09,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_ENTITY_ACTION, 0x19,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_BLOCK_PLACE, 0x29,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ANIMATION, 0x06,
      // 1.13 multiplexes enter/end/death behind an action enum on this one id.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_COMBAT_EVENT, 0x2F,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLIENT_COMMAND, 0x03,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UNLOCK_RECIPES, 0x34,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_LIVING_ENTITY, 0x03,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_CONTENT, 0x15,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_CONTAINER_SLOT, 0x17,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_ENTITY_METADATA, 0x3F,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ADVANCEMENTS, 0x51,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_ATTRIBUTES, 0x52,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WORLD_EVENT, 0x23,
      // Inventory / container / item interaction.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_WINDOW, 0x14,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CLOSE_WINDOW_CLIENTBOUND, 0x13,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_WINDOW_PROPERTY, 0x16,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CONFIRM_TRANSACTION, 0x12,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIRM_TRANSACTION, 0x06,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLICK_WINDOW, 0x08,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_SET_CARRIED_ITEM, 0x21,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CREATIVE_SLOT, 0x24,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_PICK_ITEM, 0x15,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_USE_ITEM, 0x2A,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_INTERACT_ENTITY, 0x0D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_PLAYER, 0x05,
      ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_ABILITIES, 0x17,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_EXPLOSION, 0x1E,
      // Everything else a real 1.13 server emits during play.
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_PASSENGERS, 0x46,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN, 0x38,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_EXPERIENCE_ORB, 0x01,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ENTITY_EFFECT, 0x53,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_REMOVE_ENTITY_EFFECT, 0x36,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_COOLDOWN, 0x18,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_BREAK_ANIMATION, 0x08,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_OPEN_SIGN_EDITOR, 0x2C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_LIST_HEADER, 0x4E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_STATISTICS, 0x07,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BOSS_BAR, 0x0C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_NAMED_SOUND_EFFECT, 0x1A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_NBT_QUERY_RESPONSE, 0x1D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_PAINTING, 0x04,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SPAWN_GLOBAL_ENTITY, 0x02,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_ENTITY_DATA, 0x09,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BLOCK_ACTION, 0x0A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SCOREBOARD_OBJECTIVE, 0x45,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TEAMS, 0x47,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_UPDATE_SCORE, 0x48,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DISPLAY_SCOREBOARD, 0x3E,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TITLE, 0x4B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_STOP_SOUND, 0x4C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CAMERA, 0x3C,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_ATTACH_ENTITY, 0x40,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_USE_BED, 0x33,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_FACE_PLAYER, 0x31,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CRAFT_RECIPE_RESPONSE, 0x2D,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SELECT_ADVANCEMENT_TAB, 0x3A,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_VEHICLE_MOVE, 0x2B,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_MAP_DATA, 0x26,
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TRADE_LIST, 0x27
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
      ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN, 0x41,
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
  /**
   * Registered packet tables, keyed by protocol number.
   *
   * <p>Declared tables are registered directly. Derived tables are registered
   * through {@link #derive} from a {@link ProtocolRevision}, so a point release
   * costs only its delta. A protocol number absent from this map has no codec:
   * {@link ProtocolCatalog} still lists it as a known identity, but Conduit
   * reports it as unsupported rather than guessing at a neighbour's layout.
   */
  private static final Map<Integer, ProtocolDefinition> BY_NUMBER = buildRegistry();

  private static Map<Integer, ProtocolDefinition> buildRegistry() {
    Map<Integer, ProtocolDefinition> registry = new java.util.LinkedHashMap<>();
    for (ProtocolDefinition declared : new ProtocolDefinition[] {
        V1_7_6, V1_8, V1_12_2, V1_13, V1_20_1, V1_20_4, V1_20_5, V26_2}) {
      registry.put(declared.version().number(), declared);
    }
    for (ProtocolRevision revision : ProtocolRevisions.ALL) {
      ProtocolDefinition base = registry.get(revision.baseProtocol());
      if (base == null) {
        throw new IllegalStateException("revision for protocol " + revision.protocol()
            + " names a base protocol that is not registered");
      }
      if (registry.putIfAbsent(revision.protocol(), derive(base, revision)) != null) {
        throw new IllegalStateException("duplicate registration for protocol " + revision.protocol());
      }
    }
    return registry;
  }
}

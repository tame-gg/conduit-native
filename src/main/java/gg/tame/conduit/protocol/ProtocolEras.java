// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/**
 * Wire-era boundaries used by codecs and delta translators.
 *
 * <p>Prefer these over raw {@code > 404} checks. Protocol 477 (1.14) sits between
 * the 1.13.x Slot reform and the 1.16+ registry Join Game; treating everything
 * after 404 as “modern 765” silently corrupts Join Game, containers, equipment
 * and block place when speaking 1.14.
 */
public final class ProtocolEras {
  private ProtocolEras() {}

  /** Highest protocol that still packs Y in the middle of a Position long. */
  public static final int LEGACY_POSITION_MAX = 404;

  /** First protocol with heightmaps NBT on Chunk Data and no section-embedded light. */
  /**
   * First protocol whose Join Game carries the dimension as an int rather than a signed byte
   * (1.9.1). Only matters for releases with no Configuration phase, which are the only ones whose
   * Join Game is still read field by field.
   */
  public static final int JOIN_GAME_DIMENSION_INT_FROM = 108;

  /** Whether this protocol's Join Game writes its dimension as an int. */
  public static boolean joinGameDimensionIsInt(int protocol) {
    return protocol >= JOIN_GAME_DIMENSION_INT_FROM;
  }

  /**
   * Last protocol whose Join Game still carries the difficulty and whose Respawn is dimension,
   * difficulty, gamemode, level type (1.13.2). 1.14 moved difficulty into its own packet and 1.15
   * added a hashed seed to both, so a reader of the older layout takes seed bytes for fields.
   */
  public static final int JOIN_GAME_DIFFICULTY_MAX = 404;

  /** Whether this protocol's Join Game and Respawn still use the layout that carries difficulty. */
  public static boolean joinGameHasDifficulty(int protocol) {
    return protocol <= JOIN_GAME_DIFFICULTY_MAX;
  }

  /** First protocol whose Join Game and Respawn carry a hashed world seed (1.15). */
  public static final int HASHED_SEED_FROM = 573;

  /**
   * First protocol whose play and configuration text components are network NBT rather than JSON
   * strings (1.20.3). The Configuration phase is not the boundary: 1.20.2 has one, and a real 1.20.2
   * client read Conduit's NBT reply to /conduit as a JSON string and lost the connection.
   */
  public static final int TEXT_COMPONENT_NBT_FROM = 765;

  /** Whether this protocol sends play and configuration text components as network NBT. */
  public static boolean textComponentNbt(int protocol) {
    return protocol >= TEXT_COMPONENT_NBT_FROM;
  }

  // What a text component can say, by release. The component's shape in JSON and NBT is the same
  // in every release that has both; these are the fields and forms that came and went.

  /** First protocol whose components take an insertion (1.8). */
  public static final int TEXT_INSERTION_FROM = 47;
  /** First protocol whose clicks can copy to the clipboard (1.15); an older client drops the action. */
  public static final int TEXT_COPY_TO_CLIPBOARD_FROM = 573;
  /**
   * First protocol with RGB colours (1.16), which is also where a show-text hover moved its text from
   * {@code value}, which is all 1.8-1.15 read, to {@code contents}.
   */
  public static final int TEXT_RGB_FROM = 735;
  /** First protocol whose translations take a fallback (1.19.4). */
  public static final int TEXT_FALLBACK_FROM = 762;
  /**
   * First protocol whose components name events {@code click_event} and {@code hover_event} (1.21.5),
   * with a click's value under a key per action and a hover's text back under {@code value}. A
   * 1.21.5 client also refuses the whole component over an open-URL click that is not http(s), a
   * command with a character chat cannot hold, or a page that is not a positive number.
   */
  public static final int TEXT_SNAKE_CASE_EVENTS_FROM = 770;

  public static boolean textInsertion(int protocol) { return protocol >= TEXT_INSERTION_FROM; }
  public static boolean textCopyToClipboard(int protocol) { return protocol >= TEXT_COPY_TO_CLIPBOARD_FROM; }
  public static boolean textRgb(int protocol) { return protocol >= TEXT_RGB_FROM; }
  public static boolean textFallback(int protocol) { return protocol >= TEXT_FALLBACK_FROM; }
  public static boolean textSnakeCaseEvents(int protocol) { return protocol >= TEXT_SNAKE_CASE_EVENTS_FROM; }

  /**
   * First protocol whose Player Info Update has the show-hat action (1.21.4). 1.21.2 and 1.21.3 end
   * at list priority, and a real 1.21.3 client given the hat's byte was disconnected with "found 1
   * bytes extra whilst reading packet player_info_update".
   */
  public static final int PLAYER_INFO_HAT_FROM = 769;

  /** Whether this protocol's Player Info Update carries the show-hat action. */
  public static boolean playerInfoHat(int protocol) {
    return protocol >= PLAYER_INFO_HAT_FROM;
  }

  /** First protocol whose Player Info Update has the list-order (priority) action (1.21.2). */
  public static final int PLAYER_INFO_LIST_ORDER_FROM = 768;

  /** Whether this protocol's Player Info Update carries the list-order action. */
  public static boolean playerInfoListOrder(int protocol) {
    return protocol >= PLAYER_INFO_LIST_ORDER_FROM;
  }

  /**
   * First protocol whose Player Info names entries by UUID and carries an action (1.8). 1.7's names
   * an entry by its display string and has only online and ping, so nothing maps onto it.
   */
  public static final int PLAYER_INFO_UUID_FROM = 47;

  /** Whether this protocol's Player Info names entries by UUID. */
  public static boolean playerInfoByUuid(int protocol) {
    return protocol >= PLAYER_INFO_UUID_FROM;
  }

  /**
   * Whether this protocol's Player Info Add ends with an optional profile public key (1.19-1.19.2),
   * the same window as Login Start's signature. 1.19.3 split the packet and moved keys to chat sessions.
   */
  public static boolean playerInfoProfileKey(int protocol) {
    return loginStartSignature(protocol);
  }

  /** The one protocol whose System Chat names a chat type by registry id instead of an action-bar flag (1.19). */
  public static final int SYSTEM_CHAT_TYPE_ID = 759;

  /** Whether this protocol's System Chat ends with a chat type id rather than a boolean. */
  public static boolean systemChatTypeId(int protocol) {
    return protocol == SYSTEM_CHAT_TYPE_ID;
  }

  /** First protocol whose Login Start carries the client's optional profile key signature (1.19). */
  public static final int LOGIN_START_SIGNATURE_FROM = 759;

  /** First protocol whose Login Start carries the client's UUID as an optional field (1.19.1). */
  public static final int LOGIN_START_OPTIONAL_UUID_FROM = 760;

  /** Last protocol whose Login Start carries the profile key signature (1.19.2); 1.19.3 dropped it. */
  public static final int LOGIN_START_SIGNATURE_MAX = 760;

  /** Last protocol whose Login Start UUID is optional (1.20.1); from 1.20.2 it is always present. */
  public static final int LOGIN_START_OPTIONAL_UUID_MAX = 763;

  /** Whether this protocol's Login Start has optional fields after the username (1.19-1.20.1). */
  public static boolean loginStartOptionalFields(int protocol) {
    return protocol >= LOGIN_START_SIGNATURE_FROM && protocol <= LOGIN_START_OPTIONAL_UUID_MAX;
  }

  /** Whether this protocol's Login Start has the optional profile key signature (1.19-1.19.2). */
  public static boolean loginStartSignature(int protocol) {
    return protocol >= LOGIN_START_SIGNATURE_FROM && protocol <= LOGIN_START_SIGNATURE_MAX;
  }

  /** Whether this protocol's Login Start has an optional UUID (1.19.1-1.20.1). */
  public static boolean loginStartOptionalUuid(int protocol) {
    return protocol >= LOGIN_START_OPTIONAL_UUID_FROM && protocol <= LOGIN_START_OPTIONAL_UUID_MAX;
  }

  /** First protocol whose Join Game and Respawn name the world by key (1.16). */
  public static final int RESPAWN_WORLD_KEY_FROM = 735;

  /**
   * First protocol whose Join Game has a hardcore flag and a VarInt max players, and whose Join Game
   * and Respawn carry the dimension type as NBT beside the world key (1.16.2).
   */
  public static final int RESPAWN_DIMENSION_NBT_FROM = 751;

  /**
   * First protocol whose item slot is a present flag and a VarInt id rather than a short id where -1
   * is empty (1.13.2). The layout is otherwise unchanged until 1.20.2's nameless NBT root.
   */
  public static final int SLOT_PRESENT_FLAG_FROM = 404;

  /** Whether this protocol writes an item slot as a present flag and a VarInt id. */
  public static boolean slotPresentFlag(int protocol) {
    return protocol >= SLOT_PRESENT_FLAG_FROM;
  }

  /** First protocol whose Join Game carries a simulation distance (1.18). */
  public static final int JOIN_GAME_SIMULATION_DISTANCE_FROM = 757;

  /**
   * First protocol whose Join Game and Respawn name the dimension type by key again and carry an
   * optional last death location (1.19).
   */
  public static final int RESPAWN_DEATH_LOCATION_FROM = 759;

  /** First protocol whose Join Game and Respawn end with a portal cooldown (1.20). */
  public static final int RESPAWN_PORTAL_COOLDOWN_FROM = 763;

  /** Last protocol with no Configuration phase (1.20.1). */
  public static final int PRE_CONFIGURATION_MAX = 763;

  /**
   * Whether the legacy world reload writes the world-key Respawn: every release from 1.16, where the
   * world is named by key, to 1.20.1, the last one with no Configuration phase to reload through.
   * Real 1.16, 1.16.5, 1.17.1 and 1.18 clients switched between two servers sat on "Loading terrain"
   * without it.
   */
  public static boolean worldReloadWorldKey(int protocol) {
    return protocol >= RESPAWN_WORLD_KEY_FROM && protocol <= PRE_CONFIGURATION_MAX;
  }

  /** Whether this protocol's Join Game and Respawn carry the dimension type as NBT (1.16.2-1.18.2). */
  public static boolean dimensionTypeNbt(int protocol) {
    return protocol >= RESPAWN_DIMENSION_NBT_FROM && protocol < RESPAWN_DEATH_LOCATION_FROM;
  }

  /** Whether this protocol's Join Game and Respawn carry a hashed world seed. */
  public static boolean hashedSeed(int protocol) {
    return protocol >= HASHED_SEED_FROM;
  }

  /**
   * First protocol whose clientbound Chat Message ends with a position byte (1.8). A 1.7 client
   * reads the JSON and nothing else, and drops the connection over the one byte left behind.
   */
  public static final int CHAT_POSITION_FROM = 47;

  /** Whether this protocol's clientbound Chat Message carries a position byte after the text. */
  public static boolean chatHasPosition(int protocol) {
    return protocol >= CHAT_POSITION_FROM;
  }

  /**
   * First protocol whose clientbound Chat Message ends with the sender's UUID after the position
   * (1.16). A 1.16.5 client reads a long past the end of a message that stops at the position.
   */
  public static final int CHAT_SENDER_FROM = 735;

  /** Whether this protocol's clientbound Chat Message carries a sender UUID after the position. */
  public static boolean chatHasSender(int protocol) {
    return protocol >= CHAT_SENDER_FROM;
  }

  /**
   * First protocol whose Title packet has an action-bar action (1.11). It was inserted as action 2,
   * which moved times, hide and reset up by one, so 1.8-1.10 number every later action differently.
   * Which releases have a Title packet at all, rather than 1.17's one packet per action, is the
   * packet table's to say.
   */
  public static final int TITLE_ACTION_BAR_FROM = 315;

  /** Whether this protocol's Title packet carries the action bar as action 2. */
  public static boolean titleActionBar(int protocol) {
    return protocol >= TITLE_ACTION_BAR_FROM;
  }

  /**
   * Last protocol Conduit has a codec for whose Resource Pack Status carries the pack's hash before
   * the result (1.8). 1.12.2's carries the result alone; the release between them that dropped the
   * hash has no codec here. Whether a release names packs by UUID instead (1.20.3's Push and Pop) is
   * the packet table's to say.
   */
  public static final int RESOURCE_PACK_STATUS_HASH_MAX = 47;

  /** Whether this protocol's Resource Pack Status starts with the pack's hash. */
  public static boolean resourcePackStatusHash(int protocol) {
    return protocol <= RESOURCE_PACK_STATUS_HASH_MAX;
  }

  /** First protocol whose Resource Pack Send ends with a required flag and an optional prompt (1.17). */
  public static final int RESOURCE_PACK_PROMPT_FROM = 755;

  /** Whether this protocol's Resource Pack Send carries a required flag and a prompt. */
  public static boolean resourcePackPrompt(int protocol) {
    return protocol >= RESOURCE_PACK_PROMPT_FROM;
  }

  // Sounds played by name. Which packets a release has is the table's to say; these are the fields.

  /** First protocol whose sounds carry a category (1.9); 1.7 and 1.8 have none. */
  public static final int SOUND_CATEGORY_FROM = 107;
  /** First protocol whose Named Sound Effect sends pitch as a float (1.10); before it a byte, 63 being normal. */
  public static final int SOUND_PITCH_FLOAT_FROM = 201;
  /** First protocol that can stop sounds (1.9.3), through the MC|StopSound channel until 1.13 gave it a packet. */
  public static final int STOP_SOUND_FROM = 110;
  /** First protocol whose sounds carry the seed that picks among a sound's variants (1.19). */
  public static final int SOUND_SEED_FROM = 759;
  /**
   * First protocol that names a sound inline in Sound Effect and Entity Sound Effect (1.19.3), where
   * Named Sound Effect went; an entity's sound could be named only by registry id before it.
   */
  public static final int SOUND_INLINE_EVENT_FROM = 761;
  /** First protocol with the {@code ui} sound category (26.1). */
  public static final int SOUND_UI_SOURCE_FROM = 775;

  public static boolean soundCategory(int protocol) { return protocol >= SOUND_CATEGORY_FROM; }
  public static boolean soundPitchFloat(int protocol) { return protocol >= SOUND_PITCH_FLOAT_FROM; }
  public static boolean stopSound(int protocol) { return protocol >= STOP_SOUND_FROM; }
  public static boolean soundSeed(int protocol) { return protocol >= SOUND_SEED_FROM; }
  public static boolean soundInlineEvent(int protocol) { return protocol >= SOUND_INLINE_EVENT_FROM; }
  public static boolean soundUiSource(int protocol) { return protocol >= SOUND_UI_SOURCE_FROM; }

  // What a client sends when a plugin speaks for it (Player.spoofChatInput).

  /** First protocol whose chat box takes 256 characters (1.11); before it, 100. */
  public static final int CHAT_256_FROM = 315;
  /**
   * First protocol with an unsigned Chat Command packet, the command alone (1.20.5). From 1.19 to 1.20.4
   * every command carried a timestamp, a salt and the client's acknowledgement of recent chat.
   */
  public static final int UNSIGNED_CHAT_COMMAND_FROM = 766;

  /** The most characters this protocol's chat box, and so its chat packet, takes. */
  public static int chatLimit(int protocol) { return protocol >= CHAT_256_FROM ? 256 : 100; }
  public static boolean unsignedChatCommand(int protocol) { return protocol >= UNSIGNED_CHAT_COMMAND_FROM; }

  // Secure chat (SecureChat): how a client says which signed messages it has seen.

  /** First protocol whose chat and commands carry the list of signed messages the client has seen (1.19.1). */
  public static final int CHAT_LAST_SEEN_LIST_FROM = 760;
  /**
   * First protocol with chat sessions (1.19.3): the key moved from Login Start to a Play packet, and chat
   * and commands acknowledge seen messages as an offset and 20 bits instead of a list.
   */
  public static final int CHAT_SESSION_FROM = 761;
  /** First protocol whose chat acknowledgement ends with a checksum byte, 0 meaning none (1.21.5). */
  public static final int CHAT_CHECKSUM_FROM = 770;
  public static boolean chatLastSeenList(int protocol) { return protocol >= CHAT_LAST_SEEN_LIST_FROM && protocol < CHAT_SESSION_FROM; }
  public static boolean chatSession(int protocol) { return protocol >= CHAT_SESSION_FROM; }
  public static boolean chatChecksum(int protocol) { return protocol >= CHAT_CHECKSUM_FROM; }

  public static final int CHUNK_HEIGHTMAPS_FROM = 477;

  /** First protocol with Open Window menu registry ids (title still JSON until 765). */
  public static final int OPEN_WINDOW_MENU_FROM = 477;

  /** First protocol with multi-slot Entity Equipment arrays. */
  public static final int EQUIPMENT_ARRAY_FROM = 735;

  /** First protocol with container stateId (1.17). */
  public static final int CONTAINER_STATE_FROM = 755;

  /** First protocol with block-place prediction sequence (1.19). */
  public static final int BLOCK_PLACE_SEQUENCE_FROM = 759;

  /** First protocol with NBT text components on Open Window (1.20.3 / 765). */
  public static final int OPEN_WINDOW_NBT_TITLE_FROM = 765;

  /** First protocol with registry-based Join Game (1.16). */
  public static final int JOIN_GAME_REGISTRY_FROM = 735;

  public static boolean legacyPosition(int protocol) {
    return protocol <= LEGACY_POSITION_MAX;
  }

  public static boolean chunkHeightmaps(int protocol) {
    return protocol >= CHUNK_HEIGHTMAPS_FROM;
  }

  public static boolean sectionEmbeddedLight(int protocol) {
    return protocol < CHUNK_HEIGHTMAPS_FROM;
  }

  public static boolean openWindowMenuId(int protocol) {
    return protocol >= OPEN_WINDOW_MENU_FROM;
  }

  public static boolean openWindowNbtTitle(int protocol) {
    return protocol >= OPEN_WINDOW_NBT_TITLE_FROM;
  }

  public static boolean equipmentArray(int protocol) {
    return protocol >= EQUIPMENT_ARRAY_FROM;
  }

  public static boolean containerStateId(int protocol) {
    return protocol >= CONTAINER_STATE_FROM;
  }

  public static boolean blockPlaceSequence(int protocol) {
    return protocol >= BLOCK_PLACE_SEQUENCE_FROM;
  }

  /** 1.14–1.15 Join Game: numeric dimension, viewDistance, no registry worlds. */
  public static boolean joinGame114(int protocol) {
    return protocol >= CHUNK_HEIGHTMAPS_FROM && protocol < JOIN_GAME_REGISTRY_FROM;
  }

  public static boolean joinGameRegistry(int protocol) {
    return protocol >= JOIN_GAME_REGISTRY_FROM;
  }

  // There is deliberately no "flattening item table" predicate here. Item and
  // block-state ids are not an era property: 1.13.1 and 1.14 each reshuffled
  // them mid-era, so a single pre-1.16 bucket maps most of the registry to the
  // wrong item. Registry selection lives in ItemRegistries/BlockStateMaps, per
  // protocol, backed by generated tables.
}

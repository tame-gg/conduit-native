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

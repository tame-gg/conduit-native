package gg.tame.conduit.protocol;

public enum PacketKind {
  HANDSHAKE, STATUS_REQUEST, STATUS_PING, LOGIN_START, LOGIN_SUCCESS, LOGIN_DISCONNECT,
  LOGIN_ENCRYPTION_REQUEST, LOGIN_ENCRYPTION_RESPONSE, LOGIN_SET_COMPRESSION, LOGIN_PLUGIN_REQUEST, LOGIN_PLUGIN_RESPONSE,
  LOGIN_ACKNOWLEDGED, CONFIGURATION_FINISH, CONFIGURATION_PLUGIN_MESSAGE, CONFIGURATION_KNOWN_PACKS, CONFIGURATION_REGISTRY, CONFIGURATION_RESET_CHAT,
  CONFIGURATION_DISCONNECT, CONFIGURATION_KEEP_ALIVE,
  PLAY_LOGIN, PLAY_PLUGIN_MESSAGE, PLAY_START_CONFIGURATION, PLAY_SYSTEM_CHAT,
  PLAY_TAB_COMPLETE, PLAY_DISCONNECT, PLAY_CHAT_COMMAND, PLAY_TAB_COMPLETE_REQUEST,
  PLAY_CONFIGURATION_ACKNOWLEDGED, PLAY_DECLARE_COMMANDS, PLAY_PLAYER_INFO_UPDATE, PLAY_PLAYER_INFO_REMOVE,
  PLAY_CLIENT_INFORMATION, CONFIGURATION_CLIENT_INFORMATION, PLAY_KEEP_ALIVE,
  /** Pre-1.19 unsigned chat / command string (1.13 chat packet). */
  PLAY_CHAT,
  PLAY_POSITION, PLAY_POSITION_LOOK, PLAY_LOOK, PLAY_FLYING,
  PLAY_PLAYER_POSITION, PLAY_TELEPORT_CONFIRM,
  PLAY_RESOURCE_PACK_SEND, PLAY_RESOURCE_PACK_STATUS,
  PLAY_CHUNK_DATA, PLAY_UNLOAD_CHUNK, PLAY_UPDATE_LIGHT,
  PLAY_SPAWN_POSITION, PLAY_DIFFICULTY, PLAY_GAME_EVENT, PLAY_ABILITIES, PLAY_HELD_ITEM,
  PLAY_DECLARE_RECIPES, PLAY_TAGS,
  PLAY_UPDATE_VIEW_POSITION, PLAY_UPDATE_VIEW_DISTANCE, PLAY_SIMULATION_DISTANCE,
  PLAY_CHUNK_BATCH_START, PLAY_CHUNK_BATCH_FINISHED, PLAY_UNLOCK_RECIPES,
  PLAY_ENTITY_STATUS,
  /** Server MOTD / icon / secure-chat flag. Added in 1.19; absent from 1.13. */
  PLAY_SERVER_DATA,
  /** Initialize world border. Multiplexed behind an action enum on 1.13; standalone on 1.17+. */
  PLAY_WORLD_BORDER_INIT,
  /** World age + time of day. Identical two-long layout on 1.13 and 1.20.4. */
  PLAY_UPDATE_TIME,
  /** Tick rate + frozen flag. Added in 1.20.3; no 1.13 equivalent. */
  PLAY_SET_TICKING_STATE,
  /** Advance a frozen world by N ticks. Added in 1.20.3; no 1.13 equivalent. */
  PLAY_STEP_TICK,
  /** Full container contents. Carries Slot item ids, which are registry-incompatible across eras. */
  PLAY_SET_CONTAINER_CONTENT,
  /** Single container slot. Same era-specific Slot item payload as the full-content packet. */
  PLAY_SET_CONTAINER_SLOT,
  /** Entity metadata. Both field indices and type ids are era specific. */
  PLAY_SET_ENTITY_METADATA,
  /** Entity attributes. Key names were renamed and namespaced in 1.16; count width also differs. */
  PLAY_UPDATE_ATTRIBUTES,
  /** Advancement tree and progress. Display/criteria structure diverged after 1.13. */
  PLAY_UPDATE_ADVANCEMENTS,
  /** Health + food + saturation. Identical Float/VarInt/Float layout on 1.13 and 1.20.4. */
  PLAY_UPDATE_HEALTH,
  /** Experience bar + level + total. Identical Float/VarInt/VarInt layout on 1.13 and 1.20.4. */
  PLAY_SET_EXPERIENCE,
  /** Single block change. Position field order changed in 1.14; state ids are era specific. */
  PLAY_BLOCK_UPDATE,
  /** Player digging / block action. Carries a packed Position; 1.19+ adds a prediction sequence. */
  PLAY_PLAYER_DIGGING,
  /** Batch of block changes in one chunk column. Structurally unrelated between 1.13 and 1.20.4. */
  PLAY_MULTI_BLOCK_CHANGE,
  /** Swing arm. Single Hand VarInt on both 1.13 and 1.20.4. */
  PLAY_SWING_ARM,
  PLAY_ENTITY_DESTROY,

  // ---------------------------------------------------------------------------
  // Entity and world packets a real 1.20.4 server sends during ordinary play.
  // Added from wire traces of an official client, not from speculation; the
  // schema comparison behind each decision is in tools/schemadiff.py output.
  // ---------------------------------------------------------------------------

  /** Groups packets for atomic application. Added 1.19.4; absent before. */
  PLAY_BUNDLE_DELIMITER,
  /** Spawn a non-living entity. 1.20.4 widened type to VarInt and added headPitch. */
  PLAY_SPAWN_ENTITY,
  /** Relative entity move. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_RELATIVE_MOVE,
  /** Relative entity move plus rotation. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_MOVE_LOOK,
  /** Entity rotation. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_LOOK,
  /** Entity head yaw. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_HEAD_ROTATION,
  /** Entity velocity. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_VELOCITY,
  /** Item pickup animation. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_COLLECT_ITEM,
  /** Absolute entity teleport. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_TELEPORT,
  /** Signed player chat. Added 1.19; 1.13 carries chat through PLAY_CHAT. */
  PLAY_PLAYER_CHAT,
  /** Damage animation/source detail. Added 1.19.4; no 1.13 equivalent. */
  PLAY_DAMAGE_EVENT,
  /** Block-prediction sequence acknowledgement. Added 1.19; no 1.13 equivalent. */
  PLAY_ACKNOWLEDGE_BLOCK_CHANGE,
  /** Positional sound. Sound registry ids differ per version; 1.20.4 adds a seed. */
  PLAY_SOUND_EFFECT,
  /** Particle spawn. 1.20.4 widened coordinates to f64 and the id to VarInt. */
  PLAY_WORLD_PARTICLES,
  /** Entity equipment. 1.16 replaced the single slot with a multi-slot array. */
  PLAY_ENTITY_EQUIPMENT,
  /** Client closed a container. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_CLOSE_WINDOW,
  /** Sneak/sprint/horse actions. Byte-identical layout on 1.13 and 1.20.4. */
  PLAY_ENTITY_ACTION,
  /** Use item on block. 1.20.4 reorders fields and adds insideBlock + sequence. */
  PLAY_BLOCK_PLACE,

  /** Swing/hurt/wake animation for an entity. Byte-identical on 1.13 and 1.20.4. */
  PLAY_ANIMATION,
  /** Standalone hurt animation. Added 1.19.4; 1.13 drives it from entity status. */
  PLAY_HURT_ANIMATION,
  /**
   * Pre-1.17 combat event, multiplexed behind an action enum: enter combat,
   * end combat, or entity death (which drives the death screen). 1.17 split this
   * into three separate packets, so translating either way is a fan-in/fan-out
   * rather than a field copy.
   */
  PLAY_COMBAT_EVENT,
  /** 1.17+ enter-combat. Folds into {@link #PLAY_COMBAT_EVENT} action 0. */
  PLAY_ENTER_COMBAT,
  /** 1.17+ end-combat. Folds into {@link #PLAY_COMBAT_EVENT} action 1. */
  PLAY_END_COMBAT,
  /** 1.17+ death, carrying the death message. Folds into action 2. */
  PLAY_DEATH_COMBAT,
  /** Respawn / request-stats button. Byte-identical on 1.13 and 1.20.4. */
  PLAY_CLIENT_COMMAND,
  /**
   * Pre-1.19 spawn packet for living entities (mobs, players' mounts). 1.19
   * merged it into {@link #PLAY_SPAWN_ENTITY}, so an old backend's living spawns
   * must fan in to the unified modern packet.
   */
  PLAY_SPAWN_LIVING_ENTITY,
  /**
   * Block-break / door / portal style world effects. Same field list on 1.13 and
   * 1.20.4, but it carries a packed Position, whose bit layout changed in 1.14 —
   * so it must be converted, not copied.
   */
  PLAY_WORLD_EVENT,

  // ---------------------------------------------------------------------------
  // Inventory, containers and item interaction. Slot payloads name items by the
  // sending era's registry, and the container framing gained a state id in 1.17,
  // so none of these can be forwarded as bytes.
  // ---------------------------------------------------------------------------

  /** Server opens a screen. 1.14 replaced the type string with a registry id. */
  PLAY_OPEN_WINDOW,
  /** Server closes a screen. Single window id on both releases. */
  PLAY_CLOSE_WINDOW_CLIENTBOUND,
  /** Furnace/enchanting/beacon progress bars: windowId, property, value. Same on both. */
  PLAY_WINDOW_PROPERTY,
  /**
   * 1.13's inventory-action handshake. The server echoes each click's action
   * number and the client re-sends it when the server rejects it. 1.17 replaced
   * the whole mechanism with the container state id, so 1.20.4 has no such
   * packet and Conduit has to answer it on the backend's behalf.
   */
  PLAY_CONFIRM_TRANSACTION,
  /** Client clicked a container slot. 1.17 swapped the action number for a state id. */
  PLAY_CLICK_WINDOW,
  /** Client changed its selected hotbar slot. Single short on both releases. */
  PLAY_SET_CARRIED_ITEM,
  /** Creative-mode direct slot set: slot plus item payload. */
  PLAY_CREATIVE_SLOT,
  /** Middle-click pick block. Single VarInt slot on both releases. */
  PLAY_PICK_ITEM,
  /** Use held item (right click in air). 1.19 appended a prediction sequence. */
  PLAY_USE_ITEM,
  /** Attack or interact with an entity. 1.16 appended a sneaking flag. */
  PLAY_INTERACT_ENTITY,
  /**
   * Pre-1.20.2 spawn packet for other players. 1.20.2 removed it in favour of the
   * unified spawn packet, so a 1.13 backend's player spawns have to fan in.
   */
  PLAY_SPAWN_PLAYER,
  /**
   * Explosion. 1.13 sends float coordinates and nothing else; 1.20.4 widened the
   * coordinates to doubles and appended the block-interaction mode, two particle
   * descriptors and a sound.
   */
  PLAY_EXPLOSION,

  // ---------------------------------------------------------------------------
  // The rest of what a real 1.13 server sends during ordinary play. Conduit is
  // fail-closed, so an id with no entry here ends the session; every one of
  // these therefore needs an explicit translate-or-drop decision even when the
  // packet itself is only cosmetic. Enumerated from a real server, not a table.
  // ---------------------------------------------------------------------------

  /** Riding. Entity id plus a VarInt array of passengers; identical on both. */
  PLAY_SET_PASSENGERS,
  /** Respawn after death or a dimension change. World identity was rewritten in 1.16. */
  PLAY_RESPAWN,
  /** XP orb spawn. entityId + three doubles + a short count; identical on both. */
  PLAY_SPAWN_EXPERIENCE_ORB,
  /** Potion effect applied. The effect id widened from a byte to a VarInt. */
  PLAY_ENTITY_EFFECT,
  /** Potion effect cleared. Same id-width change. */
  PLAY_REMOVE_ENTITY_EFFECT,
  /** Item cooldown after use. Carries an item registry id, so it needs the item map. */
  PLAY_SET_COOLDOWN,
  /** Block cracking overlay. Carries a packed Position, whose layout changed in 1.14. */
  PLAY_BLOCK_BREAK_ANIMATION,
  /** Opens the sign text editor. Packed Position; 1.20 appended a front/back flag. */
  PLAY_OPEN_SIGN_EDITOR,
  /** Tab-list header and footer: two text components. */
  PLAY_TAB_LIST_HEADER,

  // Display-only packets. Mapped so the id is recognised and dropped on purpose.
  PLAY_STATISTICS,
  PLAY_BOSS_BAR,
  PLAY_NAMED_SOUND_EFFECT,
  PLAY_NBT_QUERY_RESPONSE,
  PLAY_SPAWN_PAINTING,
  PLAY_SPAWN_GLOBAL_ENTITY,
  PLAY_BLOCK_ENTITY_DATA,
  PLAY_BLOCK_ACTION,
  PLAY_SCOREBOARD_OBJECTIVE,
  PLAY_TEAMS,
  PLAY_UPDATE_SCORE,
  PLAY_DISPLAY_SCOREBOARD,
  PLAY_TITLE,
  PLAY_STOP_SOUND,
  PLAY_CAMERA,
  PLAY_ATTACH_ENTITY,
  PLAY_USE_BED,
  PLAY_FACE_PLAYER,
  PLAY_CRAFT_RECIPE_RESPONSE,
  PLAY_SELECT_ADVANCEMENT_TAB,
  PLAY_VEHICLE_MOVE,
  PLAY_MAP_DATA,
  PLAY_TRADE_LIST
}

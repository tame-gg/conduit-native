package gg.tame.conduit.protocol.entity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The 1.13 Spawn Object type namespace.
 *
 * <p>This is the trap in 1.13's entity model: {@code Spawn Mob} carries an entity
 * <em>registry</em> id, but {@code Spawn Object} carries a completely separate,
 * much older enumeration — a boat is object type 1, a dropped item is 2, an arrow
 * is 60. Neither the numbers nor their ordering have anything to do with the
 * registry, so building the object map out of registry indices (as an earlier
 * version of Conduit's generator did) silently leaves almost every projectile,
 * dropped item and vehicle unmapped, and those spawns are then dropped.
 *
 * <p>The enumeration is fixed and was never generated from data, so it is written
 * out here by identifier. Resolution to a 1.20.4 type then goes through the
 * generated entity-name table, so the mapping is still by name and still fails
 * closed on anything absent.
 */
public final class LegacyObjectTypes {
  /** 1.13 Spawn Object type id -> identifier. */
  private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

  static {
    NAMES.put(1, "minecraft:boat");
    NAMES.put(2, "minecraft:item");                    // a dropped item stack
    NAMES.put(3, "minecraft:area_effect_cloud");
    NAMES.put(10, "minecraft:minecart");               // objectData selects the variant
    NAMES.put(50, "minecraft:tnt");
    NAMES.put(51, "minecraft:end_crystal");
    NAMES.put(60, "minecraft:arrow");
    NAMES.put(61, "minecraft:snowball");
    NAMES.put(62, "minecraft:egg");
    NAMES.put(63, "minecraft:fireball");               // ghast
    NAMES.put(64, "minecraft:small_fireball");         // blaze
    NAMES.put(65, "minecraft:ender_pearl");
    NAMES.put(66, "minecraft:wither_skull");
    NAMES.put(67, "minecraft:shulker_bullet");
    NAMES.put(68, "minecraft:llama_spit");
    NAMES.put(70, "minecraft:falling_block");
    NAMES.put(71, "minecraft:item_frame");
    NAMES.put(72, "minecraft:eye_of_ender");
    NAMES.put(73, "minecraft:potion");
    NAMES.put(75, "minecraft:experience_bottle");
    NAMES.put(76, "minecraft:firework_rocket");
    NAMES.put(77, "minecraft:leash_knot");
    NAMES.put(78, "minecraft:armor_stand");
    NAMES.put(79, "minecraft:evoker_fangs");
    NAMES.put(90, "minecraft:fishing_bobber");
    NAMES.put(91, "minecraft:spectral_arrow");
    NAMES.put(93, "minecraft:dragon_fireball");
  }

  /**
   * 1.13 minecart variants travel as object type 10 with the variant in
   * {@code objectData}; 1.20.4 gives each its own entity type.
   */
  private static final String[] MINECARTS = {
      "minecraft:minecart",
      "minecraft:chest_minecart",
      "minecraft:furnace_minecart",
      "minecraft:tnt_minecart",
      "minecraft:spawner_minecart",
      "minecraft:hopper_minecart",
      "minecraft:command_block_minecart"
  };

  private static final List<String> NAMES_765 = loadNames();
  private static final Map<String, Integer> IDS_765 = index(NAMES_765);
  private static final Map<String, Integer> OBJECT_BY_NAME = new HashMap<>();

  static {
    NAMES.forEach((id, name) -> OBJECT_BY_NAME.putIfAbsent(name, id));
    for (int variant = 1; variant < MINECARTS.length; variant++) {
      OBJECT_BY_NAME.putIfAbsent(MINECARTS[variant], 10);
    }
  }

  private LegacyObjectTypes() {}

  /** The identifier a 1.13 object type names, taking minecart variants into account. */
  public static Optional<String> name(int objectType, int objectData) {
    if (objectType == 10) {
      return Optional.of(objectData >= 0 && objectData < MINECARTS.length
          ? MINECARTS[objectData] : MINECARTS[0]);
    }
    return Optional.ofNullable(NAMES.get(objectType));
  }

  /** 1.13 object type -> 1.20.4 entity type id. Empty when 1.20.4 has no such entity. */
  public static OptionalInt to765(int objectType, int objectData) {
    Optional<String> name = name(objectType, objectData);
    if (name.isEmpty()) return OptionalInt.empty();
    Integer id = IDS_765.get(name.get());
    return id == null ? OptionalInt.empty() : OptionalInt.of(id);
  }

  /** 1.20.4 entity type id -> 1.13 object type. Empty when 1.13 has no object for it. */
  public static OptionalInt toObjectType(int type765) {
    if (type765 < 0 || type765 >= NAMES_765.size()) return OptionalInt.empty();
    Integer object = OBJECT_BY_NAME.get(NAMES_765.get(type765));
    return object == null ? OptionalInt.empty() : OptionalInt.of(object);
  }

  /** The {@code objectData} a 1.13 spawn needs for this 1.20.4 minecart variant. */
  public static int objectDataFor(int type765) {
    if (type765 < 0 || type765 >= NAMES_765.size()) return 0;
    String name = NAMES_765.get(type765);
    for (int variant = 0; variant < MINECARTS.length; variant++) {
      if (MINECARTS[variant].equals(name)) return variant;
    }
    return 0;
  }

  /** The 1.20.4 identifier for a type id, for diagnostics and tests. */
  public static Optional<String> name765(int type765) {
    if (type765 < 0 || type765 >= NAMES_765.size()) return Optional.empty();
    String name = NAMES_765.get(type765);
    return name.isEmpty() ? Optional.empty() : Optional.of(name);
  }

  public static OptionalInt id765(String name) {
    Integer id = IDS_765.get(name);
    return id == null ? OptionalInt.empty() : OptionalInt.of(id);
  }

  private static Map<String, Integer> index(List<String> names) {
    Map<String, Integer> ids = new HashMap<>(names.size() * 2);
    for (int id = 0; id < names.size(); id++) {
      if (!names.get(id).isEmpty()) ids.put(names.get(id), id);
    }
    return Map.copyOf(ids);
  }

  private static List<String> loadNames() {
    String path = "/gg/tame/conduit/protocol/entity/entitytypes_765_names.txt";
    try (InputStream in = LegacyObjectTypes.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource " + path);
      List<String> names = new ArrayList<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) names.add(line.trim());
      return List.copyOf(names);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load entity names", exception);
    }
  }
}

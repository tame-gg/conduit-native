package gg.tame.conduit.protocol.entity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Semantic entity-type mapping between protocol 393 (1.13) and 765 (1.20.4).
 *
 * <p>1.13 keeps separate numeric namespaces for living ({@code Spawn Mob}) and
 * object ({@code Spawn Object}) types. 1.20.4 uses one entity registry. Maps are
 * built from PrismarineJS minecraft-data by entity <em>name</em>, never by
 * copying raw numeric IDs.
 *
 * <p>Unmapped types return empty so callers can suppress the spawn rather than
 * invent a wrong mob.
 */
public final class EntityTypeMaps {
  private static final int[] MOB_393_TO_765 = loadInts("entitytypes_mob_393_to_765.bin");

  private static final int[] TO_MOB_393 = loadInts("entitytypes_765_to_mob_393.bin");

  private static final byte[] LIVING_765 = loadBytes("entitytypes_765_living_flags.bin");

  private EntityTypeMaps() {}

  public static OptionalInt mob393To765(int type393) {
    return lookup(MOB_393_TO_765, type393);
  }

  /**
   * 1.13 Spawn Object type -> 1.20.4 entity type, by identifier.
   *
   * <p>Delegates to {@link LegacyObjectTypes}: the 1.13 object namespace is not
   * the entity registry, so the generated registry-index table cannot answer
   * this. {@code objectData} is needed because 1.13 puts the minecart variant
   * there while 1.20.4 gives each variant its own entity type.
   */
  public static OptionalInt object393To765(int type393, int objectData) {
    return LegacyObjectTypes.to765(type393, objectData);
  }

  public static OptionalInt toMob393(int type765) {
    return lookup(TO_MOB_393, type765);
  }

  /** 1.20.4 entity type -> 1.13 Spawn Object type, by identifier. */
  public static OptionalInt toObject393(int type765) {
    return LegacyObjectTypes.toObjectType(type765);
  }

  /** True when the 765 registry entry is a living/mob category (not projectile/object). */
  public static boolean isLiving765(int type765) {
    return type765 >= 0 && type765 < LIVING_765.length && LIVING_765[type765] != 0;
  }

  /**
   * Prefer living mapping for living 765 types, object mapping otherwise.
   * Falls back to the other namespace when the preferred one is absent.
   */
  public static OptionalInt to393(int type765) {
    if (isLiving765(type765)) {
      OptionalInt mob = toMob393(type765);
      if (mob.isPresent()) return mob;
      return toObject393(type765);
    }
    OptionalInt object = toObject393(type765);
    if (object.isPresent()) return object;
    return toMob393(type765);
  }

  public static boolean isLivingOn393(int type765) {
    if (isLiving765(type765)) return toMob393(type765).isPresent();
    // Non-living 765 types should use object spawn when possible.
    return toObject393(type765).isEmpty() && toMob393(type765).isPresent();
  }

  /**
   * Entity registry index across the 404 ↔ 477 pair, resolved by identifier.
   *
   * <p>1.14 inserted {@code cat} at index 6, so 89 of 1.13.2's 95 entity types
   * arrive as a different mob if the id is forwarded unchanged. Both tables are
   * plain index→name lists, so the crossing is a name lookup, never an offset.
   *
   * <p>Empty when the type has no counterpart, so callers suppress the spawn
   * rather than showing the player the wrong mob.
   */
  public static OptionalInt translateRegistry(int fromProtocol, int toProtocol, int type) {
    if (fromProtocol == toProtocol) return OptionalInt.of(type);
    List<String> from = registryNames(fromProtocol);
    Map<String, Integer> to = registryIndex(toProtocol);
    if (from == null || to == null || type < 0 || type >= from.size()) return OptionalInt.empty();
    String name = from.get(type);
    if (name.isEmpty()) return OptionalInt.empty();
    Integer mapped = to.get(name);
    return mapped == null ? OptionalInt.empty() : OptionalInt.of(mapped);
  }

  /** True when both protocols have a generated entity registry for this pair. */
  public static boolean supportsRegistry(int fromProtocol, int toProtocol) {
    return fromProtocol == toProtocol
        || (registryNames(fromProtocol) != null && registryNames(toProtocol) != null);
  }

  /** The identifier this protocol's entity registry gives that index. */
  public static Optional<String> registryName(int protocol, int type) {
    List<String> names = registryNames(protocol);
    if (names == null || type < 0 || type >= names.size()) return Optional.empty();
    String name = names.get(type);
    return name.isEmpty() ? Optional.empty() : Optional.of(name);
  }

  /** The index this protocol's entity registry gives that identifier. */
  public static OptionalInt registryIndexOf(int protocol, String name) {
    Map<String, Integer> index = registryIndex(protocol);
    if (index == null || name == null) return OptionalInt.empty();
    Integer id = index.get(name);
    return id == null ? OptionalInt.empty() : OptionalInt.of(id);
  }

  private static List<String> registryNames(int protocol) {
    return REGISTRY_NAMES.get(protocol);
  }

  private static Map<String, Integer> registryIndex(int protocol) {
    return REGISTRY_INDEX.get(protocol);
  }

  private static final Map<Integer, List<String>> REGISTRY_NAMES = Map.of(
      404, loadNames("entitytypes_404_names.txt"),
      477, loadNames("entitytypes_477_names.txt"));

  private static final Map<Integer, Map<String, Integer>> REGISTRY_INDEX = index(REGISTRY_NAMES);

  private static Map<Integer, Map<String, Integer>> index(Map<Integer, List<String>> names) {
    Map<Integer, Map<String, Integer>> indexed = new HashMap<>();
    names.forEach((protocol, list) -> {
      Map<String, Integer> ids = new HashMap<>(list.size() * 2);
      for (int id = 0; id < list.size(); id++) {
        if (!list.get(id).isEmpty()) ids.putIfAbsent(list.get(id), id);
      }
      indexed.put(protocol, Map.copyOf(ids));
    });
    return Map.copyOf(indexed);
  }

  private static List<String> loadNames(String name) {
    String path = "/gg/tame/conduit/protocol/entity/" + name;
    try (InputStream in = EntityTypeMaps.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource " + path);
      List<String> names = new ArrayList<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        names.add(line.trim());
      }
      return List.copyOf(names);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load " + name, exception);
    }
  }

  private static OptionalInt lookup(int[] table, int id) {
    if (id < 0 || id >= table.length) return OptionalInt.empty();
    int mapped = table[id];
    return mapped < 0 ? OptionalInt.empty() : OptionalInt.of(mapped);
  }

  private static int[] loadInts(String name) {
    String path = "/gg/tame/conduit/protocol/entity/" + name;
    try (InputStream in = EntityTypeMaps.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource " + path);
      byte[] header = in.readNBytes(4);
      if (header.length != 4) throw new IllegalStateException("truncated " + name);
      int len = (header[0] & 0xff) | ((header[1] & 0xff) << 8) | ((header[2] & 0xff) << 16) | ((header[3] & 0xff) << 24);
      if (len < 1 || len > 10_000) throw new IllegalStateException("invalid map length " + len);
      byte[] body = in.readNBytes(len * 4);
      if (body.length != len * 4) throw new IllegalStateException("truncated body " + name);
      int[] map = new int[len];
      for (int i = 0; i < len; i++) {
        int o = i * 4;
        map[i] = (body[o] & 0xff) | ((body[o + 1] & 0xff) << 8) | ((body[o + 2] & 0xff) << 16) | ((body[o + 3] & 0xff) << 24);
      }
      return map;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load " + name, exception);
    }
  }

  private static byte[] loadBytes(String name) {
    String path = "/gg/tame/conduit/protocol/entity/" + name;
    try (InputStream in = EntityTypeMaps.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource " + path);
      return in.readAllBytes();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load " + name, exception);
    }
  }
}

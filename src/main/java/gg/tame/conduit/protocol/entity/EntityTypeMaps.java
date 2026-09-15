package gg.tame.conduit.protocol.entity;

import java.io.IOException;
import java.io.InputStream;
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
  private static final int[] OBJECT_393_TO_765 = loadInts("entitytypes_object_393_to_765.bin");
  private static final int[] TO_MOB_393 = loadInts("entitytypes_765_to_mob_393.bin");
  private static final int[] TO_OBJECT_393 = loadInts("entitytypes_765_to_object_393.bin");
  private static final byte[] LIVING_765 = loadBytes("entitytypes_765_living_flags.bin");

  private EntityTypeMaps() {}

  public static OptionalInt mob393To765(int type393) {
    return lookup(MOB_393_TO_765, type393);
  }

  public static OptionalInt object393To765(int type393) {
    return lookup(OBJECT_393_TO_765, type393);
  }

  public static OptionalInt toMob393(int type765) {
    return lookup(TO_MOB_393, type765);
  }

  public static OptionalInt toObject393(int type765) {
    return lookup(TO_OBJECT_393, type765);
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

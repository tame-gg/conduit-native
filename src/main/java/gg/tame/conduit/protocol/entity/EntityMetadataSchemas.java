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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-entity metadata layouts, and how one protocol's layout lines up with
 * another's.
 *
 * <p>A metadata index is not a field name. It is an offset into the entity's
 * class hierarchy, so it moves whenever <em>any</em> class in that chain gains
 * or loses a field — and 1.14 did that at several levels at once: {@code Entity}
 * gained {@code pose}, {@code LivingEntity} gained {@code sleepingPos}, and
 * {@code AbstractArrow} gained {@code pierceLevel} in the middle of its own
 * block. A single pair-wide "+1 here, +2 there" rule describes the base classes
 * and is simply wrong for any concrete class that changed independently. That is
 * what put a tipped arrow's colour (a VarInt) onto 1.14's pierce level (a Byte).
 *
 * <p>So the layouts are not derived from a rule at all. They are <em>measured</em>:
 * {@code MetadataSchemaProbe} connects to each real server, summons every entity
 * type in the registry and records what it is actually sent;
 * {@code tools/gen_entity_metadata_schema.py} turns that into the two resources
 * loaded here, keyed by entity identifier so the sides line up by name rather
 * than by ids 1.14 reshuffled.
 *
 * <p>Two layouts are then aligned by a longest-common-subsequence over their
 * serializer sequences. That is the whole mechanism: it needs no per-entity
 * special cases, and it handles an inserted field, a removed field and a field
 * whose serializer changed in exactly the way each deserves —
 *
 * <ul>
 *   <li>inserted ({@code pierceLevel}): the fields after it shift, and the
 *       arrow's colour still lands on the colour;</li>
 *   <li>removed (a zombie field 1.14 dropped): it has no counterpart and is not
 *       sent;</li>
 *   <li>retyped (a villager's profession became {@code VillagerData}): the
 *       serializers do not match, so it is not sent rather than landing a VarInt
 *       on a structure the client will cast.</li>
 * </ul>
 *
 * <p>An entity with no recorded layout returns empty and the caller falls back to
 * the base-class mapping, which is common to every entity, dropping the concrete
 * class's own fields. Unmapped is always a dropped field, never a wrong one.
 */
public final class EntityMetadataSchemas {
  /** entity identifier -> the ordered (index, serializer) pairs it sends. */
  private static final Map<Integer, Map<String, List<Field>>> SCHEMAS = load();

  private static final Map<String, Alignment> ALIGNMENTS = new ConcurrentHashMap<>();

  /** One metadata entry: where it sits, and how its value is encoded. */
  public record Field(int index, int serializer) {}

  /**
   * How one protocol's layout for an entity maps onto another's.
   *
   * @param targetIndex source index -> target index, or -1 where the field has
   *                    no counterpart with a matching serializer
   */
  public record Alignment(int[] targetIndex, int[] sourceSerializer) {
    /**
     * The target index for a field, or -1 to drop it.
     *
     * <p>The observed serializer is checked against the one the source protocol
     * is recorded as using at that index. They disagree when the entity is not
     * really the one the spawn packet claimed, or when a server sends something
     * the capture never saw; either way the alignment cannot be trusted for that
     * field, and a dropped field is always better than one landed on a neighbour
     * the client will cast.
     */
    public int map(int sourceIndex, int serializer) {
      if (sourceIndex < 0 || sourceIndex >= targetIndex.length) return -1;
      if (sourceSerializer[sourceIndex] != serializer) return -1;
      return targetIndex[sourceIndex];
    }
  }

  private EntityMetadataSchemas() {}

  /** The measured layout, if this protocol has one recorded for that entity. */
  public static Optional<List<Field>> schema(int protocol, String entity) {
    Map<String, List<Field>> byName = SCHEMAS.get(protocol);
    if (byName == null || entity == null) return Optional.empty();
    return Optional.ofNullable(byName.get(entity));
  }

  /**
   * The index mapping for one entity across a protocol pair, or empty when
   * either side has no measured layout for it.
   */
  public static Optional<Alignment> align(int fromProtocol, int toProtocol, String entity) {
    if (entity == null) return Optional.empty();
    String key = fromProtocol + ">" + toProtocol + ">" + entity;
    Alignment cached = ALIGNMENTS.get(key);
    if (cached != null) return Optional.of(cached);

    Optional<List<Field>> from = schema(fromProtocol, entity);
    Optional<List<Field>> to = schema(toProtocol, entity);
    if (from.isEmpty() || to.isEmpty()) return Optional.empty();

    Alignment alignment = commonSubsequence(from.get(), to.get());
    ALIGNMENTS.put(key, alignment);
    return Optional.of(alignment);
  }

  /**
   * Longest common subsequence over the two serializer sequences.
   *
   * <p>Matching on the serializer rather than on the position is what makes an
   * inserted field shift everything after it instead of silently renaming every
   * field from that point on. Where the two sides genuinely disagree about a
   * field's type, no match is produced and the field is dropped.
   */
  private static Alignment commonSubsequence(List<Field> from, List<Field> to) {
    int rows = from.size();
    int columns = to.size();
    int[][] best = new int[rows + 1][columns + 1];
    for (int row = rows - 1; row >= 0; row--) {
      for (int column = columns - 1; column >= 0; column--) {
        best[row][column] = from.get(row).serializer() == to.get(column).serializer()
            ? best[row + 1][column + 1] + 1
            : Math.max(best[row + 1][column], best[row][column + 1]);
      }
    }
    int highest = 0;
    for (Field field : from) highest = Math.max(highest, field.index());
    int[] mapping = new int[highest + 1];
    int[] serializers = new int[highest + 1];
    java.util.Arrays.fill(mapping, -1);
    java.util.Arrays.fill(serializers, -1);
    for (Field field : from) serializers[field.index()] = field.serializer();
    int row = 0;
    int column = 0;
    while (row < rows && column < columns) {
      if (from.get(row).serializer() == to.get(column).serializer()) {
        mapping[from.get(row).index()] = to.get(column).index();
        row++;
        column++;
      } else if (best[row + 1][column] >= best[row][column + 1]) {
        row++;
      } else {
        column++;
      }
    }
    return new Alignment(mapping, serializers);
  }

  private static Map<Integer, Map<String, List<Field>>> load() {
    Map<Integer, Map<String, List<Field>>> all = new HashMap<>();
    for (int protocol : new int[] { 404, 477 }) {
      all.put(protocol, read("entity_metadata_" + protocol + ".txt"));
    }
    return Map.copyOf(all);
  }

  private static Map<String, List<Field>> read(String resource) {
    String path = "/gg/tame/conduit/protocol/entity/" + resource;
    try (InputStream in = EntityMetadataSchemas.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource " + path);
      Map<String, List<Field>> schema = new HashMap<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        line = line.trim();
        if (line.isEmpty()) continue;
        int space = line.indexOf(' ');
        if (space < 0) continue;
        String entity = line.substring(0, space);
        List<Field> fields = new ArrayList<>();
        for (String pair : line.substring(space + 1).split(",")) {
          int colon = pair.indexOf(':');
          if (colon < 0) continue;
          fields.add(new Field(Integer.parseInt(pair.substring(0, colon).trim()),
              Integer.parseInt(pair.substring(colon + 1).trim())));
        }
        schema.put(entity, List.copyOf(fields));
      }
      return Map.copyOf(schema);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot load " + resource, exception);
    }
  }
}

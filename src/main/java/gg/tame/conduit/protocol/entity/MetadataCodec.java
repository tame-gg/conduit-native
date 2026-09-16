package gg.tame.conduit.protocol.entity;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.chunk.BlockStateMaps;
import gg.tame.conduit.protocol.codec.BlockPositionCodec;
import gg.tame.conduit.protocol.item.ItemCodec;
import gg.tame.conduit.protocol.text.ComponentCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Semantic translation of entity metadata between protocol 393 and 765.
 *
 * <p>Two things are era-specific and neither can be copied through.
 *
 * <p><b>Type ids.</b> 1.19 inserted VarLong at id 2, and later releases appended
 * a dozen more types, so every id from 2 up means something different on the two
 * sides. A 765 Float (3) read as a 393 type 3 would be a String, and the stream
 * desynchronises from that byte onward. Types are therefore mapped explicitly,
 * and each value is decoded and re-encoded rather than memcpy'd — several of
 * them (Slot, Chat, Position, BlockState) have genuinely different payloads.
 *
 * <p><b>Field indices.</b> An index is not a global field number: it is an offset
 * into the entity class hierarchy, so it shifts whenever a superclass gains a
 * field. Between these two releases exactly two insertions happened:
 * {@code Entity} gained pose and tickFrozen (indices 6 and 7), and
 * {@code LivingEntity} gained beeStingers and sleepingBedLocation (13 and 14).
 * That gives one rule that holds for every entity rather than a per-mob table:
 *
 * <pre>
 *   0..5                 Entity base          unchanged
 *   non-living, 6+       entity-specific      +2
 *   living,     6..10    LivingEntity block   +2
 *   living,     11+      Mob / concrete class +4
 * </pre>
 *
 * <p>Fields with no counterpart (a modern pose, a villager's profession record)
 * are dropped from the stream rather than guessed at. The entity keeps its
 * position, identity and every field that does map.
 */
public final class MetadataCodec {
  private static final int END = 0xff;

  /** 393 type id -> 765 type id. -1 where the modern client has no such type. */
  private static final int[] TYPE_393_TO_765 = {
      0,   // Byte
      1,   // VarInt
      3,   // Float
      4,   // String
      5,   // Chat
      6,   // OptChat
      7,   // Slot
      8,   // Boolean
      9,   // Rotation
      10,  // Position
      11,  // OptPosition
      12,  // Direction
      13,  // OptUUID
      15,  // OptBlockID -> OptBlockState
      16,  // NBT
      17   // Particle
  };

  private MetadataCodec() {}

  /**
   * Translates one metadata body.
   *
   * @param living whether the entity extends LivingEntity, which decides where
   *               the index shift falls. Callers track this from the spawn
   *               packet that introduced the entity.
   * @return the re-encoded body, or null when nothing survived translation
   */
  public static byte[] translate(ProtocolDefinition source, ProtocolDefinition target,
                                 byte[] body, boolean living) throws IOException {
    int fromProtocol = source.version().number();
    int toProtocol = target.version().number();
    boolean fromLegacy = fromProtocol <= 404;
    boolean toLegacy = toProtocol <= 404;

    // 393 ↔ 404 share metadata type ids and field indices. The only payload that
    // changes is Slot (short id → present+VarInt). Rematerialising those is enough;
    // applying the 393↔765 index/type maps here would scramble a same-era stream.
    if (fromLegacy && toLegacy) {
      return translateLegacySlotOnly(fromProtocol, toProtocol, body);
    }

    // 404 ↔ 477 is its own era and must not borrow the 393↔765 maps. 1.14 kept
    // metadata types 0..15 byte-identical and merely appended VillagerData (16),
    // OptVarInt (17) and Pose (18); the 765 type table would renumber Float and
    // desynchronise the stream. Indices shift by one insertion, not two.
    if (is114Pair(fromProtocol, toProtocol)) {
      return translate114(fromProtocol, toProtocol, body, living, null);
    }

    boolean toModern = !toLegacy;

    ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 16);
    DataOutputStream out = new DataOutputStream(buffer);
    int carried = 0;

    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      while (true) {
        int index = in.readUnsignedByte();
        if (index == END) break;
        int type = MinecraftInput.varInt(in);

        int targetType = toModern ? mapType393To765(type) : mapType765To393(type);
        int targetIndex = toModern ? mapIndexToModern(index, living) : mapIndexToLegacy(index, living);

        // A value whose type the target does not have still has to be consumed
        // from the source stream so the following entries stay aligned.
        ByteArrayOutputStream value = new ByteArrayOutputStream(16);
        boolean transcoded = transcode(new DataOutputStream(value), in, type, targetType,
            fromProtocol, toProtocol, source, target);
        if (!transcoded || targetType < 0 || targetIndex < 0) continue;

        out.writeByte(targetIndex);
        MinecraftOutput.varInt(out, targetType);
        out.write(value.toByteArray());
        carried++;
      }
    }
    out.writeByte(END);
    out.flush();
    return carried == 0 ? null : buffer.toByteArray();
  }

  /**
   * Same-era rematerialisation for protocols &le;404: type ids and indices are
   * identical; only Slot payloads change wire form between 393 and 404.
   */
  private static byte[] translateLegacySlotOnly(int fromProtocol, int toProtocol, byte[] body)
      throws IOException {
    if (fromProtocol == toProtocol) return body;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 16);
    DataOutputStream out = new DataOutputStream(buffer);
    int carried = 0;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      while (true) {
        int index = in.readUnsignedByte();
        if (index == END) break;
        int type = MinecraftInput.varInt(in);
        out.writeByte(index);
        MinecraftOutput.varInt(out, type);
        if (type == 6) { // Slot on the 1.13 metadata type table
          ItemCodec.write(toProtocol, out, ItemCodec.read(fromProtocol, in));
        } else {
          copyLegacyValue(out, in, type);
        }
        carried++;
      }
    }
    out.writeByte(END);
    out.flush();
    return carried == 0 ? null : buffer.toByteArray();
  }

  /** Copies one non-Slot legacy metadata value without interpreting it. */
  private static void copyLegacyValue(DataOutput out, DataInput in, int type) throws IOException {
    switch (type) {
      case 0 -> out.writeByte(in.readByte());
      case 1 -> MinecraftOutput.varInt(out, MinecraftInput.varInt(in));
      case 2 -> out.writeFloat(in.readFloat());
      case 3, 4 -> MinecraftOutput.string(out, MinecraftInput.string(in, 262_144));
      case 5 -> {
        boolean present = in.readBoolean();
        out.writeBoolean(present);
        if (present) MinecraftOutput.string(out, MinecraftInput.string(in, 262_144));
      }
      case 7 -> out.writeBoolean(in.readBoolean());
      case 8 -> {
        for (int axis = 0; axis < 3; axis++) out.writeFloat(in.readFloat());
      }
      case 9 -> out.writeLong(in.readLong());
      case 10 -> {
        boolean present = in.readBoolean();
        out.writeBoolean(present);
        if (present) out.writeLong(in.readLong());
      }
      case 11 -> MinecraftOutput.varInt(out, MinecraftInput.varInt(in));
      case 12 -> {
        boolean present = in.readBoolean();
        out.writeBoolean(present);
        if (present) {
          out.writeLong(in.readLong());
          out.writeLong(in.readLong());
        }
      }
      case 13 -> MinecraftOutput.varInt(out, MinecraftInput.varInt(in));
      case 14 -> {
        byte[] tag = gg.tame.conduit.protocol.item.ItemNbt.readTag(in, true);
        gg.tame.conduit.protocol.item.ItemNbt.writeTag(out, tag, true);
      }
      case 15 -> throw new IOException("particle metadata is not copied across 393/404");
      default -> throw new IOException("unknown legacy metadata type " + type);
    }
  }

  private static boolean is114Pair(int fromProtocol, int toProtocol) {
    return (fromProtocol <= 404 && toProtocol == 477) || (fromProtocol == 477 && toProtocol <= 404);
  }

  /**
   * 1.13.2 ↔ 1.14 metadata.
   *
   * <p>Types 0..15 are identical on both sides (confirmed against the 1.13.2 and
   * 1.14 protocol schemas), so each value is copied in its own form rather than
   * renumbered — except Slot, whose payload is unchanged here too since both
   * sides use the present-flag form, and which still needs its item <em>id</em>
   * remapped because 678 of 790 item ids moved in 1.14.
   *
   * <p>1.14 inserted exactly two fields: {@code Entity.pose} at index 6, and
   * {@code LivingEntity.sleepingPos} after the arrow count. Everything at or
   * above those points shifts; nothing else moves. Types and fields that exist
   * only on 1.14 are consumed and dropped on the way down rather than guessed.
   */
  public static byte[] translate114(int fromProtocol, int toProtocol, byte[] body, boolean living,
                                    String entity) throws IOException {
    boolean up = toProtocol == 477;
    var alignment = EntityMetadataSchemas.align(fromProtocol, toProtocol, entity);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 16);
    DataOutputStream out = new DataOutputStream(buffer);
    int carried = 0;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      while (true) {
        int index = in.readUnsignedByte();
        if (index == END) break;
        int type = MinecraftInput.varInt(in);

        // Prefer the entity's own measured layout. Without one, only the base
        // classes can be mapped safely, because their shape is common to every
        // entity while a concrete class's is not.
        int targetIndex;
        if (alignment.isPresent()) {
          targetIndex = alignment.get().map(index, type);
        } else {
          targetIndex = up ? mapIndexTo114(index, living) : mapIndexFrom114(index, living);
          if (index >= (living ? 11 : 6)) targetIndex = -1;
        }

        ByteArrayOutputStream value = new ByteArrayOutputStream(16);
        DataOutputStream valueOut = new DataOutputStream(value);
        boolean representable = type <= 15;   // 16..18 exist only on 1.14
        try {
          if (type == 6) {
            ItemCodec.write(toProtocol, valueOut, ItemCodec.read(fromProtocol, in));
          } else if (representable) {
            copyLegacyValue(valueOut, in, type);
          } else {
            consume114Only(in, type);
          }
        } catch (IOException unreadable) {
          // A value this codec cannot step over - a Particle, whose payload shape
          // depends on a particle id that 1.14 also renumbered - makes everything
          // after it in the block unreadable too. Stop here and terminate the
          // block: the entity keeps the fields already translated and loses the
          // rest. Throwing instead would take down the whole session over one
          // cosmetic field.
          ProtocolTrace.note("metadata truncated at index " + index + " type " + type
              + " (" + unreadable.getMessage() + ")");
          break;
        }
        valueOut.flush();
        if (!representable || targetIndex < 0) continue;

        out.writeByte(targetIndex);
        MinecraftOutput.varInt(out, type);
        out.write(value.toByteArray());
        carried++;
      }
    }
    out.writeByte(END);
    out.flush();
    return carried == 0 ? null : buffer.toByteArray();
  }

  /** Consumes a 1.14-only metadata value so the entries after it stay aligned. */
  private static void consume114Only(DataInput in, int type) throws IOException {
    switch (type) {
      case 16 -> {                                        // VillagerData
        MinecraftInput.varInt(in);
        MinecraftInput.varInt(in);
        MinecraftInput.varInt(in);
      }
      case 17 -> {                                        // OptVarInt
        MinecraftInput.varInt(in);
      }
      case 18 -> MinecraftInput.varInt(in);               // Pose
      default -> throw new IOException("unknown 1.14 metadata type " + type);
    }
  }

  public static int mapIndexTo114(int index, boolean living) {
    if (index <= 5) return index;                         // Entity base unchanged
    if (!living) return index + 1;                        // pose inserted at 6
    if (index <= 10) return index + 1;                    // LivingEntity block, pose only
    return index + 2;                                     // + sleepingPos
  }

  public static int mapIndexFrom114(int index, boolean living) {
    if (index <= 5) return index;
    if (index == 6) return -1;                            // pose: no 1.13.2 field
    if (!living) return index - 1;
    if (index <= 11) return index - 1;
    if (index == 12) return -1;                           // sleepingPos: no 1.13.2 field
    return index - 2;
  }

  public static int mapType393To765(int type) {
    return type >= 0 && type < TYPE_393_TO_765.length ? TYPE_393_TO_765[type] : -1;
  }

  public static int mapType765To393(int type) {
    for (int legacy = 0; legacy < TYPE_393_TO_765.length; legacy++) {
      if (TYPE_393_TO_765[legacy] == type) return legacy;
    }
    // 765 BlockState (14) is non-optional; 1.13's nearest is OptBlockID, which
    // the transcoder fills in as "present". Everything else (VarLong, Pose,
    // VillagerData, the 1.20 variants) has no 1.13 counterpart at all.
    return type == 14 ? 13 : -1;
  }

  public static int mapIndexToModern(int index, boolean living) {
    if (index <= 5) return index;                       // Entity base
    if (!living) return index + 2;                      // pose + tickFrozen inserted
    if (index <= 10) return index + 2;                  // LivingEntity block
    return index + 4;                                   // + beeStingers + sleepingBed
  }

  public static int mapIndexToLegacy(int index, boolean living) {
    if (index <= 5) return index;
    if (index == 6 || index == 7) return -1;            // pose, tickFrozen: no 1.13 field
    if (!living) return index - 2;
    if (index <= 12) return index - 2;
    if (index == 13 || index == 14) return -1;          // beeStingers, sleepingBed
    return index - 4;
  }

  /**
   * Reads one metadata value in the source type and writes it in the target
   * type. Returns false when the value was consumed but cannot be represented.
   */
  private static boolean transcode(DataOutput out, DataInput in, int sourceType, int targetType,
                                   int fromProtocol, int toProtocol,
                                   ProtocolDefinition source, ProtocolDefinition target)
      throws IOException {
    boolean fromModern = fromProtocol > 404;
    boolean emit = targetType >= 0;
    // Normalise the source type into a version-independent kind first, so the
    // switch below is about the VALUE, not about which era named it.
    Kind kind = fromModern ? modernKind(sourceType) : legacyKind(sourceType);
    if (kind == null) return false;                     // cannot even be skipped safely

    switch (kind) {
      case BYTE -> { byte value = in.readByte(); if (emit) out.writeByte(value); }
      case VAR_INT -> { int value = MinecraftInput.varInt(in); if (emit) MinecraftOutput.varInt(out, value); }
      case VAR_LONG -> { MinecraftInput.varLong(in); return false; }   // no 1.13 type
      case FLOAT -> { float value = in.readFloat(); if (emit) out.writeFloat(value); }
      case STRING -> { String value = MinecraftInput.string(in, 32767); if (emit) MinecraftOutput.string(out, value); }
      case CHAT -> { if (!component(out, in, fromModern, emit)) return false; }
      case OPT_CHAT -> {
        boolean present = in.readBoolean();
        if (emit) out.writeBoolean(present);
        if (present && !component(out, in, fromModern, emit)) return false;
      }
      case SLOT -> {
        var item = ItemCodec.read(fromProtocol, in);
        if (emit) ItemCodec.write(toProtocol, out, item);
      }
      case BOOLEAN -> { boolean value = in.readBoolean(); if (emit) out.writeBoolean(value); }
      case ROTATION -> { for (int axis = 0; axis < 3; axis++) { float v = in.readFloat(); if (emit) out.writeFloat(v); } }
      case POSITION -> {
        long packed = in.readLong();
        if (emit) out.writeLong(BlockPositionCodec.translate(source, target, packed));
      }
      case OPT_POSITION -> {
        boolean present = in.readBoolean();
        if (emit) out.writeBoolean(present);
        if (present) {
          long packed = in.readLong();
          if (emit) out.writeLong(BlockPositionCodec.translate(source, target, packed));
        }
      }
      case DIRECTION -> { int value = MinecraftInput.varInt(in); if (emit) MinecraftOutput.varInt(out, value); }
      case OPT_UUID -> {
        boolean present = in.readBoolean();
        if (emit) out.writeBoolean(present);
        if (present) {
          long high = in.readLong();
          long low = in.readLong();
          if (emit) { out.writeLong(high); out.writeLong(low); }
        }
      }
      case BLOCK_STATE -> {
        int state = MinecraftInput.varInt(in);
        if (!emit) return false;
        int mapped = translateState(state, fromProtocol, toProtocol);
        // 1.13's only block-state metadata type is OPTIONAL; a non-optional
        // modern BlockState becomes an explicitly-present optional.
        if (targetType == 13 && fromModern) out.writeBoolean(true);
        MinecraftOutput.varInt(out, mapped);
      }
      case OPT_BLOCK_STATE -> {
        // 1.13 encodes "absent" as state 0; 1.20.4 uses a VarInt where 0 means absent too.
        int state = MinecraftInput.varInt(in);
        if (emit) MinecraftOutput.varInt(out, state == 0 ? 0 : translateState(state, fromProtocol, toProtocol));
      }
      case NBT -> {
        // 1.13 writes a named root, 1.20.2+ a nameless one.
        byte[] tag = gg.tame.conduit.protocol.item.ItemNbt.readTag(in, !fromModern);
        if (emit) gg.tame.conduit.protocol.item.ItemNbt.writeTag(out, tag, toProtocol <= 404);
      }
      case PARTICLE -> {
        // Particle ids belong to each era's own registry and are unmapped, and
        // the payload is per-particle. Not skippable without that table, so the
        // whole metadata body has to stop here rather than desynchronise.
        throw new IOException("particle metadata is not translatable across 393/765");
      }
      case UNREPRESENTABLE -> {
        // No 1.13 counterpart, but the payload still has to be consumed or every
        // entry after it is read at the wrong offset.
        if (!skipModernOnly(in, sourceType)) {
          throw new IOException("metadata type " + sourceType + " cannot be skipped");
        }
        return false;
      }
    }
    return emit;
  }

  private static boolean component(DataOutput out, DataInput in, boolean fromModern, boolean emit)
      throws IOException {
    if (fromModern) {
      String json = ComponentCodec.nbtToJson(in);
      if (emit) MinecraftOutput.string(out, json);
      return true;
    }
    String json = MinecraftInput.string(in, 262_144);
    if (emit) ComponentCodec.jsonToNbt(out, json);
    return true;
  }

  /**
   * Block states carried inside entity metadata (falling blocks, minecart display
   * blocks) cross the version boundary through the same generated per-pair table
   * the chunk path uses. Picking a table from the target alone was only ever
   * right for the 393↔765 pair.
   */
  private static int translateState(int state, int fromProtocol, int toProtocol) {
    return BlockStateMaps.translate(fromProtocol, toProtocol, state);
  }

  private enum Kind {
    BYTE, VAR_INT, VAR_LONG, FLOAT, STRING, CHAT, OPT_CHAT, SLOT, BOOLEAN, ROTATION,
    POSITION, OPT_POSITION, DIRECTION, OPT_UUID, BLOCK_STATE, OPT_BLOCK_STATE, NBT,
    PARTICLE, UNREPRESENTABLE
  }

  private static Kind legacyKind(int type) {
    return switch (type) {
      case 0 -> Kind.BYTE;
      case 1 -> Kind.VAR_INT;
      case 2 -> Kind.FLOAT;
      case 3 -> Kind.STRING;
      case 4 -> Kind.CHAT;
      case 5 -> Kind.OPT_CHAT;
      case 6 -> Kind.SLOT;
      case 7 -> Kind.BOOLEAN;
      case 8 -> Kind.ROTATION;
      case 9 -> Kind.POSITION;
      case 10 -> Kind.OPT_POSITION;
      case 11 -> Kind.DIRECTION;
      case 12 -> Kind.OPT_UUID;
      case 13 -> Kind.OPT_BLOCK_STATE;
      case 14 -> Kind.NBT;
      case 15 -> Kind.PARTICLE;
      default -> null;
    };
  }

  private static Kind modernKind(int type) {
    return switch (type) {
      case 0 -> Kind.BYTE;
      case 1 -> Kind.VAR_INT;
      case 2 -> Kind.VAR_LONG;
      case 3 -> Kind.FLOAT;
      case 4 -> Kind.STRING;
      case 5 -> Kind.CHAT;
      case 6 -> Kind.OPT_CHAT;
      case 7 -> Kind.SLOT;
      case 8 -> Kind.BOOLEAN;
      case 9 -> Kind.ROTATION;
      case 10 -> Kind.POSITION;
      case 11 -> Kind.OPT_POSITION;
      case 12 -> Kind.DIRECTION;
      case 13 -> Kind.OPT_UUID;
      case 14 -> Kind.BLOCK_STATE;
      case 15 -> Kind.OPT_BLOCK_STATE;
      case 16 -> Kind.NBT;
      case 17 -> Kind.PARTICLE;
      // Everything below was added after 1.13 and has a fixed, skippable payload
      // except where noted, so the rest of the body survives a field 1.13 lacks.
      case 18 -> Kind.UNREPRESENTABLE;   // VillagerData: three VarInts
      case 19 -> Kind.UNREPRESENTABLE;   // OptVarInt
      case 20 -> Kind.UNREPRESENTABLE;   // Pose
      case 21, 22, 25 -> Kind.UNREPRESENTABLE;   // cat / frog / sniffer variant: VarInt
      case 23 -> Kind.UNREPRESENTABLE;   // OptGlobalPos
      case 24 -> Kind.UNREPRESENTABLE;   // painting variant
      case 26, 27 -> Kind.UNREPRESENTABLE;   // Vector3, Quaternion
      default -> null;
    };
  }

  /**
   * Consumes a modern-only value so the entries after it stay aligned.
   * Returns false if the type cannot be skipped, in which case the caller must
   * abandon the body rather than emit a desynchronised one.
   */
  static boolean skipModernOnly(DataInput in, int type) throws IOException {
    switch (type) {
      case 18 -> { MinecraftInput.varInt(in); MinecraftInput.varInt(in); MinecraftInput.varInt(in); }
      case 19, 20, 21, 22, 24, 25 -> MinecraftInput.varInt(in);
      case 23 -> { if (in.readBoolean()) { MinecraftInput.string(in, 32767); in.readLong(); } }
      case 26 -> { in.readFloat(); in.readFloat(); in.readFloat(); }
      case 27 -> { in.readFloat(); in.readFloat(); in.readFloat(); in.readFloat(); }
      default -> { return false; }
    }
    return true;
  }
}

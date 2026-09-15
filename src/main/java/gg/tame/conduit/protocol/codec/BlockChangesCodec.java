package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.semantic.SemanticBlockChanges;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Version-specific multi-block-change codecs. See {@link SemanticBlockChanges} for the layouts. */
public final class BlockChangesCodec {
  /** Highest protocol still using the 1.13 chunk-relative record layout. */
  private static final int LEGACY_MAX_PROTOCOL = 404;

  /** Guards against a hostile or corrupt record count. A full section holds 4096 blocks. */
  private static final int MAX_RECORDS = 4096;

  /** 1.13 addresses Y with one unsigned byte, so only 0..255 is representable. */
  private static final int LEGACY_MAX_Y = 255;

  private BlockChangesCodec() {}

  private static boolean legacy(ProtocolDefinition protocol) {
    return protocol.version().number() <= LEGACY_MAX_PROTOCOL;
  }

  /** Decodes a multi-block-change packet in the era of {@code protocol}. */
  public static SemanticBlockChanges decode(ProtocolDefinition protocol, PacketDirection direction,
                                            byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
      return legacy(protocol) ? decodeLegacy(direction, input) : decodeModern(direction, input);
    }
  }

  private static SemanticBlockChanges decodeLegacy(PacketDirection direction, DataInputStream input)
      throws IOException {
    int chunkX = input.readInt();
    int chunkZ = input.readInt();
    int count = MinecraftInput.varInt(input);
    if (count < 0 || count > MAX_RECORDS) throw new IOException("multi block change count out of range: " + count);
    List<SemanticBlockChanges.Change> changes = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      int horizontal = input.readUnsignedByte();
      int y = input.readUnsignedByte();
      int state = MinecraftInput.varInt(input);
      changes.add(new SemanticBlockChanges.Change(
          (chunkX << 4) + (horizontal >> 4), y, (chunkZ << 4) + (horizontal & 0xF), state));
    }
    return new SemanticBlockChanges(direction, chunkX, chunkZ, List.copyOf(changes));
  }

  private static SemanticBlockChanges decodeModern(PacketDirection direction, DataInputStream input)
      throws IOException {
    long section = input.readLong();
    // Packed left to right: x:22, z:22, y:20.
    int sectionX = (int) (section >> 42);
    int sectionZ = (int) (section << 22 >> 42);
    int sectionY = (int) (section << 44 >> 44);
    int count = MinecraftInput.varInt(input);
    if (count < 0 || count > MAX_RECORDS) throw new IOException("multi block change count out of range: " + count);
    List<SemanticBlockChanges.Change> changes = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      long record = MinecraftInput.varLong(input);
      int state = (int) (record >>> 12);
      changes.add(new SemanticBlockChanges.Change(
          (sectionX << 4) + (int) ((record >> 8) & 0xF),
          (sectionY << 4) + (int) (record & 0xF),
          (sectionZ << 4) + (int) ((record >> 4) & 0xF),
          state));
    }
    return new SemanticBlockChanges(direction, sectionX, sectionZ, List.copyOf(changes));
  }

  /** Encodes a multi-block-change packet for the era of {@code protocol}. */
  public static byte[] encode(ProtocolDefinition protocol, SemanticBlockChanges changes) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    try (DataOutputStream output = new DataOutputStream(buffer)) {
      if (legacy(protocol)) {
        encodeLegacy(output, changes);
      } else {
        encodeModern(output, changes);
      }
    }
    int id = protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
        PacketKind.PLAY_MULTI_BLOCK_CHANGE);
    return PlayPackets.withId(id, buffer.toByteArray());
  }

  private static void encodeLegacy(DataOutputStream output, SemanticBlockChanges changes) throws IOException {
    // 1.13 cannot address Y outside 0..255, so changes above or below that world are unrepresentable.
    List<SemanticBlockChanges.Change> representable = changes.changes().stream()
        .filter(change -> change.y() >= 0 && change.y() <= LEGACY_MAX_Y)
        .toList();
    int skipped = changes.changes().size() - representable.size();
    if (skipped > 0) {
      ProtocolTrace.note("multi block change: " + skipped
          + " record(s) outside the 393 world height (0..255) omitted");
    }
    output.writeInt(changes.chunkX());
    output.writeInt(changes.chunkZ());
    MinecraftOutput.varInt(output, representable.size());
    for (SemanticBlockChanges.Change change : representable) {
      output.writeByte(((change.x() & 0xF) << 4) | (change.z() & 0xF));
      output.writeByte(change.y());
      MinecraftOutput.varInt(output, change.blockState());
    }
  }

  private static void encodeModern(DataOutputStream output, SemanticBlockChanges changes) throws IOException {
    // Modern records are section-relative, so a batch spanning several sections cannot be written
    // as one packet. Every change observed in one packet shares a section by construction.
    int sectionY = changes.changes().isEmpty() ? 0 : Math.floorDiv(changes.changes().get(0).y(), 16);
    long section = ((long) (changes.chunkX() & 0x3FFFFF) << 42)
        | ((long) (changes.chunkZ() & 0x3FFFFF) << 20)
        | (sectionY & 0xFFFFFL);
    output.writeLong(section);
    MinecraftOutput.varInt(output, changes.changes().size());
    for (SemanticBlockChanges.Change change : changes.changes()) {
      long record = ((long) change.blockState() << 12)
          | ((long) (change.x() & 0xF) << 8)
          | ((long) (change.z() & 0xF) << 4)
          | (change.y() & 0xF);
      MinecraftOutput.varLong(output, record);
    }
  }
}

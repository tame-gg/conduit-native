package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Records the real per-entity metadata layout a server actually sends.
 *
 * <p>Conduit's 404 ↔ 477 metadata translation was built from a pair-wide index
 * rule, which is right for the base classes and silently wrong for any concrete
 * class that reshaped its own fields. Rather than assume which those are, this
 * connects straight to a real server (no proxy), watches the entities an
 * operator summons, and writes down what it is actually told:
 *
 * <pre>
 *   entityType -> { (metadataIndex, serializerType), ... }
 * </pre>
 *
 * <p>Run it once against 1.13.2 and once against 1.14 with the same summon list
 * and the difference between the two files is the schema delta, measured rather
 * than guessed. Output is one {@code SCHEMA} line per entity type so the two
 * runs can be diffed directly.
 *
 * <pre>
 *   java ... MetadataSchemaProbe &lt;host&gt; &lt;port&gt; &lt;protocol&gt; &lt;name&gt; &lt;seconds&gt;
 * </pre>
 */
public final class MetadataSchemaProbe {
  private static final int MAX_FRAME = 8 * 1024 * 1024;

  /** entity id -> the type name the spawn packet gave it. */
  private final Map<Integer, String> entityTypes = new LinkedHashMap<>();
  /** entity type -> observed "index:type" pairs, in first-seen order. */
  private final Map<String, Set<String>> schema = new LinkedHashMap<>();

  private final ProtocolDefinition protocol;
  private final int version;

  private MetadataSchemaProbe(int version) {
    this.version = version;
    this.protocol = ProtocolDefinition.forVersion(version);
  }

  public static void main(String[] arguments) throws Exception {
    String host = arguments.length > 0 ? arguments[0] : "127.0.0.1";
    int port = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 25614;
    int version = arguments.length > 2 ? Integer.parseInt(arguments[2]) : 404;
    String name = arguments.length > 3 ? arguments[3] : "SchemaProbe";
    long seconds = arguments.length > 4 ? Long.parseLong(arguments[4]) : 60L;
    new MetadataSchemaProbe(version).run(host, port, name, seconds * 1000L);
  }

  private void run(String host, int port, String name, long millis) throws Exception {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 10_000);
      socket.setSoTimeout(20_000);
      var in = socket.getInputStream();
      var out = socket.getOutputStream();

      MinecraftFrames.write(out, handshake(host, port));
      MinecraftFrames.write(out, loginStart(name));
      if (!awaitLoginSuccess(in)) { System.out.println("RESULT=login-failed"); return; }

      long deadline = System.currentTimeMillis() + millis;
      while (System.currentTimeMillis() < deadline) {
        byte[] packet;
        try {
          packet = MinecraftFrames.read(in, MAX_FRAME);
        } catch (Exception exception) {
          System.out.println("read ended: " + exception);
          break;
        }
        observe(packet, out);
      }
      report();
      System.out.println("RESULT=ok");
    }
  }

  private void observe(byte[] packet, java.io.OutputStream out) throws Exception {
    int id = PlayPackets.packetId(packet);
    if (is(id, PacketKind.PLAY_KEEP_ALIVE)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream reply = new DataOutputStream(buffer);
      MinecraftOutput.varInt(reply,
          protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE));
      reply.write(PlayPackets.body(packet));
      MinecraftFrames.write(out, buffer.toByteArray());
      return;
    }
    if (is(id, PacketKind.PLAY_PLAYER_POSITION)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        body.readDouble(); body.readDouble(); body.readDouble();
        body.readFloat(); body.readFloat(); body.readByte();
        int teleport = MinecraftInput.varInt(body);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream reply = new DataOutputStream(buffer);
        MinecraftOutput.varInt(reply, protocol.id(ConnectionState.PLAY,
            PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TELEPORT_CONFIRM));
        MinecraftOutput.varInt(reply, teleport);
        MinecraftFrames.write(out, buffer.toByteArray());
      }
      return;
    }
    if (is(id, PacketKind.PLAY_LOGIN)) {
      // The player's own entity: id is the first field, and it is a Player.
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        entityTypes.put(body.readInt(), "minecraft:player");
      }
      // Only now is the connection really in PLAY; sending settings any earlier
      // reaches the server while it still decodes ids against the login table.
      send(out, PacketKind.PLAY_CLIENT_INFORMATION, clientSettings());
      return;
    }
    if (is(id, PacketKind.PLAY_SPAWN_LIVING_ENTITY)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        int entityId = MinecraftInput.varInt(body);
        body.readLong(); body.readLong();
        int type = MinecraftInput.varInt(body);
        entityTypes.put(entityId, "mob#" + type);
        body.readNBytes(3 * 8 + 3 + 3 * 2);
        record("mob#" + type, body);
      }
      return;
    }
    if (is(id, PacketKind.PLAY_SPAWN_PLAYER)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        int entityId = MinecraftInput.varInt(body);
        entityTypes.put(entityId, "minecraft:player");
        body.readNBytes(2 * 8 + 3 * 8 + 2);
        record("minecraft:player", body);
      }
      return;
    }
    if (is(id, PacketKind.PLAY_SPAWN_ENTITY)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        int entityId = MinecraftInput.varInt(body);
        body.readLong(); body.readLong();
        int type = version >= 477 ? MinecraftInput.varInt(body) : (body.readByte() & 0xff);
        entityTypes.put(entityId, "object#" + type);
      }
      return;
    }
    if (is(id, PacketKind.PLAY_SET_ENTITY_METADATA)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        int entityId = MinecraftInput.varInt(body);
        record(entityTypes.getOrDefault(entityId, "unknown#" + entityId), body);
      }
    }
  }

  /**
   * Walks one metadata block, noting every (index, serializer) pair. Values are
   * skipped by the serializer's own shape, so a type this probe cannot walk
   * stops the block rather than silently misreading the rest of it.
   */
  private void record(String type, DataInputStream in) throws Exception {
    Set<String> seen = schema.computeIfAbsent(type, ignored -> new LinkedHashSet<>());
    while (true) {
      int index = in.read();
      if (index < 0 || index == 0xff) return;
      int serializer = MinecraftInput.varInt(in);
      seen.add(index + ":" + serializer);
      if (!skip(in, serializer)) {
        seen.add("UNWALKABLE:" + serializer);
        return;
      }
    }
  }

  /** Consumes one metadata value. False when the serializer is not understood. */
  private boolean skip(DataInputStream in, int serializer) throws Exception {
    switch (serializer) {
      case 0 -> in.readByte();
      case 1 -> MinecraftInput.varInt(in);
      case 2 -> in.readFloat();
      case 3, 4 -> MinecraftInput.string(in, 262_144);
      case 5 -> { if (in.readBoolean()) MinecraftInput.string(in, 262_144); }
      case 6 -> {                                    // Slot
        if (in.readBoolean()) {
          MinecraftInput.varInt(in);
          in.readByte();
          skipNbt(in);
        }
      }
      case 7 -> in.readBoolean();
      case 8 -> { in.readFloat(); in.readFloat(); in.readFloat(); }
      case 9 -> in.readLong();
      case 10 -> { if (in.readBoolean()) in.readLong(); }
      case 11 -> MinecraftInput.varInt(in);
      case 12 -> { if (in.readBoolean()) { in.readLong(); in.readLong(); } }
      case 13 -> MinecraftInput.varInt(in);
      case 14 -> skipNbt(in);
      case 16 -> { MinecraftInput.varInt(in); MinecraftInput.varInt(in); MinecraftInput.varInt(in); }
      case 17 -> MinecraftInput.varInt(in);
      case 18 -> MinecraftInput.varInt(in);
      default -> { return false; }                   // particle and anything new
    }
    return true;
  }

  private void skipNbt(DataInputStream in) throws Exception {
    gg.tame.conduit.protocol.item.ItemNbt.readTag(in, true);
  }

  private void report() {
    System.out.println("=== METADATA SCHEMA protocol=" + version + " ===");
    schema.forEach((type, pairs) -> System.out.println("SCHEMA " + type + " " + String.join(",", pairs)));
    System.out.println("=== entity types seen: " + entityTypes.size() + " ===");
  }

  private boolean is(int id, PacketKind kind) {
    return protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, kind);
  }

  private void send(java.io.OutputStream out, PacketKind kind, byte[] body) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream stream = new DataOutputStream(buffer);
    MinecraftOutput.varInt(stream, protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, kind));
    stream.write(body);
    MinecraftFrames.write(out, buffer.toByteArray());
  }

  private byte[] clientSettings() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.string(out, "en_GB");
    out.writeByte(8);
    MinecraftOutput.varInt(out, 0);
    out.writeBoolean(true);
    out.writeByte(0x7f);
    MinecraftOutput.varInt(out, 1);
    return buffer.toByteArray();
  }

  private boolean awaitLoginSuccess(java.io.InputStream in) throws Exception {
    for (int attempt = 0; attempt < 16; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (id == 2) return true;
      if (id == 0) { System.out.println("login disconnect"); return false; }
    }
    return false;
  }

  private byte[] handshake(String host, int port) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, version);
    MinecraftOutput.string(out, host);
    out.writeShort(port);
    MinecraftOutput.varInt(out, 2);
    return buffer.toByteArray();
  }

  private byte[] loginStart(String name) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.string(out, name);
    return buffer.toByteArray();
  }
}

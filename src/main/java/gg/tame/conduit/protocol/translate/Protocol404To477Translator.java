package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolEras;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.chunk.ChunkCodec393;
import gg.tame.conduit.protocol.chunk.ChunkCodec477;
import gg.tame.conduit.protocol.codec.BlockPlaceCodec;
import gg.tame.conduit.protocol.codec.BlockPositionCodec;
import gg.tame.conduit.protocol.codec.JoinGameCodec;
import gg.tame.conduit.protocol.codec.SemanticCodec;
import gg.tame.conduit.protocol.inventory.ContainerCodec;
import gg.tame.conduit.protocol.semantic.EmptyPacket;
import gg.tame.conduit.protocol.semantic.JoinGamePacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.OpaquePacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.semantic.SemanticPacket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Incremental 404 (1.13.2) ↔ 477 (1.14) translator.
 *
 * <p>Slot wire form is identical. The gameplay-critical deltas are Join Game /
 * Respawn field sets, Open Window menu ids, Chunk Data heightmaps + light
 * split, Position packing, Block Place field order, Spawn Object type width,
 * Use Bed removal, and the new view/light packets. Packet ids are remapped by
 * {@link PacketKind} via each protocol's derived table — never by offsets.
 */
public final class Protocol404To477Translator implements ProtocolTranslator {
  public static Protocol404To477Translator client404() {
    return new Protocol404To477Translator(404, 477);
  }

  public static Protocol404To477Translator client477() {
    return new Protocol404To477Translator(477, 404);
  }

  private static final Set<PacketKind> DROP_OPTIONAL = EnumSet.of(
      PacketKind.PLAY_DECLARE_RECIPES,
      PacketKind.PLAY_UPDATE_ADVANCEMENTS,
      PacketKind.PLAY_TRADE_LIST,
      PacketKind.PLAY_TAGS,
      PacketKind.PLAY_UNLOCK_RECIPES,
      PacketKind.PLAY_USE_BED
  );

  private final int source;
  private final int target;
  private final SemanticCodec sourceCodec;
  private final SemanticCodec targetCodec;
  private final Queue<byte[]> toClient = new ArrayDeque<>();
  private final Queue<byte[]> toBackend = new ArrayDeque<>();
  private int lastViewDistance = 10;

  private Protocol404To477Translator(int source, int target) {
    this.source = source;
    this.target = target;
    this.sourceCodec = new SemanticCodec(ProtocolDefinition.forVersion(source), 2 * 1024 * 1024);
    this.targetCodec = new SemanticCodec(ProtocolDefinition.forVersion(target), 2 * 1024 * 1024);
  }

  @Override public byte[] clientToBackend(ConnectionState state, byte[] packet) {
    return translate(state, PacketDirection.CLIENT_TO_SERVER, packet, sourceCodec, targetCodec, source, target);
  }

  @Override public byte[] backendToClient(ConnectionState state, byte[] packet) {
    return translate(state, PacketDirection.SERVER_TO_CLIENT, packet, targetCodec, sourceCodec, target, source);
  }

  @Override public List<byte[]> drainToClient() { return drain(toClient); }
  @Override public List<byte[]> drainToBackend() { return drain(toBackend); }

  private static List<byte[]> drain(Queue<byte[]> queue) {
    if (queue.isEmpty()) return List.of();
    List<byte[]> packets = new ArrayList<>(queue.size());
    for (byte[] packet = queue.poll(); packet != null; packet = queue.poll()) packets.add(packet);
    return packets;
  }

  private byte[] translate(ConnectionState state, PacketDirection direction, byte[] packet,
                           SemanticCodec from, SemanticCodec to, int fromProtocol, int toProtocol) {
    try {
      int id = PlayPackets.packetId(packet);
      var kind = from.identify(state, direction, id);
      if (kind.isEmpty()) {
        throw new TranslationException("unsupported " + fromProtocol + " " + state + " packet id 0x"
            + Integer.toHexString(id) + " (no semantic mapping to " + toProtocol + ")");
      }
      PacketKind packetKind = kind.get();
      TranslationResult result = translateKind(state, direction, packetKind, packet, from, to, fromProtocol, toProtocol);
      return switch (result) {
        case TranslationResult.Translated translated -> {
          byte[] encoded = encodeSemantic(to, translated.packet());
          ProtocolTrace.translation(fromProtocol, toProtocol, state, direction, id,
              PlayPackets.packetId(encoded), packetKind.name());
          yield encoded;
        }
        case TranslationResult.Dropped dropped -> {
          ProtocolTrace.note("DROP " + fromProtocol + "→" + toProtocol + " " + packetKind + ": " + dropped.reason());
          yield null;
        }
        case TranslationResult.Unsupported unsupported ->
            throw new TranslationException(unsupported.reason());
        case TranslationResult.Passthrough ignored ->
            throw new TranslationException("passthrough not permitted for " + packetKind);
      };
    } catch (TranslationException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new TranslationException("404↔477 translation failed: " + exception.getMessage(), exception);
    }
  }

  private TranslationResult translateKind(ConnectionState state, PacketDirection direction, PacketKind kind,
                                          byte[] packet, SemanticCodec from, SemanticCodec to,
                                          int fromProtocol, int toProtocol) throws IOException {
    if (DROP_OPTIONAL.contains(kind)) {
      return new TranslationResult.Dropped(kind + " optional / removed across 404↔477");
    }
    if (!to.protocol().defines(state, direction, kind)
        && kind != PacketKind.PLAY_UPDATE_LIGHT
        && kind != PacketKind.PLAY_UPDATE_VIEW_POSITION
        && kind != PacketKind.PLAY_UPDATE_VIEW_DISTANCE) {
      return new TranslationResult.Dropped(kind + " missing on protocol " + toProtocol);
    }

    byte[] body = PlayPackets.body(packet);
    ProtocolDefinition sourceDef = from.protocol();
    ProtocolDefinition targetDef = to.protocol();

    return switch (kind) {
      case PLAY_LOGIN -> {
        JoinGamePacket join = JoinGameCodec.decode(sourceDef, packet);
        lastViewDistance = Math.max(2, join.viewDistance());
        yield new TranslationResult.Translated(join);
      }
      case PLAY_RESPAWN -> {
        byte[] reshaped = JoinGameCodec.encodeRespawn(sourceDef, targetDef, body);
        if (reshaped == null) yield new TranslationResult.Dropped("respawn could not be rematerialised");
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, reshaped));
      }
      case PLAY_OPEN_WINDOW -> {
        byte[] reshaped = ContainerCodec.openWindow(fromProtocol, toProtocol, body);
        if (reshaped == null) yield new TranslationResult.Dropped("open window has no target screen");
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, reshaped));
      }
      case PLAY_CHUNK_DATA -> {
        var chunk = ProtocolEras.sectionEmbeddedLight(fromProtocol)
            ? ChunkCodec393.decode(body)
            : ChunkCodec477.decode(body);
        byte[] encoded = ProtocolEras.chunkHeightmaps(toProtocol)
            ? ChunkCodec477.encode(chunk)
            : ChunkCodec393.encodeLegacy(chunk);
        if (ProtocolEras.chunkHeightmaps(toProtocol) && ProtocolEras.sectionEmbeddedLight(fromProtocol)) {
          queueToClient(PacketKind.PLAY_UPDATE_LIGHT, targetDef, ChunkCodec477.encodeUpdateLight(chunk));
          queueToClient(PacketKind.PLAY_UPDATE_VIEW_DISTANCE, targetDef, viewDistanceBody(lastViewDistance));
          queueToClient(PacketKind.PLAY_UPDATE_VIEW_POSITION, targetDef,
              viewPositionBody(chunk.chunkX(), chunk.chunkZ()));
        }
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, encoded));
      }
      case PLAY_UPDATE_LIGHT, PLAY_UPDATE_VIEW_POSITION, PLAY_UPDATE_VIEW_DISTANCE -> {
        if (!ProtocolEras.chunkHeightmaps(toProtocol)) {
          yield new TranslationResult.Dropped(kind + " absorbed (no 1.13.2 equivalent)");
        }
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, body));
      }
      case PLAY_BLOCK_PLACE -> {
        byte[] reshaped = BlockPlaceCodec.translate(body, sourceDef, targetDef);
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, reshaped));
      }
      case PLAY_PLAYER_DIGGING, PLAY_BLOCK_UPDATE, PLAY_SPAWN_POSITION,
           PLAY_OPEN_SIGN_EDITOR, PLAY_BLOCK_BREAK_ANIMATION, PLAY_BLOCK_ACTION,
           PLAY_BLOCK_ENTITY_DATA -> {
        yield new TranslationResult.Translated(new OpaquePacket(
            kind, state, direction, rematerialiseLeadingPosition(body, sourceDef, targetDef, kind)));
      }
      case PLAY_SPAWN_ENTITY -> {
        yield new TranslationResult.Translated(new OpaquePacket(
            kind, state, direction, translateSpawnObject(body, fromProtocol, toProtocol)));
      }
      case PLAY_DIFFICULTY -> {
        byte[] reshaped = body;
        if (fromProtocol <= 404 && toProtocol >= 477) {
          byte difficulty = body.length > 0 ? body[0] : 0;
          reshaped = new byte[] { difficulty, 0 };
        } else if (fromProtocol >= 477 && toProtocol <= 404) {
          reshaped = body.length > 0 ? new byte[] { body[0] } : new byte[] { 0 };
        }
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, reshaped));
      }
      case PLAY_SET_CONTAINER_CONTENT, PLAY_SET_CONTAINER_SLOT, PLAY_CLICK_WINDOW,
           PLAY_CREATIVE_SLOT, PLAY_ENTITY_EQUIPMENT, PLAY_CONFIRM_TRANSACTION,
           PLAY_CLOSE_WINDOW, PLAY_CLOSE_WINDOW_CLIENTBOUND, PLAY_WINDOW_PROPERTY,
           PLAY_KEEP_ALIVE, PLAY_CHAT, PLAY_SYSTEM_CHAT, PLAY_PLUGIN_MESSAGE,
           PLAY_FLYING, PLAY_POSITION, PLAY_LOOK, PLAY_POSITION_LOOK,
           PLAY_PLAYER_POSITION, PLAY_TELEPORT_CONFIRM, PLAY_UNLOAD_CHUNK,
           PLAY_GAME_EVENT, PLAY_ABILITIES, PLAY_HELD_ITEM, PLAY_SET_CARRIED_ITEM,
           PLAY_UPDATE_HEALTH, PLAY_SET_EXPERIENCE, PLAY_UPDATE_TIME,
           PLAY_ENTITY_DESTROY, PLAY_ENTITY_STATUS, PLAY_SWING_ARM,
           PLAY_CLIENT_INFORMATION, PLAY_USE_ITEM, PLAY_CLIENT_COMMAND,
           PLAY_ENTITY_ACTION, PLAY_INTERACT_ENTITY, PLAY_DISCONNECT,
           PLAY_SPAWN_LIVING_ENTITY, PLAY_SPAWN_PLAYER, PLAY_SPAWN_EXPERIENCE_ORB,
           PLAY_ENTITY_RELATIVE_MOVE, PLAY_ENTITY_MOVE_LOOK, PLAY_ENTITY_LOOK,
           PLAY_ENTITY_HEAD_ROTATION, PLAY_ENTITY_VELOCITY, PLAY_ENTITY_TELEPORT,
           PLAY_COLLECT_ITEM, PLAY_SET_PASSENGERS, PLAY_ANIMATION, PLAY_COMBAT_EVENT,
           PLAY_UPDATE_ATTRIBUTES, PLAY_SET_ENTITY_METADATA, PLAY_PLAYER_INFO_UPDATE,
           PLAY_MULTI_BLOCK_CHANGE, PLAY_WORLD_EVENT,
           PLAY_EXPLOSION, PLAY_RESOURCE_PACK_SEND, PLAY_RESOURCE_PACK_STATUS,
           PLAY_TAB_COMPLETE, PLAY_TAB_COMPLETE_REQUEST, PLAY_DECLARE_COMMANDS -> {
        // Same field layout (or Slot-identical). Ids remapped by encode.
        SemanticPacket decoded = from.decode(state, direction, packet);
        if (decoded instanceof EmptyPacket || decoded instanceof PluginMessagePacket
            || decoded instanceof KeepAlivePacket || decoded instanceof OpaquePacket
            || decoded instanceof JoinGamePacket) {
          yield new TranslationResult.Translated(decoded);
        }
        yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, body));
      }
      default -> {
        SemanticPacket decoded = from.decode(state, direction, packet);
        if (decoded instanceof EmptyPacket || decoded instanceof PluginMessagePacket
            || decoded instanceof KeepAlivePacket || decoded instanceof OpaquePacket) {
          yield new TranslationResult.Translated(decoded);
        }
        yield new TranslationResult.Dropped(kind + " not rematerialised for 404↔477 core path yet");
      }
    };
  }

  private static byte[] encodeSemantic(SemanticCodec codec, SemanticPacket semantic) throws IOException {
    if (semantic instanceof JoinGamePacket join) return JoinGameCodec.encode(codec.protocol(), join);
    return codec.encode(semantic);
  }

  private void queueToClient(PacketKind kind, ProtocolDefinition destination, byte[] body) throws IOException {
    if (!destination.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind)) return;
    toClient.add(PlayPackets.withId(
        destination.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind), body));
  }

  private static byte[] viewDistanceBody(int distance) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(5);
    MinecraftOutput.varInt(new DataOutputStream(buffer), distance);
    return buffer.toByteArray();
  }

  private static byte[] viewPositionBody(int chunkX, int chunkZ) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(10);
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, chunkX);
    MinecraftOutput.varInt(out, chunkZ);
    return buffer.toByteArray();
  }

  private static byte[] rematerialiseLeadingPosition(byte[] body, ProtocolDefinition source,
                                                     ProtocolDefinition target, PacketKind kind)
      throws IOException {
    if (ProtocolEras.legacyPosition(source.version().number())
        == ProtocolEras.legacyPosition(target.version().number())) {
      return body;
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length);
      DataOutputStream out = new DataOutputStream(buffer);
      if (kind == PacketKind.PLAY_PLAYER_DIGGING) {
        MinecraftOutput.varInt(out, MinecraftInput.varInt(in)); // status
        out.writeLong(BlockPositionCodec.translate(source, target, in.readLong()));
        out.writeByte(in.readByte()); // face
      } else if (kind == PacketKind.PLAY_BLOCK_BREAK_ANIMATION) {
        MinecraftOutput.varInt(out, MinecraftInput.varInt(in)); // entityId
        out.writeLong(BlockPositionCodec.translate(source, target, in.readLong()));
        out.writeByte(in.readByte());
      } else if (kind == PacketKind.PLAY_BLOCK_ACTION) {
        out.writeLong(BlockPositionCodec.translate(source, target, in.readLong()));
        out.write(in.readAllBytes());
      } else {
        // Block change, spawn position, open sign, block entity data: position first.
        out.writeLong(BlockPositionCodec.translate(source, target, in.readLong()));
        out.write(in.readAllBytes());
      }
      out.flush();
      return buffer.toByteArray();
    }
  }

  private static byte[] translateSpawnObject(byte[] body, int fromProtocol, int toProtocol)
      throws IOException {
    boolean fromVar = fromProtocol >= 477;
    boolean toVar = toProtocol >= 477;
    if (fromVar == toVar) return body;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 4);
      DataOutputStream out = new DataOutputStream(buffer);
      MinecraftOutput.varInt(out, MinecraftInput.varInt(in)); // entityId
      out.writeLong(in.readLong()); // uuid high
      out.writeLong(in.readLong()); // uuid low
      int type = fromVar ? MinecraftInput.varInt(in) : in.readByte();
      if (toVar) MinecraftOutput.varInt(out, type & 0xff);
      else out.writeByte(type);
      out.write(in.readAllBytes());
      out.flush();
      return buffer.toByteArray();
    }
  }
}

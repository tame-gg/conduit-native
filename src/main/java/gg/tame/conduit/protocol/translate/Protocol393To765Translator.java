package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.codec.JoinGameCodec;
import gg.tame.conduit.protocol.codec.SemanticCodec;
import gg.tame.conduit.protocol.semantic.DisconnectPacket;
import gg.tame.conduit.protocol.semantic.EmptyPacket;
import gg.tame.conduit.protocol.semantic.JoinGamePacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.LoginStartPacket;
import gg.tame.conduit.protocol.semantic.LoginSuccessPacket;
import gg.tame.conduit.protocol.semantic.MovementPacket;
import gg.tame.conduit.protocol.semantic.PlayerPositionPacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.semantic.SemanticChat;
import gg.tame.conduit.protocol.semantic.SemanticPacket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 393 (1.13) ↔ 765 (1.20.4) translation foundation.
 * Configuration packets toward a 393 client are dropped (absorbed by {@link ConfigurationAbsorber}).
 * Join Game / Login / KeepAlive / movement / plugin messages / disconnect are field-translated.
 * Chunks / entity metadata / signed chat remain unsupported (PARTIAL).
 *
 * <p>Protocol data: PrismarineJS minecraft-data 1.13 + 1.20.4 — not another proxy's implementation.
 */
public final class Protocol393To765Translator implements ProtocolTranslator {
  public static final Protocol393To765Translator CLIENT_393_BACKEND_765 = new Protocol393To765Translator(393, 765);
  public static final Protocol393To765Translator CLIENT_765_BACKEND_393 = new Protocol393To765Translator(765, 393);

  private static final Set<PacketKind> CONFIG_CONSUME = EnumSet.of(
      PacketKind.CONFIGURATION_FINISH,
      PacketKind.CONFIGURATION_PLUGIN_MESSAGE,
      PacketKind.CONFIGURATION_KEEP_ALIVE,
      PacketKind.CONFIGURATION_DISCONNECT,
      PacketKind.CONFIGURATION_REGISTRY,
      PacketKind.CONFIGURATION_KNOWN_PACKS,
      PacketKind.CONFIGURATION_RESET_CHAT,
      PacketKind.CONFIGURATION_CLIENT_INFORMATION,
      PacketKind.LOGIN_ACKNOWLEDGED,
      PacketKind.PLAY_START_CONFIGURATION,
      PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED
  );

  private final int clientProtocol;
  private final int backendProtocol;
  private final SemanticCodec clientCodec;
  private final SemanticCodec backendCodec;

  private Protocol393To765Translator(int clientProtocol, int backendProtocol) {
    this.clientProtocol = clientProtocol;
    this.backendProtocol = backendProtocol;
    this.clientCodec = new SemanticCodec(ProtocolDefinition.forVersion(clientProtocol), 2 * 1024 * 1024);
    this.backendCodec = new SemanticCodec(ProtocolDefinition.forVersion(backendProtocol), 2 * 1024 * 1024);
  }

  public int clientProtocol() { return clientProtocol; }
  public int backendProtocol() { return backendProtocol; }

  @Override public byte[] clientToBackend(ConnectionState state, byte[] packet) {
    return translate(state, PacketDirection.CLIENT_TO_SERVER, packet, clientCodec, backendCodec, clientProtocol, backendProtocol);
  }

  @Override public byte[] backendToClient(ConnectionState state, byte[] packet) {
    return translate(state, PacketDirection.SERVER_TO_CLIENT, packet, backendCodec, clientCodec, backendProtocol, clientProtocol);
  }

  private byte[] translate(ConnectionState state, PacketDirection direction, byte[] packet,
                           SemanticCodec from, SemanticCodec to, int fromProtocol, int toProtocol) {
    try {
      int id = PlayPackets.packetId(packet);
      Optional<PacketKind> kindOpt = from.identify(state, direction, id);
      if (kindOpt.isEmpty()) {
        // Emit the body so the packet can be identified from the real wire rather than guessed.
        ProtocolTrace.note("UNIDENTIFIED " + fromProtocol + " " + state + " " + direction
            + " 0x" + Integer.toHexString(id) + " body=" + ProtocolTrace.hex(PlayPackets.body(packet), 64));
        throw new TranslationException("unsupported " + fromProtocol + " " + state
            + " packet id 0x" + Integer.toHexString(id) + " toward " + toProtocol);
      }
      PacketKind kind = kindOpt.get();

      // Configuration (and config-only control) must never reach a 393 client.
      if (state == ConnectionState.CONFIGURATION || CONFIG_CONSUME.contains(kind)) {
        if (!to.protocol().hasConfiguration() || !to.protocol().defines(state, direction, kind)) {
          ProtocolTrace.note("DROP " + fromProtocol + "→" + toProtocol + " " + kind + " (configuration bridge)");
          ProtocolTrace.translation(fromProtocol, state, direction, id, kind.name(),
              "consumed by " + toProtocol + " compatibility bridge", toProtocol, state, -1);
          return null;
        }
      }

      TranslationResult result = translateKind(state, direction, kind, packet, from, to);
      return switch (result) {
        case TranslationResult.Translated translated -> {
          byte[] encoded = encodeSemantic(to, translated.packet());
          ProtocolTrace.translation(fromProtocol, state, direction, id, kind.name(),
              "Translator " + fromProtocol + "→" + toProtocol,
              toProtocol, translated.packet().state(), PlayPackets.packetId(encoded));
          yield encoded;
        }
        case TranslationResult.Dropped dropped -> {
          ProtocolTrace.note("DROP " + fromProtocol + "→" + toProtocol + " " + kind + ": " + dropped.reason());
          yield null;
        }
        case TranslationResult.Unsupported unsupported ->
            throw new TranslationException(unsupported.reason());
        case TranslationResult.Passthrough ignored ->
            throw new TranslationException("passthrough not permitted for " + kind);
      };
    } catch (TranslationException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new TranslationException("393↔765 translation failed: " + exception.getMessage(), exception);
    }
  }

  private TranslationResult translateKind(ConnectionState state, PacketDirection direction, PacketKind kind,
                                          byte[] packet, SemanticCodec from, SemanticCodec to) throws IOException {
    ProtocolDefinition source = from.protocol();
    ProtocolDefinition target = to.protocol();

    return switch (kind) {
      case LOGIN_START -> {
        LoginStartPacket semantic = JoinGameCodec.decodeLoginStart(source, PlayPackets.body(packet));
        if (target.capabilities().loginStartUuid() && semantic.clientUuid().isEmpty()) {
          // Target needs UUID — offline UUID from username (auth profile applied at session layer).
          semantic = new LoginStartPacket(direction, semantic.username(),
              Optional.of(gg.tame.conduit.login.LoginStart.offlineUuid(semantic.username())));
        }
        yield new TranslationResult.Translated(semantic);
      }
      case LOGIN_SUCCESS -> {
        LoginSuccessPacket semantic = JoinGameCodec.decodeLoginSuccess(source, packet);
        yield new TranslationResult.Translated(semantic);
      }
      case PLAY_LOGIN -> {
        JoinGamePacket join = JoinGameCodec.decode(source, packet);
        yield new TranslationResult.Translated(join);
      }
      case PLAY_KEEP_ALIVE, CONFIGURATION_KEEP_ALIVE -> {
        SemanticPacket decoded = from.decode(state, direction, packet);
        if (!(decoded instanceof KeepAlivePacket keep)) {
          yield new TranslationResult.Unsupported("keepalive decode failed");
        }
        ConnectionState outState = target.defines(state, direction, kind) ? state
            : (target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_KEEP_ALIVE) ? ConnectionState.PLAY : state);
        yield new TranslationResult.Translated(new KeepAlivePacket(outState, direction, keep.id()));
      }
      case PLAY_PLUGIN_MESSAGE, CONFIGURATION_PLUGIN_MESSAGE -> {
        SemanticPacket decoded = from.decode(state, direction, packet);
        if (!(decoded instanceof PluginMessagePacket plugin)) {
          yield new TranslationResult.Unsupported("plugin message decode failed");
        }
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_PLUGIN_MESSAGE)
            && !target.defines(state, direction, kind)) {
          yield new TranslationResult.Dropped("plugin message missing on target");
        }
        ConnectionState outState = target.defines(state, direction, kind) ? state : ConnectionState.PLAY;
        PacketKind outKind = outState == ConnectionState.CONFIGURATION
            ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE : PacketKind.PLAY_PLUGIN_MESSAGE;
        yield new TranslationResult.Translated(
            new PluginMessagePacket(outState, direction, plugin.channel(), plugin.data()));
      }
      case PLAY_DISCONNECT, LOGIN_DISCONNECT, CONFIGURATION_DISCONNECT -> {
        DisconnectPacket disconnect = JoinGameCodec.decodeDisconnect(source, state, kind, packet);
        PacketKind outKind = target.defines(state, direction, kind) ? kind
            : (state == ConnectionState.LOGIN ? PacketKind.LOGIN_DISCONNECT : PacketKind.PLAY_DISCONNECT);
        ConnectionState outState = target.defines(state, direction, outKind) ? state
            : (outKind == PacketKind.LOGIN_DISCONNECT ? ConnectionState.LOGIN : ConnectionState.PLAY);
        yield new TranslationResult.Translated(new DisconnectPacket(outState, direction, outKind, disconnect.reason()));
      }
      case PLAY_PLAYER_POSITION -> {
        PlayerPositionPacket pos = JoinGameCodec.decodePlayerPosition(packet);
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_PLAYER_POSITION)) {
          yield new TranslationResult.Unsupported("target missing player position");
        }
        yield new TranslationResult.Translated(pos);
      }
      case PLAY_POSITION, PLAY_POSITION_LOOK, PLAY_LOOK, PLAY_FLYING -> {
        MovementPacket move = JoinGameCodec.decodeMovement(kind, packet);
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Unsupported("target missing " + kind);
        }
        yield new TranslationResult.Translated(move);
      }
      case PLAY_SYSTEM_CHAT, PLAY_CHAT -> {
        // Safest unsigned path: plain text system/chat without fabricating signatures.
        String plain = extractChatPlain(source, packet);
        byte[] encoded = encodeSystemOrChat(target, plain);
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_SYSTEM_CHAT)
                ? PacketKind.PLAY_SYSTEM_CHAT : PacketKind.PLAY_CHAT,
            ConnectionState.PLAY, direction, PlayPackets.body(encoded)));
      }
      case PLAY_CHAT_COMMAND -> {
        // Remap command string packet id; body is a string on both for unsigned path.
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT_COMMAND)
            && !target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT)) {
          yield new TranslationResult.Dropped("no chat command on target");
        }
        PacketKind out = target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT_COMMAND)
            ? PacketKind.PLAY_CHAT_COMMAND : PacketKind.PLAY_CHAT;
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            out, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case LOGIN_SET_COMPRESSION -> {
        // Backend-only: Conduit enables compression on the backend socket and never forwards
        // Set Compression to any client (client↔proxy stays uncompressed).
        yield new TranslationResult.Dropped("set compression consumed for backend framing");
      }
      case LOGIN_ENCRYPTION_REQUEST, LOGIN_ENCRYPTION_RESPONSE,
           LOGIN_PLUGIN_REQUEST, LOGIN_PLUGIN_RESPONSE -> {
        if (!target.defines(state, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, state, direction, PlayPackets.body(packet)));
      }
      case PLAY_PLAYER_INFO_UPDATE, PLAY_PLAYER_INFO_REMOVE -> {
        var info = gg.tame.conduit.protocol.codec.PlayerInfoCodec.decode(source, packet);
        yield new TranslationResult.Translated(info);
      }
      case PLAY_CHUNK_DATA -> {
        byte[] body = PlayPackets.body(packet);
        var chunk = source.version().number() <= 404
            ? gg.tame.conduit.protocol.chunk.ChunkCodec393.decode(body)
            : gg.tame.conduit.protocol.chunk.ChunkCodec765.decode(body);
        // Remap into target era inside encode.
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.ChunkDataPacket(direction, chunk));
      }
      case PLAY_UPDATE_LIGHT -> {
        // 1.13 embeds light in chunk sections; standalone light updates have no 393 equivalent.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped("update light consumed for 393 (light carried in chunk data)");
        }
        yield new TranslationResult.Unsupported("update light toward modern not implemented");
      }
      case PLAY_DECLARE_RECIPES, PLAY_TAGS, PLAY_UPDATE_VIEW_POSITION, PLAY_UPDATE_VIEW_DISTANCE,
           PLAY_SIMULATION_DISTANCE, PLAY_CHUNK_BATCH_START, PLAY_CHUNK_BATCH_FINISHED, PLAY_UNLOCK_RECIPES,
           PLAY_SERVER_DATA, PLAY_SET_TICKING_STATE, PLAY_STEP_TICK -> {
        // Modern-only or schema-incompatible with 393. Safe to omit for initial world entry.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(kind + " dropped for 393 (no compatible wire form / optional for entry)");
        }
        yield new TranslationResult.Unsupported(kind + " modern→modern not implemented");
      }
      case PLAY_DIFFICULTY -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("difficulty missing on target");
        }
        byte[] body = PlayPackets.body(packet);
        // 765: difficulty(u8) + difficultyLocked(bool). 393: difficulty(u8) only.
        if (source.version().number() > 404 && target.version().number() <= 404) {
          if (body.length < 1) throw new IOException("short difficulty");
          body = new byte[] { body[0] };
        } else if (source.version().number() <= 404 && target.version().number() > 404) {
          byte difficulty = body.length > 0 ? body[0] : 0;
          body = new byte[] { difficulty, 0 };
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, body));
      }
      case PLAY_UNLOAD_CHUNK, PLAY_GAME_EVENT, PLAY_ABILITIES, PLAY_TELEPORT_CONFIRM, PLAY_HELD_ITEM, PLAY_ENTITY_STATUS, PLAY_UPDATE_TIME -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        // These packets share compatible field layouts between 393 and 765 for the fields we care about.
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_SET_CONTAINER_CONTENT, PLAY_SET_CONTAINER_SLOT -> {
        // Slot payloads carry item ids from the sending era's registry. Conduit has no verified
        // 1.13 <-> 1.20.4 item mapping yet, so translating would risk wrong or corrupt items.
        // Fail closed: the 393 client sees an empty inventory rather than a mistranslated one.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(
              kind + " withheld from 393 (item registry mapping not implemented)");
        }
        yield new TranslationResult.Unsupported(kind + " modern→modern not implemented");
      }
      case PLAY_WORLD_BORDER_INIT -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("world border missing on target");
        }
        // Field-level: the two warning values are ordered differently per era.
        yield new TranslationResult.Translated(
            gg.tame.conduit.protocol.codec.WorldBorderCodec.decodeInit(source, direction, packet));
      }
      case PLAY_SPAWN_POSITION -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("spawn position missing");
        }
        // 765 may include angle after position; 393 is position only — trim or pad carefully.
        byte[] body = PlayPackets.body(packet);
        if (source.version().number() > 404 && target.version().number() <= 404) {
          // Keep first 8 bytes of packed position if present; drop trailing angle float.
          if (body.length > 8) body = java.util.Arrays.copyOf(body, 8);
        } else if (source.version().number() <= 404 && target.version().number() > 404) {
          ByteArrayOutputStream extended = new ByteArrayOutputStream();
          extended.write(body);
          // default angle 0
          java.io.DataOutputStream dos = new java.io.DataOutputStream(extended);
          dos.writeFloat(0f);
          body = extended.toByteArray();
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, body));
      }
      case PLAY_ENTITY_DESTROY -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("entity destroy missing");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_DECLARE_COMMANDS, PLAY_TAB_COMPLETE, PLAY_TAB_COMPLETE_REQUEST, PLAY_CLIENT_INFORMATION -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing");
        }
        if (kind == PacketKind.PLAY_DECLARE_COMMANDS) {
          yield new TranslationResult.Unsupported("declare commands 393↔765 requires dedicated tree translation");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      default -> new TranslationResult.Unsupported(kind + " not in 393↔765 world-entry set");
    };
  }

  private byte[] encodeSemantic(SemanticCodec codec, SemanticPacket semantic) throws IOException {
    if (semantic instanceof JoinGamePacket join) return JoinGameCodec.encode(codec.protocol(), join);
    if (semantic instanceof LoginSuccessPacket success) return JoinGameCodec.encodeLoginSuccess(codec.protocol(), success);
    if (semantic instanceof LoginStartPacket start) return JoinGameCodec.encodeLoginStart(codec.protocol(), start);
    if (semantic instanceof DisconnectPacket disconnect) return JoinGameCodec.encodeDisconnect(codec.protocol(), disconnect);
    if (semantic instanceof PlayerPositionPacket pos) return JoinGameCodec.encodePlayerPosition(codec.protocol(), pos);
    if (semantic instanceof MovementPacket move) return JoinGameCodec.encodeMovement(codec.protocol(), move);
    if (semantic instanceof gg.tame.conduit.protocol.semantic.WorldBorderInitPacket border) {
      return gg.tame.conduit.protocol.codec.WorldBorderCodec.encodeInit(codec.protocol(), border);
    }
    if (semantic instanceof gg.tame.conduit.protocol.semantic.SemanticPlayerInfo info) {
      return gg.tame.conduit.protocol.codec.PlayerInfoCodec.encode(codec.protocol(), info);
    }
    if (semantic instanceof gg.tame.conduit.protocol.semantic.ChunkDataPacket chunkPacket) {
      byte[] body;
      var chunk = chunkPacket.chunk();
      if (codec.protocol().version().number() <= 404) {
        // Ensure 393 state space + Y 0..255.
        boolean alreadyLegacyIds = chunk.biomes1024().length == 1024;
        body = alreadyLegacyIds
            ? gg.tame.conduit.protocol.chunk.ChunkCodec393.encodeLegacy(chunk.projectLegacyHeight())
            : gg.tame.conduit.protocol.chunk.ChunkCodec393.encodeLegacy(chunk.toLegacy113());
      } else {
        boolean fromLegacy = chunk.biomes1024().length == 1024;
        body = fromLegacy
            ? gg.tame.conduit.protocol.chunk.ChunkCodec765.encodeFromLegacy(chunk)
            : gg.tame.conduit.protocol.chunk.ChunkCodec765.encode(chunk);
      }
      int id = codec.protocol().id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_CHUNK_DATA);
      return PlayPackets.withId(id, body);
    }
    if (semantic instanceof KeepAlivePacket || semantic instanceof PluginMessagePacket
        || semantic instanceof EmptyPacket || semantic instanceof gg.tame.conduit.protocol.semantic.OpaquePacket) {
      return codec.encode(semantic);
    }
    throw new IOException("cannot encode " + semantic.getClass().getSimpleName());
  }

  private static String extractChatPlain(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
      if (protocol.capabilities().legacyPlayChat()) {
        String json = gg.tame.conduit.protocol.MinecraftInput.string(input, 262144);
        return SemanticChat.system(json).plain().isEmpty() ? json : stripText(json);
      }
      // Modern system chat: NBT or JSON then boolean — best-effort plain.
      return " ";
    }
  }

  private static byte[] encodeSystemOrChat(ProtocolDefinition protocol, String plain) throws IOException {
    return PlayPackets.systemChat(protocol, plain == null || plain.isBlank() ? " " : plain);
  }

  private static String stripText(String json) {
    int idx = json.indexOf("\"text\"");
    if (idx < 0) return json.length() > 256 ? json.substring(0, 256) : json;
    int start = json.indexOf('"', idx + 6);
    if (start < 0) return json;
    start++;
    int end = json.indexOf('"', start);
    return end < 0 ? json.substring(start) : json.substring(start, end);
  }
}

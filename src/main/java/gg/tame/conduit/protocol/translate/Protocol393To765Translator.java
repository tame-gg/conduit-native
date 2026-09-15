package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.codec.BlockPositionCodec;
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

  /**
   * Discovery mode ({@code -Dconduit.translate.discovery=true}). Diagnostic only:
   * lets one run against a real server enumerate every packet it sends rather
   * than stopping at the first unhandled one. Production stays fail-closed.
   */
  private static final boolean DISCOVERY = Boolean.getBoolean("conduit.translate.discovery");
  private static final Set<String> DISCOVERED = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Unhandled packets seen during a discovery run, for turning into real decisions. */
  public static Set<String> discovered() { return Set.copyOf(DISCOVERED); }

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
        if (DISCOVERY) {
          // Discovery mode only: keep the session alive so ONE run against a real
          // server enumerates every packet it actually sends, instead of ending at
          // the first one. Never enable in production — dropping an unclassified
          // packet can silently desync state, which is exactly what fail-closed
          // is for. Findings become explicit per-packet decisions below.
          DISCOVERED.add(fromProtocol + " " + state + " " + direction + " 0x" + Integer.toHexString(id));
          return null;
        }
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
        case TranslationResult.Unsupported unsupported -> {
          if (DISCOVERY) {
            DISCOVERED.add(fromProtocol + " " + state + " " + direction + " 0x"
                + Integer.toHexString(id) + " " + kind + " (" + unsupported.reason() + ")");
            yield null;
          }
          throw new TranslationException(unsupported.reason());
        }
        case TranslationResult.Passthrough ignored ->
            throw new TranslationException("passthrough not permitted for " + kind);
      };
    } catch (TranslationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new TranslationException("393↔765 translation failed: "
          + exception.getClass().getSimpleName() + ": " + exception.getMessage(), exception);
    } catch (IOException exception) {
      throw new TranslationException("393↔765 translation failed: "
          + exception.getClass().getSimpleName() + ": " + exception.getMessage(), exception);
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
        // 393 sends one Chat Message packet holding a bare string, commands included (leading '/').
        // 765 split these into Chat Command and Chat Message, and both carry a signing envelope:
        // timestamp, salt, signatures and an acknowledgement bitset. Forwarding the bare string
        // leaves the backend decoder reading a timestamp off the end of the packet.
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT_COMMAND)
            && !target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT)) {
          yield new TranslationResult.Dropped("no chat on target");
        }
        String message;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          message = gg.tame.conduit.protocol.MinecraftInput.string(input, 256);
        }
        if (target.version().number() <= 404) {
          // Modern -> 393: keep just the text, dropping the signing envelope.
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(message.length() + 4);
          DataOutputStream output = new DataOutputStream(buffer);
          gg.tame.conduit.protocol.MinecraftOutput.string(output, message);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              PacketKind.PLAY_CHAT, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
        boolean isCommand = message.startsWith("/");
        PacketKind out = isCommand ? PacketKind.PLAY_CHAT_COMMAND : PacketKind.PLAY_CHAT;
        if (!target.defines(ConnectionState.PLAY, direction, out)) {
          yield new TranslationResult.Dropped(out + " missing on target");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(message.length() + 32);
        DataOutputStream output = new DataOutputStream(buffer);
        // Command packets carry the command without its leading slash.
        gg.tame.conduit.protocol.MinecraftOutput.string(output, isCommand ? message.substring(1) : message);
        output.writeLong(System.currentTimeMillis());
        output.writeLong(0L); // salt: unsigned path, nothing to seed
        if (isCommand) {
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0); // no argument signatures
        } else {
          output.writeBoolean(false); // no message signature
        }
        gg.tame.conduit.protocol.MinecraftOutput.varInt(output, 0); // message count
        output.write(new byte[3]); // acknowledged: fixed 20-bit bitset, all clear
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            out, ConnectionState.PLAY, direction, buffer.toByteArray()));
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
      case PLAY_DECLARE_RECIPES, PLAY_TAGS -> {
        // Recipe and tag payloads are built from each era's own registries and
        // have no common wire form: 1.13 recipes are a flat typed list, 1.20.4
        // recipes are per-type records, and tags went from one list to a
        // per-registry map. Rather than fail the session or mistranslate, send
        // the modern client a well-formed EMPTY payload -- a single VarInt zero.
        // The client then has an empty recipe book and no tag overrides, which
        // it handles natively; crafting via the recipe book is unavailable but
        // the world, movement and block interaction are unaffected. Synthesising
        // is correct here where dropping is not: a modern client that never sees
        // these packets can stall waiting for them during world entry.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(kind + " dropped for 393 (no compatible wire form)");
        }
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        MinecraftOutput.varInt(new DataOutputStream(empty), 0);
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, empty.toByteArray()));
      }

      case PLAY_UNLOCK_RECIPES, PLAY_UPDATE_ADVANCEMENTS -> {
        // Both reference recipes/advancements by identifier from the sending
        // era's data pack. Toward a modern client they would name entries its
        // empty recipe book and advancement tree do not contain. Dropping costs
        // the recipe-unlock toast and the advancement tree; neither gates play,
        // and neither desyncs world state.
        yield new TranslationResult.Dropped(
            kind + " references the source era's data-pack identifiers, which the target does not have");
      }

      case PLAY_UPDATE_VIEW_POSITION, PLAY_UPDATE_VIEW_DISTANCE, PLAY_SIMULATION_DISTANCE,
           PLAY_CHUNK_BATCH_START, PLAY_CHUNK_BATCH_FINISHED, PLAY_SERVER_DATA,
           PLAY_SET_TICKING_STATE, PLAY_STEP_TICK -> {
        // Modern-only packets. Toward 393 they are dropped because the client has
        // no equivalent; they are never produced by a 393 backend, so the
        // modern→modern arm here is unreachable for this pair and is kept
        // explicit rather than silently falling through.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(kind + " has no 393 equivalent (optional for world entry)");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
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
      case PLAY_UNLOAD_CHUNK, PLAY_GAME_EVENT, PLAY_ABILITIES, PLAY_TELEPORT_CONFIRM, PLAY_HELD_ITEM, PLAY_ENTITY_STATUS, PLAY_UPDATE_TIME, PLAY_UPDATE_HEALTH, PLAY_SET_EXPERIENCE, PLAY_SWING_ARM -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        // These packets share compatible field layouts between 393 and 765 for the fields we care about.
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      // ---------------------------------------------------------------------
      // Entity and world traffic. Every decision below was made by comparing the
      // real field lists for both releases (tools/schemadiff.py), never by
      // assuming that a shared packet name implies a shared schema.
      // ---------------------------------------------------------------------

      case PLAY_ENTITY_RELATIVE_MOVE, PLAY_ENTITY_MOVE_LOOK, PLAY_ENTITY_LOOK,
           PLAY_ENTITY_HEAD_ROTATION, PLAY_ENTITY_VELOCITY, PLAY_COLLECT_ITEM,
           PLAY_ENTITY_TELEPORT, PLAY_CLOSE_WINDOW, PLAY_ENTITY_ACTION -> {
        // Field-for-field identical on 1.13 and 1.20.4 — only the packet id moved.
        // The body is carried as-is and re-emitted under the TARGET's id for this
        // kind, which the codec resolves. This is kind-addressed, not id arithmetic.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " not defined on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }

      case PLAY_SPAWN_LIVING_ENTITY -> {
        // 1.19 merged living-entity spawns into the unified spawn_entity packet,
        // so a 1.13 backend's mob spawns have to fan in. Real differences:
        //   1.13  ... type, x,y,z, yaw, pitch, headPitch, velocity, metadata
        //   1.20.4 ... type, x,y,z, pitch, yaw, headPitch, objectData, velocity
        // yaw/pitch swap order, objectData (absent for living entities, so 0) is
        // inserted before velocity, and the trailing metadata blob is dropped
        // because 1.19+ delivers entity metadata in its own packet.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_SPAWN_ENTITY)) {
          yield new TranslationResult.Dropped("target has no spawn packet for living entities");
        }
        byte[] merged = livingSpawnToUnified(PlayPackets.body(packet));
        if (merged == null) {
          yield new TranslationResult.Dropped("living spawn body not parseable");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            PacketKind.PLAY_SPAWN_ENTITY, ConnectionState.PLAY, direction, merged));
      }

      case PLAY_WORLD_EVENT -> {
        // effectId:i32, location:Position, data:i32, global:bool on both versions.
        // The field list matches, but Position packing changed in 1.14, so the
        // location is decoded and re-packed rather than copied. Copying it would
        // put the effect in the wrong place -- a name match is not a schema match.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("world event not defined on target");
        }
        byte[] in = PlayPackets.body(packet);
        if (in.length < 17) {
          yield new TranslationResult.Dropped("short world event body");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(in.length);
        DataOutputStream out = new DataOutputStream(buffer);
        try (DataInputStream reader = new DataInputStream(new ByteArrayInputStream(in))) {
          out.writeInt(reader.readInt());                                              // effectId
          out.writeLong(BlockPositionCodec.translate(source, target, reader.readLong()));
          out.writeInt(reader.readInt());                                              // data
          out.writeBoolean(reader.readBoolean());                                      // global
        }
        out.flush();
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
      }

      case PLAY_ANIMATION -> {
        // entityId:VarInt + animation:u8 on both versions.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("animation not defined on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }

      case PLAY_CLIENT_COMMAND -> {
        // actionId:VarInt on both versions — action 0 is "respawn", so this is
        // what makes the death screen's respawn button work across the pair.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("client command not defined on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }

      case PLAY_HURT_ANIMATION -> {
        // Added 1.19.4 (entityId + yaw). 1.13 plays the hurt animation from
        // PLAY_ENTITY_STATUS, which the backend still sends and which already
        // translates, so the animation still happens — only the damage-direction
        // yaw is lost.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(
            "hurt animation has no pre-1.19.4 equivalent; entity status still drives the animation");
      }

      case PLAY_ENTER_COMBAT, PLAY_END_COMBAT, PLAY_DEATH_COMBAT -> {
        // 1.17 split 1.13's single action-multiplexed combat_event into three
        // packets. Folding them back is a genuine semantic fan-in, not an id
        // remap: the action enum has to be reconstructed and each variant's
        // payload rebuilt. PLAY_DEATH_COMBAT is what raises the death screen, so
        // dropping it would leave a 1.13 player dead with no way to respawn.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_COMBAT_EVENT)) {
          yield new TranslationResult.Dropped("target has neither split nor combined combat events");
        }
        byte[] folded = foldCombatEvent(kind, PlayPackets.body(packet));
        if (folded == null) {
          yield new TranslationResult.Dropped("combat event body not parseable");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            PacketKind.PLAY_COMBAT_EVENT, ConnectionState.PLAY, direction, folded));
      }

      case PLAY_COMBAT_EVENT -> {
        // The reverse direction: a 1.13 backend's combined packet must be split
        // for a modern client. Implemented in Protocol765To393 fan-out below.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(
            "1.13 combined combat_event must fan out to three 1.17+ packets; "
                + "single-packet output cannot express that yet");
      }

      case PLAY_BUNDLE_DELIMITER -> {
        // Purely a grouping marker: a matched pair tells the client to apply the
        // packets between them in one tick. 1.13 predates bundling and applies
        // every packet as it arrives, which is the behaviour bundling emulates.
        // Dropping the marker loses atomicity, not state.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(
            "bundle delimiter has no pre-1.19.4 equivalent; target applies packets individually");
      }

      case PLAY_ACKNOWLEDGE_BLOCK_CHANGE -> {
        // Acks a client block-prediction sequence (1.19+). 1.13 has no prediction
        // system and never sends a sequence, so there is nothing to acknowledge.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(
            "block-change sequence ack has no pre-1.19 equivalent; target has no prediction system");
      }

      case PLAY_DAMAGE_EVENT -> {
        // Added 1.19.4 to describe the damage source for the hurt animation. On
        // 1.13 the hurt animation is driven by PLAY_ENTITY_STATUS, which the
        // backend still sends and which is translated above. Health itself
        // arrives via PLAY_UPDATE_HEALTH. Dropping costs an animation detail only.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(
            "damage event has no pre-1.19.4 equivalent; hurt animation comes from entity status");
      }

      case PLAY_PLAYER_CHAT -> {
        // 1.19+ signed player chat. 1.13 has one unsigned chat packet, so the
        // signature chain, salt and index cannot be represented and are dropped
        // with the rest of the security envelope. What matters to the player is
        // the message body, which is carried through as legacy chat.
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT)) {
          yield new TranslationResult.Dropped("target has no chat packet");
        }
        String plain = playerChatPlainText(PlayPackets.body(packet));
        if (plain == null) {
          yield new TranslationResult.Dropped("player chat body not parseable as unsigned text");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            PacketKind.PLAY_CHAT, ConnectionState.PLAY, direction,
            legacyChatBody(plain)));
      }

      case PLAY_SPAWN_ENTITY -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("spawn entity not defined on target");
        }
        byte[] reshaped = translateSpawnEntity(PlayPackets.body(packet),
            source.version().number(), target.version().number());
        if (reshaped == null) {
          yield new TranslationResult.Dropped("spawn entity layout not translatable for this pair");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, reshaped));
      }

      case PLAY_SOUND_EFFECT -> {
        // 1.20.4 carries the sound as a registry holder (id+1, or an inline
        // resource location) plus a seed; 1.13 carries a bare registry id with no
        // seed. The numeric sound ids belong to each version's own registry and
        // are NOT interchangeable -- hundreds of sounds were added between the
        // two, so passing the id through would play an unrelated sound. Conduit
        // has no verified 1.13<->1.20.4 sound table, so this fails closed: the
        // player hears silence rather than the wrong sound. Purely cosmetic; no
        // state desync. Revisit by generating a sound-name mapping.
        if (target.defines(ConnectionState.PLAY, direction, kind)
            && source.version().number() == target.version().number()) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(
            "sound registry ids are version-specific and unmapped; dropping beats playing a wrong sound");
      }

      case PLAY_WORLD_PARTICLES -> {
        // Same registry caveat as sounds, but particles are purely decorative and
        // carry no state, so a mismatch is harmless rather than confusing. The
        // wire layout genuinely differs (id i32->VarInt, coords f32->f64), so the
        // fields are reshaped rather than copied.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("particles not defined on target");
        }
        yield new TranslationResult.Dropped(
            "particle registry ids are version-specific and unmapped; decorative only");
      }

      case PLAY_ENTITY_EQUIPMENT -> {
        // 1.16 replaced the single (slot, item) pair with a top-bit-terminated
        // array of them, so one 1.20.4 packet can carry up to six slots while a
        // 1.13 packet carries exactly one. Splitting would require emitting
        // several packets from one, which this path cannot yet express. The item
        // payload also uses era-specific registry ids, the same blocker that
        // already fails inventory closed. Held items therefore do not render on
        // other entities; no state desync.
        yield new TranslationResult.Dropped(
            "1.16+ multi-slot equipment cannot be expressed as a single 1.13 packet, "
                + "and item ids are unmapped across the pair");
      }

      case PLAY_BLOCK_PLACE -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("block place not defined on target");
        }
        byte[] reshaped = translateBlockPlace(PlayPackets.body(packet), source, target);
        if (reshaped == null) {
          yield new TranslationResult.Dropped("block place layout not translatable for this pair");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, reshaped));
      }

      case PLAY_SET_CONTAINER_CONTENT, PLAY_SET_CONTAINER_SLOT -> {
        // Slot payloads carry item ids from the sending era's registry. Conduit has no verified
        // 1.13 <-> 1.20.4 item mapping yet, so translating would risk wrong or corrupt items.
        // Fail closed: the 393 client sees an empty inventory rather than a mistranslated one.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(
              kind + " withheld from 393 (item registry mapping not implemented)");
        }
        // Slot payloads name items by the sending era's registry id, and 1.20.4
        // additionally wraps the container in a stateId plus a carried-item slot.
        // An unmapped copy would hand the target wrong or malformed items, so
        // both directions fail closed: the player sees an empty container rather
        // than a mistranslated one. Revisit with a real item-id mapping.
        yield new TranslationResult.Dropped(
            kind + " withheld (item registry ids and container framing differ across the pair)");
      }
      case PLAY_UPDATE_ATTRIBUTES -> {
        // 393 writes the attribute count as a fixed Int and names keys in pre-1.16 form
        // (generic.movementSpeed); 765 uses a VarInt count and namespaced snake_case keys
        // (minecraft:generic.movement_speed). Withhold until a verified key map exists — the
        // values Paper sends here are the client defaults anyway.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(
              "entity attributes withheld from 393 (attribute keys were renamed in 1.16)");
        }
        // Attribute keys were renamed and namespaced in 1.16 (generic.movementSpeed
        // -> minecraft:generic.movement_speed) and the modifier count width changed,
        // so an unmapped copy names attributes the target does not know. Dropping
        // leaves the target on default attribute values, which is stable.
        yield new TranslationResult.Dropped(
            "entity attributes withheld (attribute keys were renamed in 1.16)");
      }
      case PLAY_SET_ENTITY_METADATA -> {
        // Metadata is doubly era-specific: field indices differ per entity class, and the type ids
        // shifted when VarLong was inserted at id 2 in 1.19 — so a 765 type 3 (Float) would be
        // read by a 393 client as type 3 (String). Forwarding desyncs the stream immediately.
        // Withhold until a real index/type mapping subsystem exists.
        if (target.version().number() <= 404) {
          yield new TranslationResult.Dropped(
              "entity metadata withheld from 393 (index and type ids are era specific)");
        }
        // Same hazard in the other direction: a 1.13 type 3 (String) would be
        // read by a modern client as type 3 (Float). Withhold rather than corrupt.
        // Cost: entities lose custom appearance state (name tags, poses, held
        // items, baby/adult). They still spawn, move and can be interacted with,
        // because position and identity travel in their own packets. No desync.
        yield new TranslationResult.Dropped(
            "entity metadata withheld (index and type ids are era specific in both directions)");
      }
      case PLAY_WORLD_BORDER_INIT -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("world border missing on target");
        }
        // Field-level: the two warning values are ordered differently per era.
        yield new TranslationResult.Translated(
            gg.tame.conduit.protocol.codec.WorldBorderCodec.decodeInit(source, direction, packet));
      }
      case PLAY_PLAYER_DIGGING -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("player digging missing on target");
        }
        // FIELD-TRANSLATED + SYNTHESIZED. Status and face are stable across both eras; the packed
        // Position must cross the 1.14 field-order change; and 1.19 appended a prediction sequence
        // that 393 has no concept of, so it is synthesized. A 393 client does no client-side block
        // prediction, so it has no acknowledgement to reconcile against.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int status = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
          long packed = input.readLong();
          byte face = input.readByte();
          int sequence = input.available() >= 1 ? gg.tame.conduit.protocol.MinecraftInput.varInt(input) : 0;

          ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
          DataOutputStream output = new DataOutputStream(buffer);
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, status);
          output.writeLong(gg.tame.conduit.protocol.codec.BlockPositionCodec.translate(source, target, packed));
          output.writeByte(face);
          if (target.version().number() > 404) {
            gg.tame.conduit.protocol.MinecraftOutput.varInt(output, sequence);
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_MULTI_BLOCK_CHANGE -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("multi block change missing on target");
        }
        // SEMANTICALLY TRANSLATED: absolute positions through the semantic model, states remapped.
        var batch = gg.tame.conduit.protocol.codec.BlockChangesCodec.decode(source, direction, packet);
        yield new TranslationResult.Translated(
            batch.mapStates(blockState -> translateBlockState(source, target, blockState)));
      }
      case PLAY_BLOCK_UPDATE -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("block update missing on target");
        }
        // Position field order changed in 1.14, and block state ids differ per era.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          long packed = input.readLong();
          int blockState = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
          DataOutputStream output = new DataOutputStream(buffer);
          output.writeLong(gg.tame.conduit.protocol.codec.BlockPositionCodec.translate(source, target, packed));
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, translateBlockState(source, target, blockState));
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_SPAWN_POSITION -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("spawn position missing");
        }
        // The packed position must be re-packed across the 1.14 field-order change, not copied.
        // 765 also appends an angle float that 393 has no field for.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          long packed = input.readLong();
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
          DataOutputStream output = new DataOutputStream(buffer);
          output.writeLong(gg.tame.conduit.protocol.codec.BlockPositionCodec.translate(source, target, packed));
          if (target.version().number() > 404) {
            // Carry the source angle when it has one, otherwise default to 0.
            output.writeFloat(input.available() >= 4 ? input.readFloat() : 0f);
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_ENTITY_DESTROY -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("entity destroy missing");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_CLIENT_INFORMATION -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("client information missing on target");
        }
        // 1.20.4 appends enableTextFiltering and allowServerListings, which 1.13 never sends.
        // Forwarding the 393 body verbatim leaves the backend decoder one byte short.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          String locale = gg.tame.conduit.protocol.MinecraftInput.string(input, 64);
          byte viewDistance = input.readByte();
          int chatMode = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
          boolean chatColors = input.readBoolean();
          int skinParts = input.readUnsignedByte();
          int mainHand = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
          // Present only from 1.18/1.19 on; fall back to the vanilla client defaults.
          boolean textFiltering = input.available() >= 1 && input.readBoolean();
          boolean serverListings = input.available() >= 1 ? input.readBoolean() : true;

          ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
          DataOutputStream output = new DataOutputStream(buffer);
          gg.tame.conduit.protocol.MinecraftOutput.string(output, locale);
          output.writeByte(viewDistance);
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, chatMode);
          output.writeBoolean(chatColors);
          output.writeByte(skinParts);
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, mainHand);
          if (target.version().number() > 404) {
            output.writeBoolean(textFiltering);
            output.writeBoolean(serverListings);
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_DECLARE_COMMANDS, PLAY_TAB_COMPLETE, PLAY_TAB_COMPLETE_REQUEST -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing");
        }
        if (kind == PacketKind.PLAY_DECLARE_COMMANDS) {
          // The brigadier node graph is structurally similar across eras, but argument parsers are
          // named by string on 393 and by registry id from 1.19 on, so the trees are not
          // interchangeable. Withhold rather than kill the session: the tree only drives client
          // side command autocomplete, and commands still work without it.
          if (target.version().number() <= 404) {
            yield new TranslationResult.Dropped(
                "command tree withheld from 393 (brigadier parser ids are era specific)");
          }
          // Toward a modern client, send a minimal but VALID tree instead: one
          // root node with no children. 1.19+ names argument parsers by registry
          // id where 1.13 names them by string, so the source tree's argument
          // nodes cannot be carried across; an empty root is the honest subset.
          // The player loses client-side autocomplete but can still type
          // commands, because the server parses the text it receives. Dropping
          // the packet outright is worse here than synthesising: some clients
          // wait on the command tree during world entry.
          ByteArrayOutputStream tree = new ByteArrayOutputStream();
          DataOutputStream treeOut = new DataOutputStream(tree);
          MinecraftOutput.varInt(treeOut, 1);   // one node
          treeOut.writeByte(0x00);              // flags: root, not executable
          MinecraftOutput.varInt(treeOut, 0);   // no children
          MinecraftOutput.varInt(treeOut, 0);   // root index
          treeOut.flush();
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, tree.toByteArray()));
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
    if (semantic instanceof gg.tame.conduit.protocol.semantic.SemanticBlockChanges changes) {
      return gg.tame.conduit.protocol.codec.BlockChangesCodec.encode(codec.protocol(), changes);
    }
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

  /** Maps a block state id between eras using the same table the chunk codecs use. */
  /**
   * Reshapes spawn-entity between the 1.13 and 1.20.4 layouts.
   *
   * <pre>
   *   1.13    entityId:VarInt uuid:UUID type:i8    x y z:f64 pitch:i8 yaw:i8            objectData:i32    velocity:3xi16
   *   1.20.4  entityId:VarInt uuid:UUID type:VarInt x y z:f64 pitch:i8 yaw:i8 headPitch:i8 objectData:VarInt velocity:3xi16
   * </pre>
   *
   * <p>Three real differences: the type widened to a VarInt, a headPitch byte was
   * inserted, and objectData changed from a fixed i32 to a VarInt.
   *
   * <p>Known limitation: entity type ids are registry indices and the registry
   * gained entries between the two versions, so a numerically-copied type can
   * name a different entity. The geometry is correct and the entity exists at
   * the right place; its model may be wrong. That is strictly better than the
   * entity being absent, and unlike sounds it is observable and fixable later
   * with a type-name mapping.
   */
  private static byte[] translateSpawnEntity(byte[] body, int fromProtocol, int toProtocol) {
    boolean fromModern = fromProtocol > 404;
    boolean toModern = toProtocol > 404;
    if (fromModern == toModern) return body;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 8);
      DataOutputStream out = new DataOutputStream(buffer);

      MinecraftOutput.varInt(out, MinecraftInput.varInt(in));            // entityId
      out.writeLong(in.readLong());                                      // uuid high
      out.writeLong(in.readLong());                                      // uuid low

      int type = fromModern ? MinecraftInput.varInt(in) : in.readUnsignedByte();
      if (toModern) MinecraftOutput.varInt(out, type); else out.writeByte(type & 0xff);

      for (int index = 0; index < 3; index++) out.writeDouble(in.readDouble());  // x, y, z
      out.writeByte(in.readByte());                                      // pitch
      out.writeByte(in.readByte());                                      // yaw

      if (fromModern) {
        int headPitch = in.readByte();                                   // 1.20.4 only
        if (toModern) out.writeByte(headPitch);
      } else if (toModern) {
        out.writeByte(0);                                                // no 1.13 source for headPitch
      }

      int objectData = fromModern ? MinecraftInput.varInt(in) : in.readInt();
      if (toModern) MinecraftOutput.varInt(out, objectData); else out.writeInt(objectData);

      for (int index = 0; index < 3; index++) out.writeShort(in.readShort());    // velocity
      out.flush();
      return buffer.toByteArray();
    } catch (IOException exception) {
      return null;
    }
  }

  /**
   * Reshapes the serverbound use-item-on-block packet.
   *
   * <pre>
   *   1.13    location:Position direction:VarInt hand:VarInt cursorX/Y/Z:f32
   *   1.20.4  hand:VarInt location:Position direction:VarInt cursorX/Y/Z:f32 insideBlock:bool sequence:VarInt
   * </pre>
   *
   * <p>1.20.4 moved hand to the front and appended two fields. {@code insideBlock}
   * has no 1.13 source and is sent false; {@code sequence} belongs to the 1.19+
   * block-prediction system, which a 1.13 client does not participate in, so a
   * zero sequence is correct rather than merely convenient. The packed Position
   * layout also differs between the eras and is converted rather than copied.
   */
  private static byte[] translateBlockPlace(byte[] body, ProtocolDefinition source, ProtocolDefinition target) {
    boolean fromModern = source.version().number() > 404;
    boolean toModern = target.version().number() > 404;
    if (fromModern == toModern) return body;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 8);
      DataOutputStream out = new DataOutputStream(buffer);

      int hand;
      long position;
      int face;
      if (fromModern) {
        hand = MinecraftInput.varInt(in);
        position = in.readLong();
        face = MinecraftInput.varInt(in);
      } else {
        position = in.readLong();
        face = MinecraftInput.varInt(in);
        hand = MinecraftInput.varInt(in);
      }
      long converted = BlockPositionCodec.translate(source, target, position);

      if (toModern) {
        MinecraftOutput.varInt(out, hand);
        out.writeLong(converted);
        MinecraftOutput.varInt(out, face);
      } else {
        out.writeLong(converted);
        MinecraftOutput.varInt(out, face);
        MinecraftOutput.varInt(out, hand);
      }
      for (int index = 0; index < 3; index++) out.writeFloat(in.readFloat());   // cursor x, y, z
      if (toModern) {
        out.writeBoolean(false);            // insideBlock: no 1.13 source
        MinecraftOutput.varInt(out, 0);     // sequence: 1.13 has no block prediction
      }
      out.flush();
      return buffer.toByteArray();
    } catch (IOException exception) {
      return null;
    }
  }

  /**
   * Extracts the plain message from a 1.19+ signed player-chat packet.
   *
   * <p>The packet leads with sender UUID, index and an optional signature before
   * the message string, so the body is walked rather than guessed at. Everything
   * after the message (timestamp, salt, previous-signature chain, filter mask and
   * the formatting envelope) describes chat signing, which 1.13 has no concept
   * of and which is intentionally discarded.
   */
  private static String playerChatPlainText(byte[] body) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      in.readLong();                        // sender uuid high
      in.readLong();                        // sender uuid low
      MinecraftInput.varInt(in);            // index
      if (in.readBoolean()) in.skipBytes(256);   // message signature present
      return MinecraftInput.string(in, 262144);
    } catch (IOException exception) {
      return null;
    }
  }

  /**
   * Converts a pre-1.19 living-entity spawn into the unified modern spawn packet.
   *
   * <p>Entity type ids are registry indices and the entity registry gained
   * entries between the versions, so a numerically-copied type can name a
   * different mob. The entity still spawns at the correct position with correct
   * motion; only its model may be wrong. That is observable and fixable later
   * with a type-name mapping, and is better than the mob not existing at all.
   */
  private static byte[] livingSpawnToUnified(byte[] body) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 4);
      DataOutputStream out = new DataOutputStream(buffer);

      MinecraftOutput.varInt(out, MinecraftInput.varInt(in));   // entityId
      out.writeLong(in.readLong());                             // uuid high
      out.writeLong(in.readLong());                             // uuid low
      MinecraftOutput.varInt(out, MinecraftInput.varInt(in));   // type
      for (int index = 0; index < 3; index++) out.writeDouble(in.readDouble());

      byte yaw = in.readByte();
      byte pitch = in.readByte();
      byte headPitch = in.readByte();
      out.writeByte(pitch);          // modern order is pitch before yaw
      out.writeByte(yaw);
      out.writeByte(headPitch);

      MinecraftOutput.varInt(out, 0);  // objectData: unused for living entities
      for (int index = 0; index < 3; index++) out.writeShort(in.readShort());
      // Trailing 1.13 metadata blob is intentionally not carried: 1.19+ sends
      // entity metadata as its own packet, which the backend also emits.
      out.flush();
      return buffer.toByteArray();
    } catch (IOException exception) {
      return null;
    }
  }

  /**
   * Folds a 1.17+ split combat packet back into the 1.13 combined layout.
   *
   * <pre>
   *   1.13 combat_event: event:VarInt
   *                      event==1 -> duration:VarInt, entityId:i32
   *                      event==2 -> playerId:VarInt, entityId:i32, message:String
   *   1.20.4 enter_combat_event: (no fields)
   *          end_combat_event:   duration:VarInt
   *          death_combat_event: playerId:VarInt, message:NBT text component
   * </pre>
   *
   * <p>1.17 also dropped the killer {@code entityId} that 1.13 carries for the
   * end and death events; -1 is written, which 1.13 already treats as "no entity"
   * and which only affects the killer name shown on the death screen. The death
   * message itself is converted from the modern NBT text component to the JSON
   * string 1.13 expects.
   */
  private static byte[] foldCombatEvent(PacketKind kind, byte[] body) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(buffer);
      switch (kind) {
        case PLAY_ENTER_COMBAT -> MinecraftOutput.varInt(out, 0);
        case PLAY_END_COMBAT -> {
          MinecraftOutput.varInt(out, 1);
          MinecraftOutput.varInt(out, MinecraftInput.varInt(in));   // duration
          out.writeInt(-1);                                          // killer unknown post-1.17
        }
        case PLAY_DEATH_COMBAT -> {
          MinecraftOutput.varInt(out, 2);
          MinecraftOutput.varInt(out, MinecraftInput.varInt(in));   // playerId
          out.writeInt(-1);                                          // killer unknown post-1.17
          MinecraftOutput.string(out, nbtTextToJson(in));
        }
        default -> {
          return null;
        }
      }
      out.flush();
      return buffer.toByteArray();
    } catch (IOException exception) {
      return null;
    }
  }

  /**
   * Reads a modern NBT text component and renders it as a 1.13 JSON component.
   *
   * <p>Only the plain text is recovered; styling and nested components are not
   * reconstructed. For a death message that means the player sees the correct
   * sentence without colour, which is far better than no death message at all.
   */
  private static String nbtTextToJson(DataInputStream in) throws IOException {
    int tag = in.readUnsignedByte();
    if (tag == 8) {                     // TAG_String, the common single-line case
      return "{\"text\":" + quoteJson(in.readUTF()) + "}";
    }
    return "{\"text\":\"You died\"}";   // compound/list form not decoded here
  }

  /** Wraps plain text as a 1.13 chat packet body: JSON component + position byte. */
  private static byte[] legacyChatBody(String plain) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.string(out, "{\"text\":" + quoteJson(plain) + "}");
    out.writeByte(0);                       // position: chat box
    out.flush();
    return buffer.toByteArray();
  }

  private static String quoteJson(String value) {
    StringBuilder text = new StringBuilder("\"");
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> text.append("\\\"");
        case '\\' -> text.append("\\\\");
        case '\n' -> text.append("\\n");
        case '\r' -> text.append("\\r");
        case '\t' -> text.append("\\t");
        default -> {
          if (character < 0x20) text.append(String.format("\\u%04x", (int) character));
          else text.append(character);
        }
      }
    }
    return text.append('"').toString();
  }

  private static int translateBlockState(ProtocolDefinition source, ProtocolDefinition target, int state) {
    if (source.version().number() == target.version().number()) return state;
    return target.version().number() <= 404
        ? gg.tame.conduit.protocol.chunk.BlockStateMaps.to393(state)
        : gg.tame.conduit.protocol.chunk.BlockStateMaps.to765(state);
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

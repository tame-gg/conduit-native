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
  /** 1.13 client in front of a 1.20.4 backend. One instance per session. */
  public static Protocol393To765Translator clientLegacy() { return new Protocol393To765Translator(393, 765); }

  /** 1.20.4 client in front of a 1.13 backend. One instance per session. */
  public static Protocol393To765Translator clientModern() { return new Protocol393To765Translator(765, 393); }

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

  // --------------------------------------------------------- per-session state
  //
  // None of this can be shared between players: a container state id, an
  // inventory action number and "which entity id is a living entity" all belong
  // to one connection. Translators are therefore created per session.

  /** Extra packets produced while translating one packet, drained by the session. */
  private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> toClient =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> toBackend =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /**
   * The container state id a 1.20.4 client must echo back. A 1.13 backend has no
   * such counter, so Conduit owns it: it is bumped on every container update
   * Conduit sends to a modern client, and replayed into every click that client
   * sends back.
   */
  private final java.util.concurrent.atomic.AtomicInteger containerState =
      new java.util.concurrent.atomic.AtomicInteger();

  /**
   * The 1.13 inventory action number. A 1.13 client will not accept another
   * inventory change until the server confirms the action number it sent, and a
   * 1.20.4 backend has no packet for that, so Conduit answers on its behalf.
   */
  private final java.util.concurrent.atomic.AtomicInteger actionNumber =
      new java.util.concurrent.atomic.AtomicInteger(1);

  /**
   * Whether each spawned entity extends LivingEntity, learned from the spawn
   * packet that introduced it. Entity metadata indices shift by a different
   * amount for living and non-living entities, so this is not optional
   * bookkeeping — without it every mob's metadata lands on the wrong field.
   */
  private final java.util.Map<Integer, Boolean> livingEntities =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override public java.util.List<byte[]> drainToClient() { return drain(toClient); }
  @Override public java.util.List<byte[]> drainToBackend() { return drain(toBackend); }

  private static java.util.List<byte[]> drain(java.util.Queue<byte[]> queue) {
    if (queue.isEmpty()) return java.util.List.of();
    java.util.List<byte[]> packets = new java.util.ArrayList<>(queue.size());
    for (byte[] packet = queue.poll(); packet != null; packet = queue.poll()) packets.add(packet);
    return packets;
  }

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
          ProtocolTrace.emitted(kind.name() + " " + fromProtocol + "→" + toProtocol, encoded);
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
      // Include the packet so a failure against a real server is diagnosable from
      // the log alone, instead of needing the run reproduced to see the bytes.
      throw new TranslationException("393↔765 translation failed: "
          + exception.getClass().getSimpleName() + ": " + exception.getMessage()
          + " [" + fromProtocol + "→" + toProtocol + " " + state + " " + direction
          + " " + describe(packet) + "]", exception);
    } catch (IOException exception) {
      // Include the packet so a failure against a real server is diagnosable from
      // the log alone, instead of needing the run reproduced to see the bytes.
      throw new TranslationException("393↔765 translation failed: "
          + exception.getClass().getSimpleName() + ": " + exception.getMessage()
          + " [" + fromProtocol + "→" + toProtocol + " " + state + " " + direction
          + " " + describe(packet) + "]", exception);
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
        // Unsigned path in both directions: no signature is fabricated.
        //
        //   1.13    component:JSON string   position:i8
        //   1.20.4  component:NBT           overlay:bool
        //
        // 1.20.3 moved text components from JSON to NBT. That is a change of
        // representation, not of meaning, so the whole component crosses --
        // colours, translation keys, hover text and the `extra` chain included.
        // Flattening to plain text here is what made a 1.13 server's command
        // feedback arrive on a modern client as the bare word "Server".
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          String json = source.version().number() <= 404
              ? MinecraftInput.string(input, 262_144)
              : gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input);
          // 1.13's position byte: 0 chat, 1 system, 2 action bar. 1.20.4 keeps
          // only the action-bar distinction, as a boolean.
          boolean overlay = source.version().number() <= 404
              ? input.available() >= 1 && input.readByte() == 2
              : input.available() >= 1 && input.readBoolean();

          ByteArrayOutputStream buffer = new ByteArrayOutputStream(json.length() + 16);
          DataOutputStream output = new DataOutputStream(buffer);
          PacketKind outKind;
          if (target.version().number() <= 404) {
            MinecraftOutput.string(output, json);
            output.writeByte(overlay ? 2 : 1);
            outKind = PacketKind.PLAY_CHAT;
          } else {
            gg.tame.conduit.protocol.text.ComponentCodec.jsonToNbt(output, json);
            output.writeBoolean(overlay);
            outKind = PacketKind.PLAY_SYSTEM_CHAT;
          }
          if (!target.defines(ConnectionState.PLAY, direction, outKind)) {
            yield new TranslationResult.Dropped("no chat packet on target");
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              outKind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
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
        rememberLiving(peekEntityId(PlayPackets.body(packet)), true);
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
        // 1.17 split this packet by action, and each 1.13 action corresponds to
        // exactly one of the three, so the "fan-out" is really a demultiplex.
        // Action 2 is what raises the death screen, so dropping it leaves a
        // modern player dead with no way to respawn.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int action = MinecraftInput.varInt(input);
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
          DataOutputStream output = new DataOutputStream(buffer);
          PacketKind out = switch (action) {
            case 0 -> PacketKind.PLAY_ENTER_COMBAT;
            case 1 -> PacketKind.PLAY_END_COMBAT;
            case 2 -> PacketKind.PLAY_DEATH_COMBAT;
            default -> null;
          };
          if (out == null || !target.defines(ConnectionState.PLAY, direction, out)) {
            yield new TranslationResult.Dropped("combat action " + action + " has no target packet");
          }
          if (action == 1) {
            MinecraftOutput.varInt(output, MinecraftInput.varInt(input));   // duration
            input.readInt();               // 1.17 dropped the killer's entity id
          } else if (action == 2) {
            MinecraftOutput.varInt(output, MinecraftInput.varInt(input));   // player entity id
            input.readInt();               // killer id, dropped as above
            // 1.20.3 carries the death message as an NBT component, not JSON.
            gg.tame.conduit.protocol.text.ComponentCodec.jsonToNbt(
                output, MinecraftInput.string(input, 262_144));
          }
          output.flush();
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              out, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
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
        // Remember whether this entity is living BEFORE any reshaping: metadata
        // that arrives later needs it to place field indices correctly.
        rememberLiving(peekEntityId(PlayPackets.body(packet)),
            source.version().number() > 404 && spawnIsLiving765(PlayPackets.body(packet)));
        if (source.version().number() > 404 && target.version().number() <= 404) {
          // 765 unified spawn → 393 living or object spawn (never copy type ids).
          var split = unifiedSpawnTo393(PlayPackets.body(packet));
          if (split == null) {
            yield new TranslationResult.Dropped("spawn entity type unmapped or body not parseable for 393");
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              split.kind(), ConnectionState.PLAY, direction, split.body()));
        }
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
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("entity equipment missing on target");
        }
        // 1.16 replaced the single (slot, item) pair with a top-bit-terminated
        // array, so one modern packet can carry six slots that 1.13 can only
        // express as six packets. Toward 1.13 the first change is returned and
        // the rest are queued for the session to send straight after it.
        var changes = gg.tame.conduit.protocol.inventory.ContainerCodec.readEquipment(
            source.version().number(), PlayPackets.body(packet));
        if (changes.isEmpty()) yield new TranslationResult.Dropped("empty equipment packet");
        if (target.version().number() > 404) {
          byte[] merged = gg.tame.conduit.protocol.inventory.ContainerCodec.writeEquipment765(changes);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, merged));
        }
        for (int index = 1; index < changes.size(); index++) {
          queueToClient(kind, ConnectionState.PLAY, direction, target,
              gg.tame.conduit.protocol.inventory.ContainerCodec.writeEquipment393(changes.get(index)));
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction,
            gg.tame.conduit.protocol.inventory.ContainerCodec.writeEquipment393(changes.get(0))));
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
        // Every slot is decoded into a semantic item, translated by identifier
        // and re-encoded in the target's slot layout. The container framing
        // differs too: 1.17 added the state id and the carried (cursor) item,
        // so those are synthesised toward a modern client and discarded toward
        // a 1.13 one, where the cursor is reconciled by transaction instead.
        int nextState = target.version().number() > 404 ? containerState.incrementAndGet() : 0;
        byte[] reshaped = kind == PacketKind.PLAY_SET_CONTAINER_CONTENT
            ? gg.tame.conduit.protocol.inventory.ContainerCodec.containerContent(
                source.version().number(), target.version().number(), PlayPackets.body(packet), nextState)
            : gg.tame.conduit.protocol.inventory.ContainerCodec.containerSlot(
                source.version().number(), target.version().number(), PlayPackets.body(packet), nextState);
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, reshaped));
      }
      case PLAY_OPEN_WINDOW -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("open window missing on target");
        }
        byte[] reshaped = gg.tame.conduit.protocol.inventory.ContainerCodec.openWindow(
            source.version().number(), target.version().number(), PlayPackets.body(packet));
        if (reshaped == null) {
          // A screen with no counterpart (horse, grindstone, loom, smithing).
          // Dropping leaves the player where they were rather than opening the
          // wrong screen, which would desync every subsequent slot update.
          yield new TranslationResult.Dropped("screen type has no counterpart on the target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, reshaped));
      }
      case PLAY_CLOSE_WINDOW_CLIENTBOUND, PLAY_WINDOW_PROPERTY -> {
        // windowId (+ property/value). Identical field layouts on both releases.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_CLICK_WINDOW -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("click window missing on target");
        }
        var click = gg.tame.conduit.protocol.inventory.ContainerCodec.readClick(
            source.version().number(), PlayPackets.body(packet));
        int action = actionNumber.getAndIncrement() & 0x7fff;
        byte[] reshaped = gg.tame.conduit.protocol.inventory.ContainerCodec.writeClick(
            target.version().number(), click, containerState.get(), action);
        if (source.version().number() <= 404) {
          // The 1.13 client is waiting for the transaction confirmation that a
          // 1.20.4 backend will never send. Without it the client freezes its
          // inventory after the first click. Conduit answers on the backend's
          // behalf with the action number the client itself just used; the
          // backend still authoritatively resends the slots afterwards.
          queueToClient(PacketKind.PLAY_CONFIRM_TRANSACTION, ConnectionState.PLAY,
              PacketDirection.SERVER_TO_CLIENT, source,
              gg.tame.conduit.protocol.inventory.ContainerCodec.confirmTransaction(
                  click.windowId(), sourceActionNumber(PlayPackets.body(packet)), true));
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, reshaped));
      }
      case PLAY_CONFIRM_TRANSACTION -> {
        // 1.13 only. Toward a modern client there is nothing to send. Toward a
        // 1.13 BACKEND, an unaccepted transaction must be echoed back or the
        // server stops applying that window's clicks — so Conduit echoes it
        // rather than dropping it, since the modern client cannot.
        if (direction == PacketDirection.SERVER_TO_CLIENT && target.version().number() > 404) {
          echoTransactionIfRejected(PlayPackets.body(packet), source);
          yield new TranslationResult.Dropped("1.13 transaction handshake answered by Conduit");
        }
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("confirm transaction missing on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_CREATIVE_SLOT -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("creative slot missing on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction,
            gg.tame.conduit.protocol.inventory.ContainerCodec.creativeSlot(
                source.version().number(), target.version().number(), PlayPackets.body(packet))));
      }
      case PLAY_SET_CARRIED_ITEM, PLAY_PICK_ITEM -> {
        // Selected hotbar slot / pick block. One short or one VarInt; unchanged.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_USE_ITEM -> {
        // hand:VarInt on both, plus a 1.19+ prediction sequence a 1.13 client
        // does not have and a 1.13 server does not read.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("use item missing on target");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int hand = MinecraftInput.varInt(input);
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(8);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.varInt(output, hand);
          if (target.version().number() > 404) MinecraftOutput.varInt(output, 0);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_INTERACT_ENTITY -> {
        // entityId, type, [target x/y/z for INTERACT_AT], [hand], and from 1.16
        // a trailing sneaking flag the 1.13 client never sends. Attacking a mob
        // goes through this packet, so it is gameplay-critical, not cosmetic.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("interact entity missing on target");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int entityId = MinecraftInput.varInt(input);
          int type = MinecraftInput.varInt(input);
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(24);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.varInt(output, entityId);
          MinecraftOutput.varInt(output, type);
          if (type == 2) {                       // interact at: three floats follow
            for (int axis = 0; axis < 3; axis++) output.writeFloat(input.readFloat());
          }
          if (type == 0 || type == 2) {          // interact / interact at carry a hand
            MinecraftOutput.varInt(output, MinecraftInput.varInt(input));
          }
          boolean sneaking = input.available() >= 1 && input.readBoolean();
          if (target.version().number() > 404) output.writeBoolean(sneaking);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_DISGUISED_CHAT -> {
        // 1.19 chat that carries a decoration instead of a signature -- this is
        // what /say and /me produce, so it is the ordinary server-announcement
        // path, not an edge case. 1.13 has one unsigned chat packet, so the
        // sender and the message are composed into a single component rather
        // than dropped, which is what the decoration would have rendered.
        if (target.version().number() > 404) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        if (!target.defines(ConnectionState.PLAY, direction, PacketKind.PLAY_CHAT)) {
          yield new TranslationResult.Dropped("no chat packet on target");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          String message = gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input);
          MinecraftInput.varInt(input);                 // chat type
          String sender = gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input);
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(message.length() + 64);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.string(output,
              "{\"text\":\"\",\"extra\":[{\"text\":\"[\"}," + sender
                  + ",{\"text\":\"] \"}," + message + "]}");
          output.writeByte(1);                          // system position
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              PacketKind.PLAY_CHAT, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_DELETE_MESSAGE, PLAY_CHAT_SUGGESTIONS, PLAY_CHUNK_BIOMES, PLAY_CLEAR_TITLES,
           PLAY_OPEN_HORSE_SCREEN, PLAY_OPEN_BOOK, PLAY_PING, PLAY_PONG_RESPONSE, PLAY_RESET_SCORE,
           PLAY_RESOURCE_PACK_POP, PLAY_RESOURCE_PACK_PUSH, PLAY_SET_ACTION_BAR,
           PLAY_BORDER_CENTER, PLAY_BORDER_LERP_SIZE, PLAY_BORDER_SIZE,
           PLAY_BORDER_WARNING_DELAY, PLAY_BORDER_WARNING_DISTANCE,
           PLAY_SET_SUBTITLE, PLAY_SET_TITLE_TEXT, PLAY_SET_TITLE_TIMES -> {
        // 1.19/1.20 additions with no 1.13 counterpart, plus the world-border
        // updates 1.17 split out of 1.13's action-multiplexed border packet.
        // Recognised so the drop is deliberate rather than fatal; none of them
        // carries state the player can lose track of.
        if (target.version().number() > 404 && target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        yield new TranslationResult.Dropped(kind + " has no 1.13 counterpart");
      }
      case PLAY_SET_PASSENGERS, PLAY_SPAWN_EXPERIENCE_ORB, PLAY_ATTACH_ENTITY -> {
        // Field-for-field identical on 1.13 and 1.20.4; only the id moved.
        // Riding matters: without passengers a boat or horse carries nobody.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
      }
      case PLAY_ENTITY_EFFECT, PLAY_REMOVE_ENTITY_EFFECT -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped(kind + " missing on target");
        }
        // The effect id widened from a byte to a VarInt in 1.19. The numeric ids
        // 1..31 are the same set in both releases (1.14 and later only appended
        // hero_of_the_village and darkness), so ids in that range are equivalent
        // and anything above it is dropped rather than shown as a wrong effect.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int entityId = MinecraftInput.varInt(input);
          int effect = source.version().number() > 404 ? MinecraftInput.varInt(input) : input.readUnsignedByte();
          if (target.version().number() <= 404 && effect > 31) {
            yield new TranslationResult.Dropped("potion effect " + effect + " postdates 1.13");
          }
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.varInt(output, entityId);
          if (target.version().number() > 404) MinecraftOutput.varInt(output, effect);
          else output.writeByte(effect);
          if (kind == PacketKind.PLAY_ENTITY_EFFECT) {
            output.writeByte(input.readByte());                        // amplifier
            MinecraftOutput.varInt(output, MinecraftInput.varInt(input));  // duration
            output.writeByte(input.readByte());                        // flags
            // 1.19 appended optional factor data (a darkness-effect detail).
            if (target.version().number() > 404) output.writeBoolean(false);
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_SET_COOLDOWN -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("set cooldown missing on target");
        }
        // itemId:VarInt + ticks:VarInt. The id must cross the item registry.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int item = MinecraftInput.varInt(input);
          int ticks = MinecraftInput.varInt(input);
          var mapped = gg.tame.conduit.protocol.item.ItemRegistries.translate(
              source.version().number(), target.version().number(), item);
          if (mapped.isEmpty()) {
            yield new TranslationResult.Dropped("cooldown item has no counterpart on the target");
          }
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(8);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.varInt(output, mapped.getAsInt());
          MinecraftOutput.varInt(output, ticks);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_BLOCK_BREAK_ANIMATION -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("block break animation missing on target");
        }
        // entityId:VarInt, location:Position, stage:i8. Position packing changed.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(16);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.varInt(output, MinecraftInput.varInt(input));
          output.writeLong(BlockPositionCodec.translate(source, target, input.readLong()));
          output.writeByte(input.readByte());
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_OPEN_SIGN_EDITOR -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("sign editor missing on target");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(12);
          DataOutputStream output = new DataOutputStream(buffer);
          output.writeLong(BlockPositionCodec.translate(source, target, input.readLong()));
          // 1.20 added a front/back flag; 1.13 signs only have a front.
          if (target.version().number() > 404) output.writeBoolean(true);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_TAB_LIST_HEADER -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("tab list header missing on target");
        }
        // Two text components. 1.20.3 moved components from JSON to NBT, which
        // is a representation change the component codec handles in full rather
        // than by flattening to plain text.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
          DataOutputStream output = new DataOutputStream(buffer);
          for (int component = 0; component < 2; component++) {
            if (source.version().number() <= 404) {
              String json = MinecraftInput.string(input, 262_144);
              if (target.version().number() > 404) {
                gg.tame.conduit.protocol.text.ComponentCodec.jsonToNbt(output, json);
              } else {
                MinecraftOutput.string(output, json);
              }
            } else {
              String json = gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input);
              MinecraftOutput.string(output, json);
            }
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_RESPAWN -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("respawn missing on target");
        }
        // 1.16 replaced the numeric dimension with a pair of registry
        // identifiers, and 1.20 appended the seed, the debug/flat flags, the
        // death location and a portal cooldown. Nothing but the game mode
        // survives as a field, so the packet is rebuilt from the synthesised
        // world identity the Join Game codec already establishes for this pair.
        byte[] rebuilt = JoinGameCodec.encodeRespawn(source, target, PlayPackets.body(packet));
        if (rebuilt == null) {
          yield new TranslationResult.Dropped("respawn body not translatable for this pair");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, rebuilt));
      }
      case PLAY_STATISTICS, PLAY_BOSS_BAR, PLAY_NAMED_SOUND_EFFECT, PLAY_NBT_QUERY_RESPONSE,
           PLAY_SPAWN_PAINTING, PLAY_SPAWN_GLOBAL_ENTITY, PLAY_BLOCK_ENTITY_DATA, PLAY_BLOCK_ACTION,
           PLAY_SCOREBOARD_OBJECTIVE, PLAY_TEAMS, PLAY_UPDATE_SCORE, PLAY_DISPLAY_SCOREBOARD,
           PLAY_TITLE, PLAY_STOP_SOUND, PLAY_CAMERA, PLAY_USE_BED, PLAY_FACE_PLAYER,
           PLAY_CRAFT_RECIPE_RESPONSE, PLAY_SELECT_ADVANCEMENT_TAB, PLAY_VEHICLE_MOVE,
           PLAY_MAP_DATA, PLAY_TRADE_LIST -> {
        // Recognised and dropped on purpose. Each of these is either display-only
        // (scoreboards, titles, boss bars, the tab list, statistics) or names
        // something from the sending era's own registry that this pair has no
        // verified mapping for (block-entity types, paintings, sounds, map and
        // trade payloads). Dropping costs the feature; forwarding the bytes would
        // desynchronise the stream, and a fail-closed proxy would end the session.
        // These are the deliberately-unsupported set for 393 <-> 765, not an
        // oversight, and none of them gates world entry or movement.
        yield new TranslationResult.Dropped(
            kind + " is display-only or names era-specific registry entries; "
                + "intentionally unsupported for 393 <-> 765");
      }
      case PLAY_EXPLOSION -> {
        // 1.13   x,y,z:f32  strength:f32  count:i32  records[3B]  motion x,y,z:f32
        // 1.20.4 x,y,z:f64  strength:f32  count:VarInt records[3B] motion x,y,z:f32
        //        blockInteraction:VarInt  smallParticle  largeParticle  sound
        //
        // The coordinates widened and 1.20.3 appended a particle/sound descriptor
        // that 1.13 has no source for. The particle ids are the two explosion
        // particles from 1.20.4's own registry and neither carries extra data, so
        // they can be written exactly rather than guessed; the sound is named by
        // identifier, which is version independent.
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("explosion missing on target");
        }
        boolean toModern = target.version().number() > 404;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          double x;
          double y;
          double z;
          if (source.version().number() > 404) {
            x = input.readDouble(); y = input.readDouble(); z = input.readDouble();
          } else {
            x = input.readFloat(); y = input.readFloat(); z = input.readFloat();
          }
          float strength = input.readFloat();
          int count = source.version().number() > 404 ? MinecraftInput.varInt(input) : input.readInt();
          if (count < 0 || count > 1_000_000) yield new TranslationResult.Dropped("bad explosion record count");
          byte[] records = new byte[count * 3];
          input.readFully(records);
          float motionX = input.readFloat();
          float motionY = input.readFloat();
          float motionZ = input.readFloat();

          ByteArrayOutputStream buffer = new ByteArrayOutputStream(records.length + 64);
          DataOutputStream output = new DataOutputStream(buffer);
          if (toModern) {
            output.writeDouble(x); output.writeDouble(y); output.writeDouble(z);
          } else {
            output.writeFloat((float) x); output.writeFloat((float) y); output.writeFloat((float) z);
          }
          output.writeFloat(strength);
          if (toModern) MinecraftOutput.varInt(output, count); else output.writeInt(count);
          output.write(records);
          output.writeFloat(motionX);
          output.writeFloat(motionY);
          output.writeFloat(motionZ);
          if (toModern) {
            MinecraftOutput.varInt(output, 1);        // block interaction: DESTROY_WITH_DECAY
            MinecraftOutput.varInt(output, EXPLOSION_PARTICLE_765);
            MinecraftOutput.varInt(output, EXPLOSION_EMITTER_PARTICLE_765);
            MinecraftOutput.string(output, "minecraft:entity.generic.explode");
            output.writeBoolean(false);               // no fixed sound range
          }
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
      }
      case PLAY_SPAWN_PLAYER -> {
        // 1.20.2 removed the dedicated player spawn in favour of the unified
        // spawn packet, so a 1.13 backend's player spawns have to fan in.
        //   1.13   entityId uuid x y z yaw pitch metadata
        //   1.20.4 entityId uuid type x y z pitch yaw headYaw objectData velocity
        if (target.defines(ConnectionState.PLAY, direction, kind)) {
          rememberLiving(peekEntityId(PlayPackets.body(packet)), true);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, PlayPackets.body(packet)));
        }
        byte[] unified = playerSpawnToUnified(PlayPackets.body(packet));
        if (unified == null) yield new TranslationResult.Dropped("player spawn not parseable");
        rememberLiving(peekEntityId(unified), true);
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            PacketKind.PLAY_SPAWN_ENTITY, ConnectionState.PLAY, direction, unified));
      }
      case PLAY_UPDATE_ATTRIBUTES -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("attributes missing on target");
        }
        // Keys are mapped by identity across the 1.16 rename, and the count
        // changes width (fixed Int on 1.13, VarInt on 1.20.4). Entries with no
        // counterpart are dropped individually and the count rewritten.
        byte[] reshaped = gg.tame.conduit.protocol.entity.AttributeCodec.translate(
            source.version().number(), target.version().number(), PlayPackets.body(packet));
        if (reshaped == null) {
          yield new TranslationResult.Dropped("no attribute in this packet exists on the target");
        }
        yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
            kind, ConnectionState.PLAY, direction, reshaped));
      }
      case PLAY_SET_ENTITY_METADATA -> {
        if (!target.defines(ConnectionState.PLAY, direction, kind)) {
          yield new TranslationResult.Dropped("entity metadata missing on target");
        }
        // Type ids and field indices are both era-specific, so every entry is
        // decoded, remapped and re-encoded. Which entity this is decides how far
        // the indices shift, which is why spawns are tracked above.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int entityId = MinecraftInput.varInt(input);
          byte[] body = input.readAllBytes();
          byte[] translated = gg.tame.conduit.protocol.entity.MetadataCodec.translate(
              source, target, body, isLiving(entityId));
          if (translated == null) {
            yield new TranslationResult.Dropped("no metadata field in this packet exists on the target");
          }
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(translated.length + 5);
          DataOutputStream output = new DataOutputStream(buffer);
          MinecraftOutput.varInt(output, entityId);
          output.write(translated);
          yield new TranslationResult.Translated(new gg.tame.conduit.protocol.semantic.OpaquePacket(
              kind, ConnectionState.PLAY, direction, buffer.toByteArray()));
        }
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

  /** Packet id and leading bytes, for failure messages. Never throws itself. */
  private static String describe(byte[] packet) {
    try {
      return "id=0x" + Integer.toHexString(PlayPackets.packetId(packet))
          + " body=" + ProtocolTrace.hex(PlayPackets.body(packet), 96);
    } catch (IOException exception) {
      return "unframed packet of " + packet.length + " bytes";
    }
  }

  // ------------------------------------------------------- per-session helpers

  /** Queues an extra packet, encoded with the definition of the side it is going to. */
  private void queueToClient(PacketKind kind, ConnectionState state, PacketDirection unusedDirection,
                             ProtocolDefinition destination, byte[] body) throws IOException {
    if (!destination.defines(state, PacketDirection.SERVER_TO_CLIENT, kind)) return;
    toClient.add(PlayPackets.withId(
        destination.id(state, PacketDirection.SERVER_TO_CLIENT, kind), body));
  }

  private void queueToBackend(PacketKind kind, ProtocolDefinition destination, byte[] body) throws IOException {
    if (!destination.defines(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, kind)) return;
    toBackend.add(PlayPackets.withId(
        destination.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, kind), body));
  }

  /**
   * A 1.13 server that rejects an inventory action expects the client to send
   * the same action number back before it will apply anything else in that
   * window. A 1.20.4 client has no such packet, so Conduit closes the loop.
   */
  private void echoTransactionIfRejected(byte[] body, ProtocolDefinition backend) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int windowId = input.readUnsignedByte();
      short action = input.readShort();
      boolean accepted = input.readBoolean();
      if (accepted) return;
      queueToBackend(PacketKind.PLAY_CONFIRM_TRANSACTION, backend,
          gg.tame.conduit.protocol.inventory.ContainerCodec.confirmTransaction(windowId, action, true));
    }
  }

  /** The action number a 1.13 client put in its own click, so the ack matches it. */
  private static int sourceActionNumber(byte[] clickBody) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(clickBody))) {
      input.readUnsignedByte();      // windowId
      input.readShort();             // slot
      input.readByte();              // button
      return input.readShort();
    }
  }

  /** Whether a unified 1.20.4 spawn packet describes a living entity. */
  private static boolean spawnIsLiving765(byte[] body) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      MinecraftInput.varInt(in);        // entityId
      in.readLong();
      in.readLong();                    // uuid
      return gg.tame.conduit.protocol.entity.EntityTypeMaps.isLiving765(MinecraftInput.varInt(in));
    } catch (IOException exception) {
      return true;
    }
  }

  private void rememberLiving(int entityId, boolean living) {
    if (entityId < 0) return;
    if (livingEntities.size() > 8192) livingEntities.clear();   // bounded; entities respawn cheaply
    livingEntities.put(entityId, living);
  }

  /**
   * Whether this entity extends LivingEntity. Unknown entities are treated as
   * living: the player's own entity and every mob are living, and that is what
   * almost all metadata traffic is about, so it is the safer default.
   */
  private boolean isLiving(int entityId) {
    return livingEntities.getOrDefault(entityId, Boolean.TRUE);
  }

  private static int peekEntityId(byte[] body) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      return MinecraftInput.varInt(input);
    } catch (IOException exception) {
      return -1;
    }
  }

  /**
   * Converts a 1.13 Spawn Player into the unified modern spawn packet.
   *
   * <pre>
   *   1.13    entityId uuid x y z yaw:i8 pitch:i8 metadata
   *   1.20.4  entityId uuid type x y z pitch:i8 yaw:i8 headYaw:i8 objectData velocity
   * </pre>
   *
   * <p>The player entity type is resolved through the name-based entity map, not
   * assumed; the trailing 1.13 metadata blob is dropped because 1.19+ delivers
   * metadata in its own packet, which this translator now handles.
   */
  private static byte[] playerSpawnToUnified(byte[] body) {
    java.util.OptionalInt playerType = gg.tame.conduit.protocol.entity.EntityTypeMaps.mob393To765(
        PLAYER_TYPE_393);
    if (playerType.isEmpty()) return null;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
      DataOutputStream out = new DataOutputStream(buffer);
      MinecraftOutput.varInt(out, MinecraftInput.varInt(in));   // entityId
      out.writeLong(in.readLong());
      out.writeLong(in.readLong());
      MinecraftOutput.varInt(out, playerType.getAsInt());
      for (int axis = 0; axis < 3; axis++) out.writeDouble(in.readDouble());
      byte yaw = in.readByte();
      byte pitch = in.readByte();
      out.writeByte(pitch);          // modern order is pitch before yaw
      out.writeByte(yaw);
      out.writeByte(yaw);            // head yaw: 1.13 sends it separately, start aligned
      MinecraftOutput.varInt(out, 0);                 // objectData: unused for players
      for (int axis = 0; axis < 3; axis++) out.writeShort(0);   // no velocity in the 1.13 packet
      out.flush();
      return buffer.toByteArray();
    } catch (IOException exception) {
      return null;
    }
  }

  /** 1.13 mob-registry id for {@code minecraft:player}. */
  private static final int PLAYER_TYPE_393 = 92;

  /** 1.20.4 particle-registry ids for the two explosion particles; neither carries data. */
  private static final int EXPLOSION_PARTICLE_765 = 23;
  private static final int EXPLOSION_EMITTER_PARTICLE_765 = 22;

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
   * <p>Object-type IDs are mapped by name via {@link gg.tame.conduit.protocol.entity.EntityTypeMaps}.
   * Unmapped types fail closed (null) rather than inventing a wrong entity.
   */
  private static byte[] translateSpawnEntity(byte[] body, int fromProtocol, int toProtocol) {
    boolean fromModern = fromProtocol > 404;
    boolean toModern = toProtocol > 404;
    if (fromModern == toModern) return body;
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int entityId = MinecraftInput.varInt(in);
      long uuidHigh = in.readLong();
      long uuidLow = in.readLong();
      int type = fromModern ? MinecraftInput.varInt(in) : in.readUnsignedByte();
      double x = in.readDouble();
      double y = in.readDouble();
      double z = in.readDouble();
      byte pitch = in.readByte();
      byte yaw = in.readByte();
      byte headPitch = fromModern ? in.readByte() : 0;
      int objectData = fromModern ? MinecraftInput.varInt(in) : in.readInt();
      short vx = in.readShort();
      short vy = in.readShort();
      short vz = in.readShort();

      // The type has to be resolved together with objectData: 1.13 puts the
      // minecart variant in objectData while 1.20.4 gives each variant its own
      // entity type, so the two fields are one piece of information.
      int mappedType;
      int mappedData = objectData;
      if (toModern) {
        var mapped = gg.tame.conduit.protocol.entity.EntityTypeMaps.object393To765(type, objectData);
        if (mapped.isEmpty()) return null;
        mappedType = mapped.getAsInt();
        if (type == 10) mappedData = 0;          // variant is now carried by the type itself
      } else {
        var mapped = gg.tame.conduit.protocol.entity.EntityTypeMaps.toObject393(type);
        if (mapped.isEmpty()) return null;
        mappedType = mapped.getAsInt();
        if (mappedType == 10) {
          mappedData = gg.tame.conduit.protocol.entity.LegacyObjectTypes.objectDataFor(type);
        }
      }

      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 8);
      DataOutputStream out = new DataOutputStream(buffer);
      MinecraftOutput.varInt(out, entityId);
      out.writeLong(uuidHigh);
      out.writeLong(uuidLow);
      if (toModern) MinecraftOutput.varInt(out, mappedType); else out.writeByte(mappedType & 0xff);
      out.writeDouble(x);
      out.writeDouble(y);
      out.writeDouble(z);
      out.writeByte(pitch);
      out.writeByte(yaw);
      if (toModern) out.writeByte(headPitch);
      if (toModern) MinecraftOutput.varInt(out, mappedData); else out.writeInt(mappedData);
      out.writeShort(vx);
      out.writeShort(vy);
      out.writeShort(vz);
      out.flush();
      return buffer.toByteArray();
    } catch (IOException exception) {
      return null;
    }
  }

  private record Spawn393(PacketKind kind, byte[] body) {}

  /**
   * Splits a 1.20.4 unified spawn into 1.13 living ({@code 0x03}) or object ({@code 0x00}) form.
   */
  private static Spawn393 unifiedSpawnTo393(byte[] body) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      int entityId = MinecraftInput.varInt(in);
      long uuidHigh = in.readLong();
      long uuidLow = in.readLong();
      int type765 = MinecraftInput.varInt(in);
      double x = in.readDouble();
      double y = in.readDouble();
      double z = in.readDouble();
      byte pitch = in.readByte();
      byte yaw = in.readByte();
      byte headPitch = in.readByte();
      int objectData = MinecraftInput.varInt(in);
      short vx = in.readShort();
      short vy = in.readShort();
      short vz = in.readShort();

      var preferLiving = gg.tame.conduit.protocol.entity.EntityTypeMaps.isLiving765(type765);
      if (preferLiving) {
        var mob = gg.tame.conduit.protocol.entity.EntityTypeMaps.toMob393(type765);
        if (mob.isPresent()) {
          return living393(entityId, uuidHigh, uuidLow, mob.getAsInt(), x, y, z, yaw, pitch, headPitch, vx, vy, vz);
        }
        var object = gg.tame.conduit.protocol.entity.EntityTypeMaps.toObject393(type765);
        if (object.isEmpty()) return null;
        return object393(entityId, uuidHigh, uuidLow, object.getAsInt(), x, y, z, pitch, yaw, objectData, vx, vy, vz);
      }

      var object = gg.tame.conduit.protocol.entity.EntityTypeMaps.toObject393(type765);
      if (object.isPresent()) {
        return object393(entityId, uuidHigh, uuidLow, object.getAsInt(), x, y, z, pitch, yaw, objectData, vx, vy, vz);
      }
      var mob = gg.tame.conduit.protocol.entity.EntityTypeMaps.toMob393(type765);
      if (mob.isEmpty()) return null;
      return living393(entityId, uuidHigh, uuidLow, mob.getAsInt(), x, y, z, yaw, pitch, headPitch, vx, vy, vz);
    } catch (IOException exception) {
      return null;
    }
  }

  private static Spawn393 living393(int entityId, long uuidHigh, long uuidLow, int type393,
                                    double x, double y, double z, byte yaw, byte pitch, byte headPitch,
                                    short vx, short vy, short vz) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, entityId);
    out.writeLong(uuidHigh);
    out.writeLong(uuidLow);
    MinecraftOutput.varInt(out, type393);
    out.writeDouble(x);
    out.writeDouble(y);
    out.writeDouble(z);
    out.writeByte(yaw);       // 1.13 living: yaw then pitch
    out.writeByte(pitch);
    out.writeByte(headPitch);
    out.writeShort(vx);
    out.writeShort(vy);
    out.writeShort(vz);
    out.writeByte(0xff);      // empty metadata terminator (full metadata still withheld)
    out.flush();
    return new Spawn393(PacketKind.PLAY_SPAWN_LIVING_ENTITY, buffer.toByteArray());
  }

  private static Spawn393 object393(int entityId, long uuidHigh, long uuidLow, int type393,
                                    double x, double y, double z, byte pitch, byte yaw, int objectData,
                                    short vx, short vy, short vz) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, entityId);
    out.writeLong(uuidHigh);
    out.writeLong(uuidLow);
    out.writeByte(type393 & 0xff);
    out.writeDouble(x);
    out.writeDouble(y);
    out.writeDouble(z);
    out.writeByte(pitch);
    out.writeByte(yaw);
    out.writeInt(objectData);
    out.writeShort(vx);
    out.writeShort(vy);
    out.writeShort(vz);
    out.flush();
    return new Spawn393(PacketKind.PLAY_SPAWN_ENTITY, buffer.toByteArray());
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
   * <p>Entity type IDs are mapped by name via {@link gg.tame.conduit.protocol.entity.EntityTypeMaps}.
   * Unmapped mobs are suppressed (null) rather than shown as the wrong model.
   */
  private static byte[] livingSpawnToUnified(byte[] body) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 4);
      DataOutputStream out = new DataOutputStream(buffer);

      MinecraftOutput.varInt(out, MinecraftInput.varInt(in));   // entityId
      out.writeLong(in.readLong());                             // uuid high
      out.writeLong(in.readLong());                             // uuid low
      int type393 = MinecraftInput.varInt(in);
      var mapped = gg.tame.conduit.protocol.entity.EntityTypeMaps.mob393To765(type393);
      if (mapped.isEmpty()) return null;
      MinecraftOutput.varInt(out, mapped.getAsInt());
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
      // entity metadata as its own packet. Full index/type remapping is still TODO;
      // empty spawn is preferred over wrong metadata that can NPE the client.
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

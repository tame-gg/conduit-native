package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.codec.SemanticCodec;
import gg.tame.conduit.protocol.semantic.EmptyPacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.OpaquePacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.semantic.SemanticPacket;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Genuine 765 ↔ 766 translator for Conduit-known packets.
 * Unknown play/world packets fail closed — this is PARTIAL play coverage, not ViaVersion.
 * Registry/known-packs that only exist on 766 are dropped toward 765 or synthesized empty toward 766 when required.
 */
public final class Protocol765To766Translator implements ProtocolTranslator {
  public static final Protocol765To766Translator V765_TO_766 = new Protocol765To766Translator(765, 766);
  public static final Protocol765To766Translator V766_TO_765 = new Protocol765To766Translator(766, 765);

  private static final Set<PacketKind> SAFE_OPAQUE = EnumSet.of(
      PacketKind.LOGIN_START,
      PacketKind.LOGIN_SUCCESS,
      PacketKind.LOGIN_DISCONNECT,
      PacketKind.LOGIN_SET_COMPRESSION,
      PacketKind.LOGIN_PLUGIN_REQUEST,
      PacketKind.LOGIN_PLUGIN_RESPONSE,
      PacketKind.LOGIN_ENCRYPTION_REQUEST,
      PacketKind.LOGIN_ENCRYPTION_RESPONSE,
      PacketKind.CONFIGURATION_DISCONNECT,
      PacketKind.CONFIGURATION_CLIENT_INFORMATION,
      PacketKind.PLAY_CLIENT_INFORMATION,
      PacketKind.PLAY_CHAT_COMMAND,
      PacketKind.PLAY_TAB_COMPLETE,
      PacketKind.PLAY_TAB_COMPLETE_REQUEST,
      PacketKind.PLAY_DISCONNECT,
      PacketKind.PLAY_DECLARE_COMMANDS,
      PacketKind.PLAY_SYSTEM_CHAT,
      PacketKind.PLAY_PLAYER_INFO_REMOVE
  );

  private final int source;
  private final int target;
  private final SemanticCodec sourceCodec;
  private final SemanticCodec targetCodec;

  private Protocol765To766Translator(int source, int target) {
    this.source = source;
    this.target = target;
    this.sourceCodec = new SemanticCodec(ProtocolDefinition.forVersion(source), 2 * 1024 * 1024);
    this.targetCodec = new SemanticCodec(ProtocolDefinition.forVersion(target), 2 * 1024 * 1024);
  }

  public int sourceProtocol() { return source; }
  public int targetProtocol() { return target; }

  @Override public byte[] clientToBackend(ConnectionState state, byte[] packet) {
    return translate(state, PacketDirection.CLIENT_TO_SERVER, packet, sourceCodec, targetCodec, source, target);
  }

  @Override public byte[] backendToClient(ConnectionState state, byte[] packet) {
    return translate(state, PacketDirection.SERVER_TO_CLIENT, packet, targetCodec, sourceCodec, target, source);
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
      TranslationResult result = translateKind(state, direction, packetKind, packet, from, to);
      return switch (result) {
        case TranslationResult.Translated translated -> {
          byte[] encoded = to.encode(translated.packet());
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
      throw new TranslationException("translation failed: " + exception.getMessage(), exception);
    }
  }

  private TranslationResult translateKind(ConnectionState state, PacketDirection direction, PacketKind kind,
                                          byte[] packet, SemanticCodec from, SemanticCodec to) throws IOException {
    // Known-packs / registry exist on 766 only.
    if (kind == PacketKind.CONFIGURATION_KNOWN_PACKS || kind == PacketKind.CONFIGURATION_REGISTRY
        || kind == PacketKind.CONFIGURATION_RESET_CHAT) {
      if (!to.protocol().defines(state, direction, kind)) {
        return new TranslationResult.Dropped(kind + " not present on protocol " + to.protocol().version().number());
      }
      // Body formats differ across versions — do not blindly rematerialize registry blobs.
      if (kind == PacketKind.CONFIGURATION_REGISTRY) {
        return new TranslationResult.Unsupported("registry data translation 765↔766 is not implemented");
      }
      if (kind == PacketKind.CONFIGURATION_KNOWN_PACKS) {
        // Empty known-packs list is safe either direction when the packet exists.
        return new TranslationResult.Translated(new OpaquePacket(kind, state, direction, new byte[] {0}));
      }
      return new TranslationResult.Translated(new EmptyPacket(kind, state, direction));
    }

    SemanticPacket decoded = from.decode(state, direction, packet);
    if (decoded instanceof EmptyPacket || decoded instanceof PluginMessagePacket || decoded instanceof KeepAlivePacket) {
      if (!to.protocol().defines(state, direction, kind)) {
        return new TranslationResult.Dropped(kind + " missing on target");
      }
      return new TranslationResult.Translated(decoded);
    }
    if (decoded instanceof OpaquePacket opaque) {
      if (!SAFE_OPAQUE.contains(kind)) {
        // Join game / player info update carry version-sensitive fields (items/components).
        if (kind == PacketKind.PLAY_LOGIN || kind == PacketKind.PLAY_PLAYER_INFO_UPDATE) {
          return new TranslationResult.Unsupported(kind + " requires dedicated field translation (PARTIAL)");
        }
        return new TranslationResult.Unsupported(kind + " is not in the 765↔766 safe set");
      }
      if (!to.protocol().defines(state, direction, kind)) {
        return new TranslationResult.Dropped(kind + " missing on target");
      }
      return new TranslationResult.Translated(opaque);
    }
    return new TranslationResult.Unsupported("unhandled semantic type");
  }
}

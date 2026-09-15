package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolTrace;
import gg.tame.conduit.protocol.ProtocolTranslator;
import gg.tame.conduit.protocol.codec.SemanticCodec;
import gg.tame.conduit.protocol.entity.MetadataCodec;
import gg.tame.conduit.protocol.inventory.ContainerCodec;
import gg.tame.conduit.protocol.semantic.EmptyPacket;
import gg.tame.conduit.protocol.semantic.KeepAlivePacket;
import gg.tame.conduit.protocol.semantic.OpaquePacket;
import gg.tame.conduit.protocol.semantic.PluginMessagePacket;
import gg.tame.conduit.protocol.semantic.SemanticPacket;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Incremental 393 (1.13) ↔ 404 (1.13.2) translator.
 *
 * <p>Packet ids and nearly every field layout are identical between these two
 * releases. The published delta that matters for gameplay is Slot: 1.13 writes
 * a signed short item id ({@code -1} empty); 1.13.2 writes a present boolean
 * plus a VarInt id. This translator rematerialises every Slot-bearing packet
 * through {@link gg.tame.conduit.protocol.item.ItemCodec} and forwards the rest
 * as opaque bodies under the same {@link PacketKind}.
 *
 * <p>Recipes, advancements and trade lists also embed Slot payloads but are
 * not required for core gameplay; they are dropped rather than risk a
 * mis-parsed stream. No new semantic model is introduced — {@code SemanticItem}
 * already covers the wire change.
 */
public final class Protocol393To404Translator implements ProtocolTranslator {
  public static Protocol393To404Translator client393() {
    return new Protocol393To404Translator(393, 404);
  }

  public static Protocol393To404Translator client404() {
    return new Protocol393To404Translator(404, 393);
  }

  /** Packets whose bodies embed Slot and whose full schema is not rematerialised here. */
  private static final Set<PacketKind> SLOT_DROP = EnumSet.of(
      PacketKind.PLAY_DECLARE_RECIPES,
      PacketKind.PLAY_UPDATE_ADVANCEMENTS,
      PacketKind.PLAY_TRADE_LIST
  );

  private final int source;
  private final int target;
  private final SemanticCodec sourceCodec;
  private final SemanticCodec targetCodec;

  private Protocol393To404Translator(int source, int target) {
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
      TranslationResult result = translateKind(state, direction, packetKind, packet, from, to, fromProtocol, toProtocol);
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
      throw new TranslationException("393↔404 translation failed: " + exception.getMessage(), exception);
    }
  }

  private TranslationResult translateKind(ConnectionState state, PacketDirection direction, PacketKind kind,
                                          byte[] packet, SemanticCodec from, SemanticCodec to,
                                          int fromProtocol, int toProtocol) throws IOException {
    if (SLOT_DROP.contains(kind)) {
      return new TranslationResult.Dropped(kind + " embeds Slot; not rematerialised for 393↔404 yet");
    }
    if (!to.protocol().defines(state, direction, kind)) {
      return new TranslationResult.Dropped(kind + " missing on protocol " + toProtocol);
    }

    byte[] body = PlayPackets.body(packet);
    return switch (kind) {
      case PLAY_SET_CONTAINER_CONTENT -> new TranslationResult.Translated(new OpaquePacket(
          kind, state, direction, ContainerCodec.containerContent(fromProtocol, toProtocol, body, 0)));
      case PLAY_SET_CONTAINER_SLOT -> new TranslationResult.Translated(new OpaquePacket(
          kind, state, direction, ContainerCodec.containerSlot(fromProtocol, toProtocol, body, 0)));
      case PLAY_CLICK_WINDOW -> {
        var click = ContainerCodec.readClick(fromProtocol, body);
        yield new TranslationResult.Translated(new OpaquePacket(
            kind, state, direction,
            ContainerCodec.writeClick(toProtocol, click, 0, click.actionNumber())));
      }
      case PLAY_CREATIVE_SLOT -> new TranslationResult.Translated(new OpaquePacket(
          kind, state, direction, ContainerCodec.creativeSlot(fromProtocol, toProtocol, body)));
      case PLAY_ENTITY_EQUIPMENT -> {
        var changes = ContainerCodec.readEquipment(fromProtocol, body);
        if (changes.isEmpty()) yield new TranslationResult.Dropped("empty equipment");
        // Both sides are ≤404: one slot per packet.
        yield new TranslationResult.Translated(new OpaquePacket(
            kind, state, direction, ContainerCodec.writeEquipment(toProtocol, changes.get(0))));
      }
      case PLAY_SET_ENTITY_METADATA -> {
        try (java.io.DataInputStream input = new java.io.DataInputStream(
            new java.io.ByteArrayInputStream(body))) {
          int entityId = gg.tame.conduit.protocol.MinecraftInput.varInt(input);
          byte[] meta = input.readAllBytes();
          // Same-era: living flag is unused (indices unchanged). Default true.
          byte[] translated = MetadataCodec.translate(from.protocol(), to.protocol(), meta, true);
          if (translated == null) {
            yield new TranslationResult.Dropped("no metadata fields survived");
          }
          java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(translated.length + 5);
          java.io.DataOutputStream output = new java.io.DataOutputStream(buffer);
          gg.tame.conduit.protocol.MinecraftOutput.varInt(output, entityId);
          output.write(translated);
          yield new TranslationResult.Translated(new OpaquePacket(kind, state, direction, buffer.toByteArray()));
        }
      }
      default -> {
        SemanticPacket decoded = from.decode(state, direction, packet);
        if (decoded instanceof EmptyPacket || decoded instanceof PluginMessagePacket
            || decoded instanceof KeepAlivePacket || decoded instanceof OpaquePacket) {
          yield new TranslationResult.Translated(decoded);
        }
        yield new TranslationResult.Unsupported("unhandled semantic type for " + kind);
      }
    };
  }
}

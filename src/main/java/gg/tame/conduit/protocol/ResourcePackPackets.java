// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.player.ResourcePack;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Resource packs in one client's own protocol: offers the proxy writes, and what it reads of the
 * packs a server offers and of the client's answers.
 *
 * <p>Three families, and the packet table says which a release has. Through 1.20.2 one Resource Pack
 * Send carries a URL and a hash, and from 1.17 a required flag and a JSON prompt; its answer carries
 * the result, after the pack's hash on 1.8. From 1.20.3 Push and Pop name each pack by UUID, the prompt
 * is network NBT, and the answer names the pack. 1.20.2 has Send in Configuration as well as Play, and
 * 1.20.3 Push, Pop and the answer in both.
 */
public final class ResourcePackPackets {
  private ResourcePackPackets() {}

  /** Longest hash a client or server writes; anything longer is not a hash. */
  private static final int MAX_HASH = 40;

  /** A clientbound resource-pack packet a server sent. */
  public sealed interface Clientbound permits Offer, Removal {}
  /**
   * A pack a server offered. {@code id} is the one its release named it by, or else one derived from
   * its URL and hash; {@code pack} is empty for an offer this API cannot hold, such as a blank URL.
   */
  public record Offer(UUID id, Optional<ResourcePack> pack, boolean named) implements Clientbound {}
  /** A server dropped one pack, or every one when {@code id} is empty (1.20.3+ only). */
  public record Removal(Optional<UUID> id) implements Clientbound {}
  /** The client's answer: the pack it names (1.20.3+), the hash it names (1.8), and what it says, empty for a result no release defines. */
  public record Answer(Optional<UUID> id, Optional<String> hash, Optional<ResourcePack.Status> status) {}

  /** Whether this client's release names packs by UUID (1.20.3's Push and Pop). */
  public static boolean named(ProtocolDefinition protocol) {
    return protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_PUSH);
  }

  /** Whether this client's release has a Play packet to offer a pack with at all. */
  public static boolean supported(ProtocolDefinition protocol) {
    return named(protocol) || protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_SEND);
  }

  /** The Play packet offering {@code pack}, or empty when this release has none. */
  public static Optional<byte[]> offer(ProtocolDefinition protocol, ResourcePack pack) throws IOException {
    int number = protocol.version().number();
    if (named(protocol)) {
      return Optional.of(packet(protocol, PacketKind.PLAY_RESOURCE_PACK_PUSH, output -> {
        uuid(output, pack.id());
        MinecraftOutput.string(output, pack.url());
        MinecraftOutput.string(output, pack.hash());
        output.writeBoolean(pack.required());
        prompt(output, pack.prompt(), number);
      }));
    }
    if (!supported(protocol)) return Optional.empty();
    return Optional.of(packet(protocol, PacketKind.PLAY_RESOURCE_PACK_SEND, output -> {
      MinecraftOutput.string(output, pack.url());
      MinecraftOutput.string(output, pack.hash());
      if (ProtocolEras.resourcePackPrompt(number)) {
        output.writeBoolean(pack.required());
        prompt(output, pack.prompt(), number);
      }
    }));
  }

  /** The Play packet dropping the pack {@code id}, or every pack when it is null; empty before 1.20.3. */
  public static Optional<byte[]> remove(ProtocolDefinition protocol, UUID id) throws IOException {
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESOURCE_PACK_POP)) return Optional.empty();
    return Optional.of(packet(protocol, PacketKind.PLAY_RESOURCE_PACK_POP, output -> {
      output.writeBoolean(id != null);
      if (id != null) uuid(output, id);
    }));
  }

  /**
   * A server's offer or removal, read from a clientbound packet in the client's protocol and in
   * {@code state}. Empty for any other packet, and for one too broken to read.
   */
  public static Optional<Clientbound> read(ProtocolDefinition protocol, ConnectionState state, byte[] packet) {
    PacketKind send = kind(state, PacketKind.PLAY_RESOURCE_PACK_SEND, PacketKind.CONFIGURATION_RESOURCE_PACK_SEND);
    PacketKind push = kind(state, PacketKind.PLAY_RESOURCE_PACK_PUSH, PacketKind.CONFIGURATION_RESOURCE_PACK_PUSH);
    PacketKind pop = kind(state, PacketKind.PLAY_RESOURCE_PACK_POP, PacketKind.CONFIGURATION_RESOURCE_PACK_POP);
    if (send == null) return Optional.empty();
    try {
      int id = PlayPackets.peekId(packet);
      int number = protocol.version().number();
      boolean nbt = ProtocolEras.textComponentNbt(number);
      if (is(protocol, state, PacketDirection.SERVER_TO_CLIENT, id, pop)) {
        try (DataInputStream input = body(packet)) {
          return Optional.of(new Removal(input.readBoolean() ? Optional.of(uuid(input)) : Optional.empty()));
        }
      }
      if (is(protocol, state, PacketDirection.SERVER_TO_CLIENT, id, push)) {
        try (DataInputStream input = body(packet)) {
          UUID named = uuid(input);
          String url = MinecraftInput.string(input, ResourcePack.MAX_URL * 3);
          String hash = MinecraftInput.string(input, MAX_HASH * 3);
          boolean required = input.readBoolean();
          return Optional.of(new Offer(named, pack(named, url, hash, required, readPrompt(input, nbt)), true));
        }
      }
      if (is(protocol, state, PacketDirection.SERVER_TO_CLIENT, id, send)) {
        try (DataInputStream input = body(packet)) {
          String url = MinecraftInput.string(input, ResourcePack.MAX_URL * 3);
          String hash = MinecraftInput.string(input, MAX_HASH * 3);
          boolean required = false;
          Text prompt = Text.empty();
          if (ProtocolEras.resourcePackPrompt(number)) {
            required = input.readBoolean();
            prompt = readPrompt(input, nbt);
          }
          UUID derived = UUID.nameUUIDFromBytes((url + '\0' + hash).getBytes(StandardCharsets.UTF_8));
          return Optional.of(new Offer(derived, pack(derived, url, hash, required, prompt), false));
        }
      }
    } catch (IOException | RuntimeException unreadable) {
      // Not one the proxy can follow; it goes to the client as it came, as it always did.
    }
    return Optional.empty();
  }

  /** The client's answer, read from a serverbound packet in {@code state}; empty for any other packet. */
  public static Optional<Answer> answer(ProtocolDefinition protocol, ConnectionState state, byte[] packet) {
    PacketKind status = kind(state, PacketKind.PLAY_RESOURCE_PACK_STATUS, PacketKind.CONFIGURATION_RESOURCE_PACK_STATUS);
    if (status == null) return Optional.empty();
    try {
      if (!is(protocol, state, PacketDirection.CLIENT_TO_SERVER, PlayPackets.peekId(packet), status)) return Optional.empty();
      try (DataInputStream input = body(packet)) {
        Optional<UUID> id = named(protocol) ? Optional.of(uuid(input)) : Optional.empty();
        Optional<String> hash = ProtocolEras.resourcePackStatusHash(protocol.version().number())
            ? Optional.of(MinecraftInput.string(input, MAX_HASH * 3)) : Optional.empty();
        return Optional.of(new Answer(id, hash, status(MinecraftInput.varInt(input))));
      }
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    }
  }

  /** The wire's results, 0-3 since 1.8 and 4-7 from 1.20.3. */
  private static Optional<ResourcePack.Status> status(int result) {
    return Optional.ofNullable(switch (result) {
      case 0 -> ResourcePack.Status.LOADED;
      case 1 -> ResourcePack.Status.DECLINED;
      case 2 -> ResourcePack.Status.FAILED_DOWNLOAD;
      case 3 -> ResourcePack.Status.ACCEPTED;
      case 4 -> ResourcePack.Status.DOWNLOADED;
      case 5 -> ResourcePack.Status.INVALID_URL;
      case 6 -> ResourcePack.Status.FAILED_RELOAD;
      case 7 -> ResourcePack.Status.DISCARDED;
      default -> null;
    });
  }

  /** A server's hash need not be one this API accepts; the pack is still followed, without it. */
  private static Optional<ResourcePack> pack(UUID id, String url, String hash, boolean required, Text prompt) {
    try {
      return Optional.of(new ResourcePack(id, url, hash, required, prompt));
    } catch (IllegalArgumentException odd) {
      try { return Optional.of(new ResourcePack(id, url, "", required, prompt)); }
      catch (IllegalArgumentException unusable) { return Optional.empty(); }
    }
  }

  /** Written for the client's own release: JSON before 1.20.3, network NBT from it. */
  private static void prompt(DataOutputStream output, Text prompt, int protocol) throws IOException {
    boolean present = !prompt.plain().isEmpty();
    output.writeBoolean(present);
    if (present) TextCodec.write(output, prompt, protocol);
  }

  private static Text readPrompt(DataInputStream input, boolean nbt) throws IOException {
    if (!input.readBoolean()) return Text.empty();
    return TextCodec.fromJson(nbt ? ComponentCodec.nbtToJson(input) : MinecraftInput.string(input, 262144));
  }

  private static PacketKind kind(ConnectionState state, PacketKind play, PacketKind configuration) {
    return switch (state) {
      case PLAY -> play;
      case CONFIGURATION -> configuration;
      default -> null;
    };
  }

  private static boolean is(ProtocolDefinition protocol, ConnectionState state, PacketDirection direction, int id, PacketKind kind) {
    return protocol.defines(state, direction, kind) && protocol.is(state, direction, id, kind);
  }

  private static DataInputStream body(byte[] packet) throws IOException {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input);
    return input;
  }

  private static UUID uuid(DataInputStream input) throws IOException { return new UUID(input.readLong(), input.readLong()); }

  private static void uuid(DataOutputStream output, UUID id) throws IOException {
    output.writeLong(id.getMostSignificantBits());
    output.writeLong(id.getLeastSignificantBits());
  }

  private interface Body { void write(DataOutputStream output) throws IOException; }

  private static byte[] packet(ProtocolDefinition protocol, PacketKind kind, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind));
      body.write(output);
    }
    return bytes.toByteArray();
  }
}

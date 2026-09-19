// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.particle;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.chunk.BlockStateMaps;
import gg.tame.conduit.protocol.item.ItemCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * The Particle packet across the 1.13 / 1.20.4 boundary.
 *
 * <p>Unlike most packets on this pair, the id is only part of the problem. The
 * field layout changed as well:
 *
 * <pre>
 *   1.13  (393)   i32 id | bool longDistance | f32 x | f32 y | f32 z
 *                 | f32 offsetX | f32 offsetY | f32 offsetZ | f32 data | i32 count | payload
 *   1.20.4 (765)  VarInt id | bool longDistance | f64 x | f64 y | f64 z
 *                 | f32 offsetX | f32 offsetY | f32 offsetZ | f32 maxSpeed | i32 count | payload
 * </pre>
 *
 * <p>So the id widens from a fixed 32-bit int to a VarInt and the position
 * widens from float to double, which is why forwarding the bytes could never
 * have worked even for a particle whose id happened to agree.
 *
 * <p>Four of 1.13's fifty particles carry a payload after the count, and each
 * payload names something from the sending side's own registries, so it is
 * translated rather than copied:
 *
 * <ul>
 *   <li>{@code block} and {@code falling_dust} carry a block state, translated
 *       through {@link BlockStateMaps}</li>
 *   <li>{@code item} carries an item stack, translated through {@link ItemCodec}</li>
 *   <li>{@code dust} carries red, green, blue and a scale, four floats that mean
 *       the same on both sides and are copied</li>
 * </ul>
 *
 * <p>Every other particle has no payload. A 1.20.4 particle whose payload shape
 * 1.13 has no equivalent for — the ones added since, such as
 * {@code dust_color_transition} or {@code vibration} — has no 1.13 id either, so
 * it is already dropped by the id lookup and its payload is never reached.
 *
 * <p>Everything here is bounded and fails closed: a body that is truncated, has
 * trailing bytes, or names a particle or block state the other side does not
 * have yields null, and the caller drops that one packet rather than forwarding
 * bytes the client would read as a different packet.
 */
public final class ParticleCodec {
  /** Payload shapes a particle can carry on this pair. */
  private enum Payload { NONE, BLOCK_STATE, ITEM, DUST }

  private ParticleCodec() {}

  /** A Particle packet, with its payload already translated into the target's terms. */
  public record Particle(String name, boolean longDistance, double x, double y, double z,
                         float offsetX, float offsetY, float offsetZ, float speed, int count,
                         byte[] payload) {}

  /**
   * Which payload a particle carries, by name.
   *
   * <p>By name rather than by id, because the id means different things on the
   * two sides — which is the whole reason this class exists.
   */
  private static Payload payloadOf(String name) {
    switch (name) {
      case "minecraft:block":
      case "minecraft:falling_dust":
        return Payload.BLOCK_STATE;
      case "minecraft:item":
        return Payload.ITEM;
      case "minecraft:dust":
        return Payload.DUST;
      default:
        return Payload.NONE;
    }
  }

  /**
   * Reads a Particle body written by {@code fromProtocol} and rewrites it for
   * {@code toProtocol}, or null when it cannot be carried there.
   *
   * <p>Both sides are needed at once because the payload is translated in the
   * same pass: a block state or an item has to be resolved against the registry
   * it was written with and rewritten against the one it is going to.
   */
  public static byte[] translate(int fromProtocol, int toProtocol, byte[] body) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int sourceId = fromProtocol >= 765 ? MinecraftInput.varInt(input) : input.readInt();
      String name = ParticleRegistries.name(fromProtocol, sourceId).orElse(null);
      if (name == null) return null;
      int targetId = ParticleRegistries.id(toProtocol, name).orElse(-1);
      if (targetId < 0) return null;

      boolean longDistance = input.readBoolean();
      double x = fromProtocol >= 765 ? input.readDouble() : input.readFloat();
      double y = fromProtocol >= 765 ? input.readDouble() : input.readFloat();
      double z = fromProtocol >= 765 ? input.readDouble() : input.readFloat();
      float offsetX = input.readFloat();
      float offsetY = input.readFloat();
      float offsetZ = input.readFloat();
      float speed = input.readFloat();
      int count = input.readInt();

      ByteArrayOutputStream payload = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(payload)) {
        if (!translatePayload(payloadOf(name), fromProtocol, toProtocol, input, out)) return null;
      }
      if (input.read() != -1) return null;              // trailing bytes: not this packet

      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        if (toProtocol >= 765) {
          MinecraftOutput.varInt(output, targetId);
        } else {
          output.writeInt(targetId);
        }
        output.writeBoolean(longDistance);
        if (toProtocol >= 765) {
          output.writeDouble(x);
          output.writeDouble(y);
          output.writeDouble(z);
        } else {
          output.writeFloat((float) x);
          output.writeFloat((float) y);
          output.writeFloat((float) z);
        }
        output.writeFloat(offsetX);
        output.writeFloat(offsetY);
        output.writeFloat(offsetZ);
        output.writeFloat(speed);
        output.writeInt(count);
        output.write(payload.toByteArray());
      }
      return bytes.toByteArray();
    } catch (EOFException truncated) {
      return null;
    } catch (IOException malformed) {
      return null;
    }
  }

  /**
   * Translates the payload that follows the count, returning false when it cannot be.
   *
   * <p>A block state that has no counterpart fails the whole packet rather than
   * falling back to a default: a particle of the wrong block is not a smaller
   * error than no particle, and this pair's block table already resolves by name
   * and property set.
   */
  private static boolean translatePayload(Payload payload, int fromProtocol, int toProtocol,
                                          DataInputStream input, DataOutputStream output)
      throws IOException {
    switch (payload) {
      case NONE:
        return true;
      case BLOCK_STATE: {
        int state = MinecraftInput.varInt(input);
        if (!BlockStateMaps.supports(fromProtocol, toProtocol)) return false;
        int mapped = BlockStateMaps.translate(fromProtocol, toProtocol, state);
        if (mapped < 0) return false;
        MinecraftOutput.varInt(output, mapped);
        return true;
      }
      case ITEM:
        ItemCodec.translate(fromProtocol, toProtocol, input, output);
        return true;
      case DUST:
        // Red, green, blue and scale: the same four floats on both sides.
        output.writeFloat(input.readFloat());
        output.writeFloat(input.readFloat());
        output.writeFloat(input.readFloat());
        output.writeFloat(input.readFloat());
        return true;
      default:
        return false;
    }
  }
}

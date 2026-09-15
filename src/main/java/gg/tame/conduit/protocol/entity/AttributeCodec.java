package gg.tame.conduit.protocol.entity;

import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entity attributes between protocol 393 and 765.
 *
 * <p>Two independent changes have to be undone together. 1.16 renamed every
 * attribute from camelCase to a namespaced snake_case identifier
 * ({@code generic.movementSpeed} became {@code minecraft:generic.movement_speed}),
 * and the entry count changed width from a fixed Int to a VarInt. Copying the
 * body through therefore both names attributes the target does not know and
 * misreads the header — this is exactly the case where a shared packet name
 * hides an incompatible packet.
 *
 * <p>Keys are mapped by identity, in both directions, from the attribute
 * registries of the two releases. An attribute with no counterpart (1.20's
 * {@code max_absorption}) is dropped entry-by-entry and the count is rewritten,
 * so the rest of the packet still applies. Modifier records — UUID, amount,
 * operation — are unchanged between the two releases and are carried verbatim.
 */
public final class AttributeCodec {
  private static final Map<String, String> TO_765 = new LinkedHashMap<>();
  private static final Map<String, String> TO_393 = new LinkedHashMap<>();

  static {
    pair("generic.maxHealth", "minecraft:generic.max_health");
    pair("generic.followRange", "minecraft:generic.follow_range");
    pair("generic.knockbackResistance", "minecraft:generic.knockback_resistance");
    pair("generic.movementSpeed", "minecraft:generic.movement_speed");
    pair("generic.flyingSpeed", "minecraft:generic.flying_speed");
    pair("generic.attackDamage", "minecraft:generic.attack_damage");
    pair("generic.attackKnockback", "minecraft:generic.attack_knockback");
    pair("generic.attackSpeed", "minecraft:generic.attack_speed");
    pair("generic.armor", "minecraft:generic.armor");
    pair("generic.armorToughness", "minecraft:generic.armor_toughness");
    pair("generic.luck", "minecraft:generic.luck");
    pair("horse.jumpStrength", "minecraft:horse.jump_strength");
    pair("zombie.spawnReinforcements", "minecraft:zombie.spawn_reinforcements");
  }

  private AttributeCodec() {}

  private static void pair(String legacy, String modern) {
    TO_765.put(legacy, modern);
    TO_393.put(modern, legacy);
    // A 1.13 server may also send the key already namespaced.
    TO_765.put("minecraft:" + legacy, modern);
  }

  /** Translates an Update Attributes body. Returns null when no attribute survived. */
  public static byte[] translate(int fromProtocol, int toProtocol, byte[] body) throws IOException {
    boolean fromModern = fromProtocol > 404;
    boolean toModern = toProtocol > 404;

    ByteArrayOutputStream entries = new ByteArrayOutputStream(body.length);
    DataOutputStream out = new DataOutputStream(entries);
    int kept = 0;
    int entityId;

    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
      entityId = MinecraftInput.varInt(in);
      int count = fromModern ? MinecraftInput.varInt(in) : in.readInt();
      if (count < 0 || count > 4096) throw new IOException("attribute count " + count);
      for (int index = 0; index < count; index++) {
        String key = MinecraftInput.string(in, 32767);
        double value = in.readDouble();
        int modifiers = MinecraftInput.varInt(in);
        if (modifiers < 0 || modifiers > 1024) throw new IOException("modifier count " + modifiers);
        byte[] modifierBytes = new byte[modifiers * (16 + 8 + 1)];
        in.readFully(modifierBytes);

        String mapped = (toModern ? TO_765 : TO_393).get(key);
        if (mapped == null) continue;             // unknown on the target: drop this entry
        MinecraftOutput.string(out, mapped);
        out.writeDouble(value);
        MinecraftOutput.varInt(out, modifiers);
        out.write(modifierBytes);
        kept++;
      }
    }
    out.flush();
    if (kept == 0) return null;

    ByteArrayOutputStream packet = new ByteArrayOutputStream(entries.size() + 8);
    DataOutputStream header = new DataOutputStream(packet);
    MinecraftOutput.varInt(header, entityId);
    if (toModern) MinecraftOutput.varInt(header, kept); else header.writeInt(kept);
    header.write(entries.toByteArray());
    header.flush();
    return packet.toByteArray();
  }

  /** The target-era identifier for an attribute, for tests and diagnostics. */
  public static String mapKey(int toProtocol, String key) {
    return (toProtocol > 404 ? TO_765 : TO_393).get(key);
  }
}

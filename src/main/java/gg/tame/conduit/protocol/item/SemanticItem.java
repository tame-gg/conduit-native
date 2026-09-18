// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.item;

import java.util.List;
import java.util.Optional;

/**
 * One item stack, expressed the way both eras agree on it rather than the way
 * either wire format happens to encode it.
 *
 * <p>Identity is the registry <em>identifier</em> ({@code minecraft:diamond_sword}),
 * never a numeric id: 1.13 and 1.20.4 give the same number to different items.
 * The remaining fields are the gameplay-visible state a player would notice if
 * it were lost — count, durability, display name, enchantments — and
 * {@link #tag()} carries the complete item NBT compound so anything not modelled
 * explicitly (books, shulker contents, custom plugin data, attribute modifiers)
 * still survives the crossing untouched.
 *
 * <p>{@code tag} is stored as the compound's BODY: the sequence of named entries
 * ending in TAG_End, with no root tag byte and no root name. That is the one
 * shape both eras share — 1.13 writes the root with an (empty) name, 1.20.2+
 * writes it nameless — so the codec adds or removes exactly that difference and
 * nothing else has to be rewritten.
 */
public record SemanticItem(
    String identifier,
    int count,
    byte[] tag
) {
  /** The single canonical empty stack. Both eras render this as "nothing here". */
  public static final SemanticItem EMPTY = new SemanticItem("minecraft:air", 0, new byte[0]);

  public SemanticItem {
    if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("identifier required");
    if (count < 0) throw new IllegalArgumentException("count must be >= 0");
    if (tag == null) tag = new byte[0];
  }

  public static SemanticItem of(String identifier, int count) {
    return new SemanticItem(identifier, count, new byte[0]);
  }

  public boolean isEmpty() {
    return count <= 0 || "minecraft:air".equals(identifier);
  }

  public boolean hasTag() {
    return tag.length > 0;
  }

  /** Durability consumed, from the item's {@code Damage} tag. Absent when undamaged. */
  public Optional<Integer> damage() {
    return ItemNbt.intValue(tag, "Damage");
  }

  /** The custom display name, as the JSON text component both eras store. */
  public Optional<String> displayName() {
    return ItemNbt.displayName(tag);
  }

  /** Enchantments as {@code (identifier, level)} pairs; 1.13 and 1.20.4 agree on this form. */
  public List<ItemNbt.Enchantment> enchantments() {
    return ItemNbt.enchantments(tag);
  }

  @Override public String toString() {
    if (isEmpty()) return "empty";
    return identifier + " x" + count + (hasTag() ? " +nbt(" + tag.length + "B)" : "");
  }

  @Override public boolean equals(Object other) {
    return other instanceof SemanticItem item
        && identifier.equals(item.identifier)
        && count == item.count
        && java.util.Arrays.equals(tag, item.tag);
  }

  @Override public int hashCode() {
    return identifier.hashCode() * 31 + count * 31 + java.util.Arrays.hashCode(tag);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import gg.tame.conduit.protocol.MinecraftInput;

/**
 * Validates known-packs packet bodies before allocating entry lists.
 * Count is checked first; each string is length-bounded.
 */
public final class KnownPacksValidator {
  public static final int DEFAULT_LIMIT = 1024;
  public static final int MAX_STRING_BYTES = 256;

  private KnownPacksValidator() {}

  public record KnownPack(String namespace, String id, String version) {}

  /** A list longer than the configured limit: the one refusal an operator can fix with a setting. */
  public static final class TooManyPacks extends IOException {
    public final int count;
    public final int limit;
    TooManyPacks(int count, int limit) {
      super("known-packs count exceeds limit (" + count + " > " + limit + ")");
      this.count = count;
      this.limit = limit;
    }
  }

  public record Result(int count, java.util.List<KnownPack> packs) {
    public Result {
      packs = java.util.List.copyOf(packs);
    }
  }

  public static Result validate(byte[] body, int maxPacks) throws IOException {
    if (body == null) throw new IOException("known-packs body is null");
    if (maxPacks < 1 || maxPacks > 16_384) throw new IOException("known-packs limit out of bounds");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      int count = MinecraftInput.varInt(input);
      if (count < 0) throw new IOException("known-packs count is negative");
      if (count > maxPacks) throw new TooManyPacks(count, maxPacks);
      java.util.ArrayList<KnownPack> packs = new java.util.ArrayList<>(Math.min(count, 64));
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < count; i++) {
        String namespace = MinecraftInput.string(input, MAX_STRING_BYTES);
        String id = MinecraftInput.string(input, MAX_STRING_BYTES);
        String version = MinecraftInput.string(input, MAX_STRING_BYTES);
        validateIdentifier(namespace, "namespace");
        validateIdentifier(id, "id");
        if (version.isBlank() || version.length() > MAX_STRING_BYTES) {
          throw new IOException("invalid known-packs version");
        }
        String key = namespace + '\0' + id + '\0' + version;
        if (!seen.add(key)) {
          // Pathological duplicates are rejected; rare legitimate dupes should not appear.
          throw new IOException("duplicate known-packs entry");
        }
        packs.add(new KnownPack(namespace, id, version));
      }
      if (input.available() != 0) throw new IOException("known-packs trailing data");
      return new Result(count, packs);
    }
  }

  private static void validateIdentifier(String value, String label) throws IOException {
    if (value == null || value.isBlank() || value.length() > MAX_STRING_BYTES) {
      throw new IOException("invalid known-packs " + label);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x1F || c == 0x7F) throw new IOException("invalid known-packs " + label + " character");
    }
  }
}

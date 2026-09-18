// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

/** Named Minecraft release mapped to a protocol number. Catalog ≠ codec. */
public record MinecraftRelease(String name, int protocol, ProtocolFamily family, boolean modernProgram) {
  public MinecraftRelease {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("release name required");
  }

  public boolean legacyOutOfScope() {
    return !modernProgram;
  }
}

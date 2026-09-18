// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.brand;

import gg.tame.conduit.Conduit;

/** Rewrites only the Minecraft server-brand plugin message. */
public final class ServerBrand {
  public static final String CHANNEL = "minecraft:brand";
  private ServerBrand() {}
  public static String display(String backendBrand) {
    if (backendBrand == null || backendBrand.isBlank()) return Conduit.BRAND;
    String trimmed = backendBrand.strip();
    if (trimmed.equals(Conduit.BRAND) || trimmed.endsWith(" (" + Conduit.BRAND + ")")) return trimmed;
    return trimmed + " (" + Conduit.BRAND + ")";
  }
}

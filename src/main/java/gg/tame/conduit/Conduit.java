// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit;

/** Proxy identity shown to players. Not a backend address or secret. */
public final class Conduit {
  public static final String VERSION = "0.9.6";
  public static final String BRAND = "Conduit";
  /** Native plugin API version. Independent of Minecraft protocol versions. */
  public static final int API_VERSION = 1;
  private Conduit() {}
}

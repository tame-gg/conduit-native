package gg.tame.conduit;

/** Proxy identity shown to players. Not a backend address or secret. */
public final class Conduit {
  public static final String VERSION = "0.9.0";
  public static final String BRAND = "Conduit";
  /** Native plugin API version. Independent of Minecraft protocol versions. */
  public static final int API_VERSION = 1;
  private Conduit() {}
}

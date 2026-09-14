package gg.tame.conduit.protocol;

/**
 * Optional protocol translation trace. Enable with {@code -Dconduit.trace=true}.
 * Never logs secrets, keys, or auth tokens.
 */
public final class ProtocolTrace {
  private static final boolean ENABLED = Boolean.getBoolean("conduit.trace");

  private ProtocolTrace() {}

  public static boolean enabled() { return ENABLED; }

  public static void packet(String stage, int protocol, ConnectionState state, PacketDirection direction,
                            int id, String semantic) {
    if (!ENABLED) return;
    System.out.println("TRACE " + stage + " " + protocol + " " + direction.name() + " " + state
        + " 0x" + Integer.toHexString(id) + (semantic == null || semantic.isBlank() ? "" : " " + semantic));
  }

  public static void translation(int sourceProtocol, int targetProtocol, ConnectionState state,
                                 PacketDirection direction, int sourceId, int targetId, String semantic) {
    if (!ENABLED) return;
    System.out.println("TRACE TRANSLATE " + sourceProtocol + "→" + targetProtocol
        + " " + direction.name() + " " + state
        + " 0x" + Integer.toHexString(sourceId) + "→0x" + Integer.toHexString(targetId)
        + (semantic == null ? "" : " " + semantic));
  }

  public static void note(String message) {
    if (!ENABLED) return;
    System.out.println("TRACE " + message);
  }
}

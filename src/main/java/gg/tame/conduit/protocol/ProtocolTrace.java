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
    translation(sourceProtocol, state, direction, sourceId, semantic, "Translator " + sourceProtocol + "→" + targetProtocol,
        targetProtocol, state, targetId);
  }

  public static void translation(int sourceProtocol, ConnectionState sourceState, PacketDirection direction,
                                 int sourceId, String semantic, String translator,
                                 int targetProtocol, ConnectionState targetState, int targetId) {
    if (!ENABLED) return;
    System.out.println("TRACE " + sourceProtocol + " " + direction.name() + " " + sourceState
        + " 0x" + Integer.toHexString(sourceId)
        + (semantic == null || semantic.isBlank() ? "" : " " + semantic)
        + " → " + translator
        + " → " + targetProtocol + " " + targetState + " 0x" + Integer.toHexString(targetId));
  }

  public static void note(String message) {
    if (!ENABLED) return;
    System.out.println("TRACE " + message);
  }

  /**
   * Renders up to {@code limit} bytes of a packet body as hex for diagnosis of an unidentified
   * packet id. Used to identify packets from real wire traces instead of guessing their layout.
   */
  public static String hex(byte[] data, int limit) {
    if (data == null) return "<null>";
    int count = Math.min(data.length, limit);
    StringBuilder text = new StringBuilder(count * 3 + 16);
    for (int index = 0; index < count; index++) {
      if (index > 0) text.append(' ');
      int value = data[index] & 0xFF;
      if (value < 0x10) text.append('0');
      text.append(Integer.toHexString(value));
    }
    if (data.length > count) text.append(" ... (").append(data.length).append(" bytes)");
    return text.toString();
  }
}

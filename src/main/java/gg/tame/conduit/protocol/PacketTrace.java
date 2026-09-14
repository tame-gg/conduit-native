package gg.tame.conduit.protocol;

/** Debug traces for login/switch. Never logs payload contents (secrets, skins, chat). */
public final class PacketTrace {
  private static final boolean ENABLED = Boolean.getBoolean("conduit.trace");
  private PacketTrace() {}
  public static void packet(String where, ConnectionState state, PacketDirection direction, ProtocolDefinition protocol, byte[] packet) {
    if (!ENABLED) return;
    try {
      int id = PlayPackets.packetId(packet);
      String name = name(protocol, state, direction, id);
      System.out.println(where + " " + protocol.version().displayName() + " " + state + " " + direction + " id=" + id + " " + name + " len=" + packet.length);
    } catch (Exception ignored) { }
  }
  private static String name(ProtocolDefinition protocol, ConnectionState state, PacketDirection direction, int id) {
    for (PacketKind kind : PacketKind.values()) {
      if (protocol.is(state, direction, id, kind)) return kind.name();
    }
    return "unknown";
  }
}

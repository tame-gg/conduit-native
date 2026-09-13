package gg.tame.conduit.protocol;

/** Protocol identities are values, not scattered numeric constants. */
public record ProtocolVersion(int number, String displayName) {
  public static final ProtocolVersion MINECRAFT_1_20_4 = new ProtocolVersion(765, "1.20.4");
}

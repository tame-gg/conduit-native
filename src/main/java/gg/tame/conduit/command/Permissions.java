package gg.tame.conduit.command;

/** Permission nodes for native commands. The current stub may grant all of them. */
public final class Permissions {
  private Permissions() {}
  public static final String SERVER_USE = "conduit.server";
  public static final String SERVER_SEND = "conduit.server.send";
  public static final String SERVER_SEND_OTHERS = "conduit.server.send.player";
  public static final String SERVER_SEND_MASS = "conduit.server.send.mass";
  public static final String CONDUIT_INFO = "conduit.info";
}

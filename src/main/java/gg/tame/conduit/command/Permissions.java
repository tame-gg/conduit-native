package gg.tame.conduit.command;

/** Permission nodes for native commands. Default provider may grant all of them. */
public final class Permissions {
  private Permissions() {}
  public static final String SERVER_USE = "conduit.server";
  public static final String SERVER_SEND = "conduit.server.send";
  public static final String SERVER_SEND_OTHERS = "conduit.server.send.player";
  public static final String SERVER_SEND_MASS = "conduit.server.send.mass";
  public static final String CONDUIT_INFO = "conduit.info";
  public static final String CONDUIT_ADMIN = "conduit.admin";
  public static final String PLUGINS = "conduit.command.plugins";
  public static final String GLIST = "conduit.command.glist";
  public static final String FIND = "conduit.command.find";
  public static final String ALERT = "conduit.command.alert";
  public static final String PING = "conduit.command.ping";
  public static final String HUB = "conduit.command.hub";
  public static final String GKICK = "conduit.command.gkick";
  public static final String PLIST = "conduit.command.plist";
  public static final String DUMP = "conduit.command.dump";
  public static final String HEAP = "conduit.command.heap";
  public static final String RELOAD = "conduit.command.reload";
  public static final String UPTIME = "conduit.command.uptime";
}

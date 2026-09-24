// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.command;

/**
 * Permission nodes for native commands.
 *
 * <p>{@code /server}, the {@code /<server>} shortcuts, {@code /hub}, {@code /ping} and
 * {@code /conduit help} have none: every connected player may use them. Each administrative
 * command, and each {@code /conduit} subcommand, has a node of its own, so granting one never
 * grants another. {@link #CONDUIT_ADMIN} is the one node that stands for all of them. Conduit's
 * default provider grants none of the {@code conduit.} nodes to a player; the console holds every
 * node.
 */
public final class Permissions {
  private Permissions() {}
  public static final String SEND = "conduit.command.send";
  public static final String CONDUIT_ADMIN = "conduit.admin";
  public static final String INFO = "conduit.command.info";
  public static final String SERVERS = "conduit.command.servers";
  public static final String METRICS = "conduit.command.metrics";
  public static final String PLUGINS = "conduit.command.plugins";
  public static final String GLIST = "conduit.command.glist";
  public static final String FIND = "conduit.command.find";
  public static final String ALERT = "conduit.command.alert";
  public static final String GKICK = "conduit.command.gkick";
  public static final String PLIST = "conduit.command.plist";
  public static final String DUMP = "conduit.command.dump";
  public static final String HEAP = "conduit.command.heap";
  public static final String RELOAD = "conduit.command.reload";
  public static final String UPTIME = "conduit.command.uptime";
  public static final String MAINTENANCE = "conduit.command.maintenance";
  public static final String MAINTENANCE_BYPASS = "conduit.maintenance.bypass";
  public static final String DRAIN = "conduit.command.drain";
  public static final String DRAIN_BYPASS = "conduit.drain.bypass";
  public static final String HEALTH = "conduit.command.health";
  public static final String DOCTOR = "conduit.command.doctor";
  public static final String DIAGNOSTICS = "conduit.command.diagnostics";
  public static final String ATTACK = "conduit.command.attack";
  public static final String CACHE = "conduit.command.cache";
  public static final String GBAN = "conduit.command.gban";
  public static final String GALTS = "conduit.command.galts";
  public static final String GMUTE = "conduit.command.gmute";
  public static final String GWARN = "conduit.command.gwarn";
  public static final String GWHITELIST = "conduit.command.gwhitelist";
  /** Held by players no other player may kick or ban, whatever that player may do. */
  public static final String PUNISH_EXEMPT = "conduit.punish.exempt";
  /** Told when anyone is kicked, banned or unbanned, for staff who watch without the power to. */
  public static final String NOTIFY_MODERATION = "conduit.notify.moderation";
  /** Held by the people who must still get in when the whitelist is on, such as the staff turning it on. */
  public static final String WHITELIST_BYPASS = "conduit.whitelist.bypass";

  /** Every node above, found rather than listed, so a node added later is never left out of it. */
  public static java.util.List<String> all() {
    java.util.List<String> nodes = new java.util.ArrayList<>();
    for (java.lang.reflect.Field field : Permissions.class.getFields()) {
      int modifiers = field.getModifiers();
      if (field.getType() != String.class || !java.lang.reflect.Modifier.isStatic(modifiers)
          || !java.lang.reflect.Modifier.isFinal(modifiers)) continue;
      try { nodes.add((String) field.get(null)); } catch (IllegalAccessException unreadable) { }
    }
    return nodes;
  }

  /**
   * Whether {@code source} holds {@code node}. {@link #CONDUIT_ADMIN} stands for every Conduit node,
   * and for nothing of a plugin's -- but only where the node itself was left unsaid: a provider that
   * denies {@code node} outright is obeyed, so admin plus one explicit {@code false} refuses that one
   * command. A provider with nothing but yes and no never denies outright, and behaves as before.
   */
  public static boolean allows(gg.tame.conduit.api.permission.PermissionSubject source, String node) {
    Boolean explicit = source.permissionValue(node);
    if (explicit != null) return explicit;
    return node.startsWith("conduit.") && source.hasPermission(CONDUIT_ADMIN);
  }

  /** The same question asked of a provider directly, for a caller holding one it read once. */
  public static boolean allows(gg.tame.conduit.api.permission.PermissionProvider provider,
      gg.tame.conduit.api.permission.PermissionSubject subject, String node) {
    Boolean explicit = provider.permissionValue(subject, node);
    if (explicit != null) return explicit;
    return node.startsWith("conduit.") && provider.hasPermission(subject, CONDUIT_ADMIN);
  }
}

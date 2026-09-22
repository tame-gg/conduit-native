// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.command.Permissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A permissions plugin is asked about every Conduit node, once, so it can offer them.
 *
 * <p>LuckPerms' editor suggests only the nodes it has seen checked. conduit.punish.exempt was checked
 * only when someone was kicked, so it never appeared there and could not be found to grant.
 */
public final class PermissionDiscoveryTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    everyNodeIsShownToThePermissionsPlugin();
    System.out.println("PermissionDiscoveryTests OK");
  }

  /** Records every node it is asked about, for anyone, and grants nothing. */
  private static final class Recorder implements PermissionProvider {
    final Set<String> asked = ConcurrentHashMap.newKeySet();
    @Override public boolean hasPermission(gg.tame.conduit.api.permission.PermissionSubject subject, String node) {
      if (subject instanceof Player) asked.add(node);
      return false;
    }
  }

  private static void everyNodeIsShownToThePermissionsPlugin() throws Exception {
    List<String> nodes = Permissions.all();
    require(nodes.contains(Permissions.PUNISH_EXEMPT) && nodes.contains(Permissions.NOTIFY_MODERATION)
        && nodes.contains(Permissions.GBAN) && nodes.contains(Permissions.CONDUIT_ADMIN), "every node is found, got " + nodes);
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      // Installed with nobody online: the first player to log in is the one it is asked through.
      Recorder first = new Recorder();
      proxy.runtime.setPermissionProvider(new NativeApiTests.TestPlugin("perms"), first);
      try (NativeApiTests.Client player = NativeApiTests.Client.join(proxy.port(), "player")) {
        require(waitFor(() -> first.asked.containsAll(nodes), 10_000),
            "a plugin installed before anyone joined is asked about every node, got " + first.asked);
        // A plugin installed later is asked at the next login, and the first is not asked again.
        Recorder second = new Recorder();
        proxy.runtime.setPermissionProvider(new NativeApiTests.TestPlugin("perms-again"), second);
        try (NativeApiTests.Client next = NativeApiTests.Client.join(proxy.port(), "next")) {
          require(waitFor(() -> second.asked.containsAll(nodes), 10_000),
              "a plugin installed later is asked about every node at the next login, got " + second.asked);
        }
      }
    }
  }
}

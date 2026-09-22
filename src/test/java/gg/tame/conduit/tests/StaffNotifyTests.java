// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.CHAT_OUT;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.text;

import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.command.Permissions;
import java.util.List;
import java.util.function.Predicate;

/**
 * Staff online are told when someone is kicked, banned or unbanned: those who may kick or ban, and
 * those given {@code conduit.notify.moderation} to watch. Nobody else is, and the one who did it has
 * their own confirmation instead.
 */
public final class StaffNotifyTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    staffAreToldOfKicksBansAndUnbans();
    System.out.println("StaffNotifyTests OK");
  }

  private static void staffAreToldOfKicksBansAndUnbans() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client staff = NativeApiTests.Client.join(proxy.port(), "staff");
           NativeApiTests.Client watcher = NativeApiTests.Client.join(proxy.port(), "watcher");
           NativeApiTests.Client guest = NativeApiTests.Client.join(proxy.port(), "guest");
           NativeApiTests.Client target = NativeApiTests.Client.join(proxy.port(), "target")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 4), "all joined");
        PermissionProvider provider = (subject, node) -> subject instanceof Player player && switch (player.username()) {
          case "staff" -> node.equals(Permissions.GKICK) || node.equals(Permissions.GBAN);
          case "watcher" -> node.equals(Permissions.NOTIFY_MODERATION);
          default -> false;
        };
        proxy.runtime.setPermissionProvider(new NativeApiTests.TestPlugin("perms"), provider);
        Player staffPlayer = proxy.runtime.player("staff").orElseThrow();

        proxy.runtime.commands().execute(staffPlayer, "gkick target being rude");
        require(watcher.await(alert("staff kicked target: being rude")), "the watcher is told of the kick");
        proxy.runtime.commands().execute(staffPlayer, "gban Offender 1h alt account");
        require(watcher.await(alert("staff banned Offender for")), "and of the ban");
        require(watcher.await(said("alt account")), "with its reason");
        proxy.runtime.commands().execute(staffPlayer, "gunban Offender");
        require(watcher.await(alert("staff unbanned Offender")), "and of the unban");

        require(guest.received(said("[Staff]")).isEmpty(), "a player who is not staff is told nothing");
        require(staff.received(said("[Staff]")).isEmpty(), "and the one who did it has their confirmation, not the alert");
      }
    }
  }

  /** A staff alert carrying this text; the chat JSON holds "[Staff] " and the line as separate parts. */
  private static Predicate<byte[]> alert(String fragment) {
    return packet -> id(packet) == CHAT_OUT && text(packet).contains("[Staff] ") && text(packet).contains(fragment);
  }

  private static Predicate<byte[]> said(String fragment) {
    return packet -> id(packet) == CHAT_OUT && text(packet).contains(fragment);
  }
}

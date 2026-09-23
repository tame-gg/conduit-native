// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.permission.PermissionProvider;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.PermissionSettings;
import gg.tame.conduit.permission.DefaultPermissionProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Staff without a permissions plugin, and staff against each other.
 *
 * <p>With no LuckPerms, the operators named in {@code [permissions]} run Conduit's admin commands
 * in-game and nobody else does. And a player who may kick or ban may not do it to another player who
 * holds that same power, or {@code conduit.punish.exempt}; the console always may.
 */
public final class StaffPermissionTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    operatorsAreReadFromTheConfiguration();
    operatorsHoldConduitsNodesAndNothingElse();
    staffCannotKickOrBanEachOther();
    System.out.println("StaffPermissionTests OK");
  }

  private static void operatorsAreReadFromTheConfiguration() throws Exception {
    Path config = TempFiles.file("conduit", ".toml");
    Files.writeString(config, """
        [listener]
        host = "127.0.0.1"
        port = 25565
        max-frame-bytes = 1048576
        [forwarding]
        mode = "none"
        [servers.lobby]
        host = "127.0.0.1"
        port = 25566
        [routing]
        initial = ["lobby"]
        fallback = ["lobby"]
        [permissions]
        operators = ["Boss", "00000000-0000-0000-0000-00000000000a"]
        """);
    PermissionSettings settings = ConfigurationLoader.load(config).ops().permissions();
    require(settings.isOperator("boss", null), "a name is matched without regard to case");
    require(settings.isOperator("Renamed", java.util.UUID.fromString("00000000-0000-0000-0000-00000000000a")), "a UUID is matched");
    require(!settings.isOperator("guest", java.util.UUID.randomUUID()), "anyone else is not an operator");
  }

  private static void operatorsHoldConduitsNodesAndNothingElse() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client boss = NativeApiTests.Client.join(proxy.port(), "Boss");
           NativeApiTests.Client guest = NativeApiTests.Client.join(proxy.port(), "guest")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 2), "both joined");
        var provider = new DefaultPermissionProvider(() -> new PermissionSettings(Set.of("boss")));
        Player bossPlayer = proxy.runtime.player("Boss").orElseThrow();
        Player guestPlayer = proxy.runtime.player("guest").orElseThrow();
        require(provider.hasPermission(bossPlayer, Permissions.GBAN), "an operator may ban");
        require(provider.hasPermission(bossPlayer, Permissions.RELOAD), "and reload");
        require(!provider.hasPermission(bossPlayer, "luckperms.editor"), "but holds no other plugin's node");
        require(!provider.hasPermission(guestPlayer, Permissions.GKICK), "and anyone else holds nothing");
        require(!new DefaultPermissionProvider().hasPermission(bossPlayer, Permissions.GBAN), "with no operators, nobody is one");
      }
    }
  }

  private static void staffCannotKickOrBanEachOther() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client staff = NativeApiTests.Client.join(proxy.port(), "staff");
           NativeApiTests.Client mod = NativeApiTests.Client.join(proxy.port(), "mod");
           NativeApiTests.Client vip = NativeApiTests.Client.join(proxy.port(), "vip");
           NativeApiTests.Client guest = NativeApiTests.Client.join(proxy.port(), "guest")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 4), "all joined");
        PermissionProvider provider = (subject, node) -> subject instanceof Player player && switch (player.username()) {
          case "staff", "mod" -> node.equals(Permissions.GKICK) || node.equals(Permissions.GBAN);
          case "vip" -> node.equals(Permissions.PUNISH_EXEMPT);
          default -> false;
        };
        proxy.runtime.setPermissionProvider(new NativeApiTests.TestPlugin("perms"), provider);
        Player staffPlayer = proxy.runtime.player("staff").orElseThrow();

        proxy.runtime.commands().execute(staffPlayer, "gkick mod");
        proxy.runtime.commands().execute(staffPlayer, "gban mod");
        proxy.runtime.commands().execute(staffPlayer, "gkick vip");
        proxy.runtime.commands().execute(staffPlayer, "gkick guest");
        require(waitFor(() -> proxy.runtime.player("guest").isEmpty(), 10_000), "staff may kick a player without the power");
        require(proxy.runtime.player("mod").isPresent(), "but not another player who may kick");
        require(proxy.runtime.bans().find("mod", null, null).isEmpty(), "nor ban another who may ban");
        require(proxy.runtime.player("vip").isPresent(), "nor kick a player holding conduit.punish.exempt");

        // Offline, the provider cannot be asked about vip; what they held when they left answers.
        vip.close();
        require(waitFor(() -> proxy.runtime.player("vip").isEmpty(), 10_000), "vip left");
        proxy.runtime.commands().execute(staffPlayer, "gban vip");
        require(proxy.runtime.bans().find("vip", null, null).isEmpty(), "nor ban an exempt player who is offline");
        proxy.runtime.commands().execute(staffPlayer, "gban guest2");
        require(proxy.runtime.bans().find("guest2", null, null).isPresent(), "but may ban an offline player without the power");
        proxy.runtime.commands().execute(proxy.runtime.console(), "gban vip");
        require(proxy.runtime.bans().find("vip", null, null).isPresent(), "the console may ban anyone offline");

        proxy.runtime.commands().execute(proxy.runtime.console(), "gkick mod");
        require(waitFor(() -> proxy.runtime.player("mod").isEmpty(), 10_000), "the console may kick anyone");
      }
    }
  }
}

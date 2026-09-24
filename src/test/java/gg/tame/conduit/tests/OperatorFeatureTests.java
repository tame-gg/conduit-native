// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.plugin.PluginStore;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.config.PermissionSettings;
import gg.tame.conduit.config.StatusSettings;
import gg.tame.conduit.ops.AddressHistory;
import gg.tame.conduit.permission.FilePermissionProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Address history for /galts, the plugin key-value store, permissions.toml, and per-host status. */
public final class OperatorFeatureTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    addressHistoryFindsAlts();
    pluginStoreSurvivesReopen();
    permissionsFileGivesGroups();
    perHostStatusIsRead();
    mutesAreKeptAndExpire();
    maxPlayersIsReadAndCounts();
    aMutedPlayersChatNeverReachesTheBackend();
    System.out.println("OperatorFeatureTests OK");
  }

  private static void addressHistoryFindsAlts() throws Exception {
    Path directory = TempFiles.dir("address-history");
    AddressHistory history = new AddressHistory(directory);
    UUID steve = UUID.randomUUID(), alt = UUID.randomUUID(), stranger = UUID.randomUUID();
    require(history.record(steve, "Steve", "203.0.113.5"), "a first pairing is new");
    require(!history.record(steve, "Steve", "203.0.113.5"), "the same pairing again is not");
    history.record(steve, "Steve", "198.51.100.9");
    history.record(alt, "SneakyAlt", "198.51.100.9");
    history.record(stranger, "Stranger", "192.0.2.1");
    List<AddressHistory.Seen> alts = history.alts("steve");
    require(alts.size() == 1 && alts.getFirst().account().equals(alt), "the account sharing an address is the one alt");
    require(history.alts("Stranger").isEmpty(), "an address nobody else used has no alts");
    // A rename keeps the account tied to its addresses.
    history.record(alt, "Renamed", "192.0.2.77");
    require(new AddressHistory(directory).alts("Steve").getFirst().username().equals("Renamed"),
        "after a restart the alt is listed under its newest name");
  }

  private static void pluginStoreSurvivesReopen() throws Exception {
    Path data = TempFiles.dir("plugin-store");
    PluginStore store = PluginStore.in(data);
    store.put("last-run", "2026-09-22");
    store.put("count", "3");
    require(store.get("count").orElseThrow().equals("3"), "a value reads back");
    require(PluginStore.in(data) == store, "one store per directory");
    store.remove("count");
    require(store.get("count").isEmpty(), "a removed key is gone");
    require(Files.readString(data.resolve("store.properties")).contains("last-run=2026-09-22"), "and the file on disk has the rest");
    require(store.keys().equals(Set.of("last-run")), "keys lists what is there");
  }

  private static void permissionsFileGivesGroups() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client mod = NativeApiTests.Client.join(proxy.port(), "Moddy");
           NativeApiTests.Client guest = NativeApiTests.Client.join(proxy.port(), "guest")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 2), "both joined");
        Player modPlayer = proxy.runtime.player("Moddy").orElseThrow();
        Player guestPlayer = proxy.runtime.player("guest").orElseThrow();
        Path directory = TempFiles.dir("permissions-file");
        Files.writeString(directory.resolve(FilePermissionProvider.FILE), """
            # everyone
            [groups.default]
            permissions = ["conduit.command.find"]

            [groups.mod]
            inherits = ["default"]
            permissions = ["conduit.command.gkick", "-conduit.command.gban", "conduit.admin"]

            [users]
            "moddy" = ["mod"]
            """);
        FilePermissionProvider provider = new FilePermissionProvider(() -> new PermissionSettings(Set.of("guest")));
        provider.reload(directory);
        require(provider.hasPermission(guestPlayer, Permissions.FIND), "default applies to everyone");
        require(!provider.hasPermission(guestPlayer, Permissions.GKICK), "and grants nothing more");
        require(!provider.hasPermission(guestPlayer, Permissions.RELOAD), "the operators list is not consulted once a file exists");
        require(provider.hasPermission(modPlayer, Permissions.GKICK), "a group grants its nodes");
        require(provider.hasPermission(modPlayer, Permissions.FIND), "and inherits its parent's");
        require(provider.hasPermission(modPlayer, Permissions.RELOAD), "conduit.admin stands for every conduit node");
        require(Boolean.FALSE.equals(provider.permissionValue(modPlayer, Permissions.GBAN)), "a -node is denied outright, even under admin");
        require(provider.manages(modPlayer) && !provider.manages(guestPlayer), "a listed user is managed; a default-only one is not");

        // No file: the operators list answers as before.
        FilePermissionProvider fallback = new FilePermissionProvider(() -> new PermissionSettings(Set.of("guest")));
        fallback.reload(TempFiles.dir("no-permissions-file"));
        require(fallback.hasPermission(guestPlayer, Permissions.GBAN) && !fallback.hasPermission(modPlayer, Permissions.GBAN),
            "without a file the operators list decides");
        // A broken file keeps the previous one in force.
        Files.writeString(directory.resolve(FilePermissionProvider.FILE), "[groups.mod]\ninherits = [\"missing\"]\n");
        provider.reload(directory);
        require(provider.hasPermission(modPlayer, Permissions.GKICK), "a file that fails to parse changes nothing");
      }
    }
  }

  private static void perHostStatusIsRead() throws Exception {
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
        [status]
        motd = "Network"
        [status.host."PvP.Example.com"]
        motd = "&cArena"
        """);
    StatusSettings status = ConfigurationLoader.load(config).status();
    require(status.forHost("pvp.example.com.").isPresent(), "the host is matched without case or the trailing dot");
    require(status.forHost("pvp.example.com").orElseThrow().motd().isPresent(), "and carries its MOTD");
    require(status.forHost("pvp.example.com").orElseThrow().favicon().isEmpty(), "with no favicon of its own");
    require(status.forHost("lobby.example.com").isEmpty(), "another host keeps the network's");
  }

  private static void mutesAreKeptAndExpire() throws Exception {
    Path directory = TempFiles.dir("mutes");
    gg.tame.conduit.ops.Mutes mutes = new gg.tame.conduit.ops.Mutes(directory);
    UUID loud = UUID.randomUUID();
    mutes.mute(loud, "Loud", "spam	with a tab", "Op", gg.tame.conduit.ops.BanList.PERMANENT);
    mutes.mute(UUID.randomUUID(), "Brief", "cooling off", "Op", System.currentTimeMillis() - 1);
    require(mutes.find(loud, null).isPresent(), "a mute is found by account");
    require(mutes.find(null, "loud").isPresent(), "and by name, whatever the case");
    require(mutes.find(null, "Brief").isEmpty(), "an expired mute is not a mute");
    gg.tame.conduit.ops.Mutes restarted = new gg.tame.conduit.ops.Mutes(directory);
    require(restarted.find(loud, null).orElseThrow().reason().equals("spam	with a tab"), "the reason survives a restart, tab and all");
    require(restarted.unmute("LOUD"), "unmute by name lifts it");
    require(restarted.find(loud, null).isEmpty() && !restarted.unmute("Loud"), "and it is gone");
  }

  private static void maxPlayersIsReadAndCounts() throws Exception {
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
        [servers.arena]
        host = "127.0.0.1"
        port = 25567
        max-players = 2
        [routing]
        initial = ["lobby"]
        fallback = ["lobby"]
        """);
    var servers = ConfigurationLoader.load(config).backends();
    var lobby = servers.stream().filter(s -> s.name().equals("lobby")).findFirst().orElseThrow();
    var arena = servers.stream().filter(s -> s.name().equals("arena")).findFirst().orElseThrow();
    require(lobby.maxPlayers() == 0 && !lobby.full(1_000), "no limit by default");
    require(arena.maxPlayers() == 2 && !arena.full(1) && arena.full(2), "a limit is full at its count");
  }

  private static void aMutedPlayersChatNeverReachesTheBackend() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "Chatty")) {
        require(proxy.recorder.await(gg.tame.conduit.api.event.player.PlayerServerConnectedEvent.class, 1), "joined");
        Player chatty = proxy.runtime.player("Chatty").orElseThrow();
        client.send(NativeApiTests.chat("hello"));
        require(NativeApiTests.waitFor(() -> lobby.received(packet -> NativeApiTests.chatText(packet).equals("hello")).size() == 1, 10_000),
            "an unmuted line reaches the backend");
        proxy.runtime.mutes().mute(chatty.uniqueId(), "Chatty", "spam", "Op", gg.tame.conduit.ops.BanList.PERMANENT);
        client.send(NativeApiTests.chat("still here?"));
        require(client.await(packet -> NativeApiTests.text(packet).contains("You are muted")), "the player is told they are muted");
        require(lobby.received(packet -> NativeApiTests.chatText(packet).equals("still here?")).isEmpty(), "and the line was withheld");
        client.send(NativeApiTests.chat("/server lobby"));
        require(NativeApiTests.waitFor(() -> !lobby.received(packet -> NativeApiTests.chatText(packet).startsWith("/")).isEmpty()
            || client.received(packet -> NativeApiTests.text(packet).contains("lobby")).size() > 0, 10_000), "a command still goes through");
        proxy.runtime.mutes().unmute("chatty");
        client.send(NativeApiTests.chat("back"));
        require(NativeApiTests.waitFor(() -> !lobby.received(packet -> NativeApiTests.chatText(packet).equals("back")).isEmpty(), 10_000),
            "after the unmute chat flows again");
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

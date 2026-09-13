package gg.tame.conduit.tests;

import gg.tame.conduit.command.CommandManager;
import gg.tame.conduit.command.CommandSource;
import gg.tame.conduit.command.CoreCommands;
import gg.tame.conduit.command.Permissions;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.IdentityTranslator;
import gg.tame.conduit.protocol.ProtocolCompatibility;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.protocol.Translators;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.session.PlayerManager;
import gg.tame.conduit.session.TrackedPlayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Phase7Tests {
  static void run() throws Exception {
    playerManagerIndex();
    sendCommands();
    protocolCompatibility();
  }
  private static void playerManagerIndex() {
    PlayerManager manager = new PlayerManager();
    FakePlayer kyle = new FakePlayer("Kyle", "lobby", UUID.fromString("00000000-0000-0000-0000-000000000001"));
    FakePlayer steve = new FakePlayer("Steve", "survival", UUID.fromString("00000000-0000-0000-0000-000000000002"));
    manager.add(kyle);
    manager.add(steve);
    require(manager.get(kyle.uniqueId()).orElseThrow() == kyle, "uuid lookup");
    require(manager.getByUsername("kyle").orElseThrow() == kyle, "case-insensitive username");
    require(manager.byServer("lobby").equals(List.of(kyle)), "by server");
    require(manager.all().size() == 2, "all");
    manager.remove(kyle);
    require(manager.getByUsername("Kyle").isEmpty(), "removed");
    require(manager.byServer("lobby").isEmpty(), "lobby empty after remove");
  }
  private static void sendCommands() throws Exception {
    Path config = Files.createTempFile("conduit", ".toml");
    Files.writeString(config, "[listener]\nhost=\"127.0.0.1\"\nport=25565\nmax-frame-bytes=64\n[forwarding]\nmode=\"none\"\n[servers.lobby]\nhost=\"127.0.0.1\"\nport=1\n[servers.survival]\nhost=\"127.0.0.1\"\nport=2\n[servers.minigames]\nhost=\"127.0.0.1\"\nport=3\n[routing]\ninitial=[\"lobby\"]\nfallback=[\"lobby\"]\n");
    ServerRegistry registry = new ServerRegistry(ConfigurationLoader.load(config));
    PlayerManager players = new PlayerManager();
    CommandManager commands = new CommandManager();
    CoreCommands.register(commands, registry, players);
    FakePlayer kyle = new FakePlayer("Kyle", "lobby", UUID.fromString("00000000-0000-0000-0000-000000000001"));
    FakePlayer steve = new FakePlayer("Steve", "lobby", UUID.fromString("00000000-0000-0000-0000-000000000002"));
    FakePlayer alex = new FakePlayer("Alex", "lobby", UUID.fromString("00000000-0000-0000-0000-000000000003"));
    alex.failTransfer = true;
    players.add(kyle);
    players.add(steve);
    players.add(alex);
    AdminSource admin = new AdminSource("Notch", "minigames", Set.of(Permissions.SERVER_SEND, Permissions.SERVER_SEND_OTHERS, Permissions.SERVER_SEND_MASS, Permissions.SERVER_USE, Permissions.CONDUIT_INFO));
    commands.dispatch(admin, "/send current survival");
    require(admin.messages.stream().anyMatch(line -> line.contains("only be used by a player")), "current from non-player");
    commands.dispatch(kyle, "/send current survival");
    require(kyle.backend.equals("survival"), "send current");
    require(kyle.transfers.equals(List.of("survival")), "current used transfer");
    commands.dispatch(admin, "/send kyle lobby");
    require(kyle.backend.equals("lobby"), "send player case-insensitive");
    commands.dispatch(admin, "/send Kyle lobby");
    require(admin.messages.stream().anyMatch(line -> line.equals("Kyle is already connected to lobby.")), "already connected");
    commands.dispatch(admin, "/send missing survival");
    require(admin.messages.stream().anyMatch(line -> line.equals("Player missing is not online.")), "unknown player");
    commands.dispatch(admin, "/send lobby2 survival");
    require(admin.messages.stream().anyMatch(line -> line.equals("Unknown server: lobby2")), "unknown source");
    commands.dispatch(admin, "/send lobby survival2");
    require(admin.messages.stream().anyMatch(line -> line.equals("Unknown server: survival2")), "unknown dest");
    AdminSource spectator = new AdminSource("Spec", "lobby", Set.of(Permissions.SERVER_SEND));
    commands.dispatch(spectator, "/send Steve survival");
    require(spectator.messages.stream().anyMatch(line -> line.contains("permission")), "others permission");
    require(steve.backend.equals("lobby"), "steve not moved without permission");
    commands.dispatch(admin, "/send lobby survival");
    require(admin.messages.stream().anyMatch(line -> line.equals("Sending players from lobby to survival...")), "mass start");
    require(steve.backend.equals("survival"), "mass moved steve");
    require(alex.backend.equals("lobby"), "failed mass stays");
    require(admin.messages.stream().anyMatch(line -> line.equals("Sent 2 players to survival.")), "mass count");
    require(admin.messages.stream().anyMatch(line -> line.equals("1 player could not be moved.")), "partial failure");
    players.remove(steve);
    players.remove(alex);
    players.remove(kyle);
    FakePlayer lonely = new FakePlayer("Lonely", "minigames", UUID.fromString("00000000-0000-0000-0000-000000000009"));
    players.add(lonely);
    commands.dispatch(admin, "/send lobby survival");
    require(admin.messages.stream().anyMatch(line -> line.equals("No players are connected to lobby.")), "empty source");
    List<String> first = commands.tabComplete(admin, "/send ");
    require(first.contains("current") && first.contains("lobby") && first.contains("Lonely"), "send first tab");
    List<String> dests = commands.tabComplete(admin, "/send current ");
    require(dests.contains("lobby") && dests.contains("survival") && dests.contains("minigames"), "send current tab");
    dests = commands.tabComplete(admin, "/send lobby ");
    require(dests.contains("survival") && dests.contains("lobby"), "send server tab");
  }
  private static void protocolCompatibility() {
    require(ProtocolCompatibility.between(765, 765) == TranslationSupport.DIRECT, "1.20.4 direct");
    require(ProtocolCompatibility.between(763, 763) == TranslationSupport.DIRECT, "1.20.1 direct");
    require(ProtocolCompatibility.between(765, 763) == TranslationSupport.UNSUPPORTED, "no fake translation");
    require(ProtocolCompatibility.between(5, 765) == TranslationSupport.UNSUPPORTED, "1.7.10 not claimed");
    require(Translators.forPair(765, 765) == IdentityTranslator.INSTANCE, "identity");
    byte[] packet = {1, 2, 3};
    require(IdentityTranslator.INSTANCE.clientToBackend(ConnectionState.PLAY, packet) == packet, "no copy identity");
    try { Translators.forPair(5, 765); throw new AssertionError("translator claimed"); }
    catch (IllegalArgumentException expected) { }
    try { gg.tame.conduit.protocol.ProtocolDefinition.forVersion(5); throw new AssertionError("1.7.10 codec claimed"); }
    catch (IllegalArgumentException expected) { }
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  private static final class FakePlayer implements TrackedPlayer, CommandSource {
    private final String username;
    private final UUID id;
    private String backend;
    private boolean failTransfer;
    private final List<String> transfers = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private FakePlayer(String username, String backend, UUID id) { this.username = username; this.backend = backend; this.id = id; }
    @Override public UUID uniqueId() { return id; }
    @Override public String username() { return username; }
    @Override public String currentBackend() { return backend; }
    @Override public boolean transferTo(String serverName) {
      if (failTransfer) return false;
      transfers.add(serverName);
      backend = serverName;
      return true;
    }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMessage(String message) { messages.add(message); }
  }
  private static final class AdminSource implements CommandSource {
    private final String username;
    private final String backend;
    private final Set<String> permissions;
    private final List<String> messages = new ArrayList<>();
    private AdminSource(String username, String backend, Set<String> permissions) {
      this.username = username; this.backend = backend; this.permissions = permissions;
    }
    @Override public String username() { return username; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void sendMessage(String message) { messages.add(message); }
    @Override public String currentBackend() { return backend; }
  }
}

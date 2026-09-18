// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.P47;
import static gg.tame.conduit.tests.NativeApiTests.id;
import static gg.tame.conduit.tests.NativeApiTests.packet;
import static gg.tame.conduit.tests.NativeApiTests.waitFor;

import gg.tame.conduit.api.event.player.PlayerPostLoginEvent;
import gg.tame.conduit.api.event.player.PlayerTabCompleteEvent;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.tests.NativeApiTests.Backend;
import gg.tame.conduit.tests.NativeApiTests.Client;
import gg.tame.conduit.tests.NativeApiTests.Fixture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * PlayerTabCompleteEvent and Velocity's TabCompleteEvent: a scripted 1.8 client presses Tab, a scripted
 * 1.8 backend answers, and what the client is sent is the backend's answer as listeners left it.
 */
public final class TabCompleteEventTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    listenersChangeTheBackendsAnswer();
    velocityPluginsChangeTheBackendsAnswer();
    System.out.println("TabCompleteEventTests OK");
  }

  private static final int TAB_IN = P47.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TAB_COMPLETE_REQUEST);
  private static final int TAB_OUT = P47.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_COMPLETE);

  private static void listenersChangeTheBackendsAnswer() throws Exception {
    try (Backend lobby = new Backend("lobby"); Fixture fixture = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      fixture.recorder.hook = event -> {
        if (!(event instanceof PlayerTabCompleteEvent tab)) return;
        if (tab.partialMessage().equals("hel")) { tab.suggestions().remove("help"); tab.suggestions().add("hey"); }
        if (tab.partialMessage().equals("nul")) tab.suggestions().add(null);
      };
      try (Client client = Client.join(fixture.port(), "Tabber")) {
        require(fixture.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
        require(answered(client, lobby, "hel", List.of("hello", "help")).equals(List.of("hello", "hey")), "a listener removed one and added one");
        PlayerTabCompleteEvent first = fixture.recorder.of(PlayerTabCompleteEvent.class).getFirst();
        require(first.player().username().equals("Tabber") && first.partialMessage().equals("hel"), "the player and what they typed");

        // A command name: Conduit's own matching commands are already in the list listeners see.
        List<String> names = answered(client, lobby, "/se", List.of("/seed"));
        require(names.size() == 3 && names.getFirst().equals("/seed") && names.containsAll(List.of("/send", "/server")),
            "the backend's names, then Conduit's: " + names);
        require(fixture.recorder.of(PlayerTabCompleteEvent.class).get(1).suggestions().equals(names), "the event saw the merged list");

        // Left alone, the backend's packet goes to the client as the backend wrote it.
        byte[] reply = reply(List.of("abcd", "abcd"));
        byte[] untouched = exchange(client, lobby, "abc", reply);
        require(java.util.Arrays.equals(untouched, reply), "an unchanged answer is the backend's own bytes");

        require(answered(client, lobby, "nul", List.of("null")).equals(List.of("null")), "a listener's null is dropped");

        // Conduit answers the arguments of its own commands itself: no backend reply, no event.
        int events = fixture.recorder.of(PlayerTabCompleteEvent.class).size();
        client.send(request("/server "));
        require(client.await(packet -> id(packet) == TAB_OUT && matchesOf(packet).equals(List.of("lobby"))), "Conduit answered /server");
        require(fixture.recorder.of(PlayerTabCompleteEvent.class).size() == events, "and fired nothing for it");
        require(lobby.received(packet -> id(packet) == TAB_IN && text(packet).equals("/server ")).isEmpty(), "nor asked the backend");
      }
    }
  }

  private static final String TABV = """
      package tabv;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.player.TabCompleteEvent;
      import com.velocitypowered.api.plugin.Plugin;

      @Plugin(id = "tabv", name = "TabV", version = "1")
      public final class TabV {
        @SuppressWarnings("unchecked")
        static void signal(String value) { ((java.util.Queue<String>) System.getProperties().get("tab.test.signals")).add(value); }
        @Subscribe public void tab(TabCompleteEvent event) {
          signal("tab:" + event.getPlayer().getUsername() + ":" + event.getPartialMessage() + ":" + event.getSuggestions());
          if (event.getPartialMessage().equals("hel")) {
            event.getSuggestions().remove("help");
            event.getSuggestions().add("velocity");
          }
        }
      }
      """;

  private static void velocityPluginsChangeTheBackendsAnswer() throws Exception {
    Queue<String> signals = new ConcurrentLinkedQueue<>();
    System.getProperties().put("tab.test.signals", signals);
    Path plugins = LoginFlowTests.compiledPlugin("tabv.TabV", TABV);
    try (Backend lobby = new Backend("lobby");
         Fixture fixture = new Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"), plugins,
             gg.tame.conduit.config.AuthenticationSettings.offline(), null)) {
      require(waitFor(() -> fixture.runtime.plugins().plugin("tabv").isPresent(), 10_000), "tabv enabled");
      try (Client client = Client.join(fixture.port(), "VTabber")) {
        require(fixture.recorder.await(PlayerPostLoginEvent.class, 1), "joined");
        require(answered(client, lobby, "hel", List.of("hello", "help")).equals(List.of("hello", "velocity")), "the plugin's list is what the client got");
        require(signals.contains("tab:VTabber:hel:[hello, help]"), "getPlayer, getPartialMessage, getSuggestions: " + signals);
        require(answered(client, lobby, "wor", List.of("world")).equals(List.of("world")), "a list left alone");
      }
    } finally {
      System.getProperties().remove("tab.test.signals");
    }
  }

  /** Sends Tab after {@code typed}, has the backend answer with {@code matches}, and returns what the client got. */
  private static List<String> answered(Client client, Backend backend, String typed, List<String> matches) throws Exception {
    return matchesOf(exchange(client, backend, typed, reply(matches)));
  }
  private static byte[] exchange(Client client, Backend backend, String typed, byte[] reply) throws Exception {
    int before = client.received(packet -> id(packet) == TAB_OUT).size();
    client.send(request(typed));
    require(backend.await(packet -> id(packet) == TAB_IN && text(packet).equals(typed)), "the backend was asked to complete " + typed);
    backend.send(reply);
    require(waitFor(() -> client.received(packet -> id(packet) == TAB_OUT).size() > before, 10_000), "an answer for " + typed);
    return client.received(packet -> id(packet) == TAB_OUT).get(before);
  }
  /** A 1.8 Tab-Complete request: the text, then no looked-at block. */
  private static byte[] request(String typed) throws IOException {
    return packet(TAB_IN, output -> { MinecraftOutput.string(output, typed); output.writeBoolean(false); });
  }
  private static byte[] reply(List<String> matches) throws IOException {
    return packet(TAB_OUT, output -> {
      MinecraftOutput.varInt(output, matches.size());
      for (String match : matches) MinecraftOutput.string(output, match);
    });
  }
  private static String text(byte[] packet) {
    try { return PlayPackets.tabRequest(P47, packet).text(); } catch (IOException unreadable) { return ""; }
  }
  private static List<String> matchesOf(byte[] packet) {
    try { return PlayPackets.legacyTabMatches(packet); } catch (IOException unreadable) { return List.of(); }
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.text.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /gban} on someone already banned says so and leaves the ban that stands, rather than
 * replacing it and reporting a new ban.
 */
public final class BanAlreadyBannedTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aSecondBanIsRefused();
    System.out.println("BanAlreadyBannedTests OK");
  }

  private static final class Staff implements CommandSource {
    final List<String> said = new ArrayList<>();
    @Override public String username() { return "Staff"; }
    @Override public void sendMessage(String message) { said.add(message); }
    @Override public void sendMessage(Text text) { said.add(text.plain()); }
    @Override public boolean hasPermission(String permission) { return true; }
    String last() { return said.isEmpty() ? "" : said.getLast(); }
  }

  private static void aSecondBanIsRefused() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      Staff staff = new Staff();
      var commands = proxy.runtime.commands();

      commands.execute(staff, "gban Griefer griefing spawn");
      require(staff.last().contains("Banned Griefer"), "the first ban is made, said " + staff.last());
      commands.execute(staff, "gban griefer 1h something else");
      require(staff.last().contains("already banned") && staff.last().contains("permanently")
          && staff.last().contains("by Staff") && staff.last().contains("griefing spawn") && staff.last().contains("/gunban"),
          "a second ban says the first stands and how to change it, said " + staff.last());
      var standing = proxy.runtime.bans().find("Griefer", null, null).orElseThrow();
      require(standing.reason().equals("griefing spawn") && standing.permanent(), "and the first ban is left as it was");

      commands.execute(staff, "gban 203.0.113.9 open proxy");
      commands.execute(staff, "gban 203.0.113.9 again");
      require(staff.last().contains("already banned"), "an address banned twice is refused too, said " + staff.last());

      commands.execute(staff, "gunban Griefer");
      commands.execute(staff, "gban Griefer 1h a fresh start");
      require(staff.last().contains("Banned Griefer"), "after an unban the name may be banned again, said " + staff.last());
      require(proxy.runtime.bans().find("Griefer", null, null).orElseThrow().reason().equals("a fresh start"), "with the new reason");
    }
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;

import gg.tame.conduit.api.command.CommandSource;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.ops.BanList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code /gbanlist}: who is banned, why, by whom and for how long, a page at a time. */
public final class BanListCommandTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theListNamesEachBan();
    System.out.println("BanListCommandTests OK");
  }

  /** A staff member who may ban, and keeps what they are told. */
  private static final class Staff implements CommandSource {
    final List<String> said = new ArrayList<>();
    @Override public String username() { return "Staff"; }
    @Override public void sendMessage(String message) { said.add(message); }
    @Override public void sendMessage(Text text) { said.add(text.plain()); }
    @Override public boolean hasPermission(String permission) { return true; }
    String all() { return String.join("\n", said); }
  }

  private static void theListNamesEachBan() throws Exception {
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby), List.of("lobby"), List.of("lobby"))) {
      Staff staff = new Staff();
      proxy.runtime.commands().execute(staff, "gbanlist");
      require(staff.all().contains("Nobody is banned"), "an empty list says so, got " + staff.all());

      BanList bans = proxy.runtime.bans();
      bans.ban(BanList.Kind.NAME, "Griefer", "griefing spawn", "Kyle", BanList.PERMANENT);
      bans.ban(BanList.Kind.ACCOUNT, UUID.randomUUID().toString(), "griefing spawn", "Kyle", BanList.PERMANENT, "Griefer");
      bans.ban(BanList.Kind.NAME, "Spammer", "chat spam", "Mod", System.currentTimeMillis() + 2 * 3_600_000L + 60_000);
      staff.said.clear();
      proxy.runtime.commands().execute(staff, "gbanlist");
      String list = staff.all();
      require(list.contains("Bans (2)"), "the account ban made with a name is not listed twice, got " + list);
      require(list.contains("griefer") && list.contains("griefing spawn") && list.contains("Banned by: Kyle")
          && list.contains("Duration: Permanent"), "a permanent ban names its reason and who made it, got " + list);
      require(list.contains("spammer") && list.contains("Banned by: Mod") && list.contains("Duration: 2h") && list.contains("left"),
          "a temporary ban gives the time left, got " + list);

      for (int index = 0; index < 10; index++) bans.ban(BanList.Kind.NAME, "Extra" + index, "alt", "Kyle", BanList.PERMANENT);
      staff.said.clear();
      proxy.runtime.commands().execute(staff, "gbanlist");
      require(staff.all().contains("page 1/2") && staff.all().contains("Next: /gbanlist 2"), "a long list is paged, got " + staff.all());
      staff.said.clear();
      proxy.runtime.commands().execute(staff, "gbanlist 2");
      require(staff.all().contains("page 2/2") && !staff.all().contains("Next:"), "and the last page says no more, got " + staff.all());
    }
  }
}

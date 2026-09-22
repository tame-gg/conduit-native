// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.ops.BanList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Unbanning a name lifts the account ban {@code /gban} made alongside it.
 *
 * <p>Banning an online player bans their name and their account, so a name change does not get them
 * back in. {@code /gunban <name>} lifted only the name's ban, and the account's went on refusing the
 * player: unbanned, and still banned.
 */
public final class BanUnbanTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    unbanningTheNameLiftsItsAccountBan();
    anAccountBanFromBeforeTheFixIsLiftedToo();
    anUnrelatedAccountBanStays();
    System.out.println("BanUnbanTests OK");
  }

  private static void unbanningTheNameLiftsItsAccountBan() throws Exception {
    Path directory = TempFiles.dir("ban-unban");
    UUID account = UUID.randomUUID();
    BanList bans = new BanList(directory);
    bans.ban(BanList.Kind.NAME, "Staffer", "abuse", "Owner", BanList.PERMANENT);
    bans.ban(BanList.Kind.ACCOUNT, account.toString(), "abuse", "Owner", BanList.PERMANENT, "Staffer");
    require(bans.find("Staffer", account, null).isPresent(), "banned to start with");
    // A restart between the ban and the unban: the link has to survive the file.
    BanList restarted = new BanList(directory);
    require(restarted.pardonAny("staffer"), "the unban finds the ban");
    require(restarted.find("Staffer", account, null).isEmpty(), "and nothing still refuses the player");
    require(new BanList(directory).find("Staffer", account, null).isEmpty(), "after another restart either");
  }

  private static void anAccountBanFromBeforeTheFixIsLiftedToo() throws Exception {
    Path directory = TempFiles.dir("ban-unban-legacy");
    UUID account = UUID.randomUUID();
    // What /gban wrote before bans recorded their name: two lines, a few milliseconds apart.
    Files.writeString(directory.resolve("bans.txt"),
        "NAME\tstaffer\tabuse\tOwner\t1000000\t" + BanList.PERMANENT + "\n"
            + "ACCOUNT\t" + account + "\tabuse\tOwner\t1000003\t" + BanList.PERMANENT + "\n", StandardCharsets.UTF_8);
    BanList bans = new BanList(directory);
    require(bans.find(null, account, null).isPresent(), "the old account ban is in force");
    require(bans.pardonAny("Staffer"), "the unban finds the name");
    require(bans.find("Staffer", account, null).isEmpty(), "and the account ban from the same /gban goes with it");
  }

  private static void anUnrelatedAccountBanStays() throws Exception {
    Path directory = TempFiles.dir("ban-unban-unrelated");
    UUID other = UUID.randomUUID();
    BanList bans = new BanList(directory);
    bans.ban(BanList.Kind.NAME, "Staffer", "abuse", "Owner", BanList.PERMANENT);
    bans.ban(BanList.Kind.ACCOUNT, other.toString(), "cheating", "Admin", BanList.PERMANENT);
    bans.pardonAny("Staffer");
    require(bans.find(null, other, null).isPresent(), "someone else's account ban is left alone");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

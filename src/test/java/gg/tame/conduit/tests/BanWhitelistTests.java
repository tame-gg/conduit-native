// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.ops.BanList;
import gg.tame.conduit.ops.Whitelist;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The two lists an operator edits while the proxy is running: who may not join, and who may.
 *
 * <p>The point of both is that a change is in force at once and survives a restart, so every test
 * here makes the change, asks the same object, and then builds a second one over the same directory
 * to stand in for the restart.
 */
public final class BanWhitelistTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aNameBanIsInForceAtOnceAndSurvivesARestart();
    aBanMatchesWhateverItWasMadeOn();
    aTemporaryBanStopsMatchingWhenItExpires();
    aDurationIsToldFromAReason();
    banningTheSameNameTwiceReplacesIt();
    aPardonLiftsIt();
    addressesAreToldFromNames();
    aReasonSurvivesTheFormat();
    theWhitelistIsInstantAndSurvivesARestart();
    theWhitelistIsOffWhenItIsOff();
    aBypassGetsInWithoutBeingOnIt();
    System.out.println("BanWhitelistTests OK");
  }

  private static void aNameBanIsInForceAtOnceAndSurvivesARestart() throws Exception {
    Path directory = temporary();
    BanList bans = new BanList(directory);
    require(bans.find("Steve", null, null).isEmpty(), "nobody is banned to start with");
    bans.ban(BanList.Kind.NAME, "Steve", "griefing", "Op", BanList.PERMANENT);
    require(bans.find("Steve", null, null).isPresent(), "the ban is in force with nothing reloaded");
    // A name ban is the operator's word, however they capitalised it.
    require(bans.find("steve", null, null).isPresent(), "matched case-insensitively");
    require(bans.find("STEVE", null, null).isPresent(), "matched case-insensitively either way");
    require(bans.find("Alex", null, null).isEmpty(), "and only the person named");

    BanList afterRestart = new BanList(directory);
    require(afterRestart.find("Steve", null, null).isPresent(), "the ban survives a restart");
    require(afterRestart.find("Steve", null, null).orElseThrow().reason().equals("griefing"), "with its reason");
    require(afterRestart.find("Steve", null, null).orElseThrow().permanent(), "and is still permanent");
  }

  private static void aBanMatchesWhateverItWasMadeOn() throws Exception {
    BanList bans = new BanList(temporary());
    UUID account = UUID.randomUUID();
    bans.ban(BanList.Kind.ACCOUNT, account.toString(), "alt account", "Op", BanList.PERMANENT);
    bans.ban(BanList.Kind.ADDRESS, "198.51.100.7", "open proxy", "Op", BanList.PERMANENT);

    // An account ban follows the player through a name change, which is the whole reason for it.
    require(bans.find("AnyNewName", account, "203.0.113.1").isPresent(), "the account is caught whatever the name");
    require(bans.find("Someone", UUID.randomUUID(), "198.51.100.7").isPresent(), "the address is caught whoever it is");
    require(bans.find("Someone", UUID.randomUUID(), "203.0.113.1").isEmpty(), "and an unrelated login is not");
  }

  private static void aTemporaryBanStopsMatchingWhenItExpires() throws Exception {
    Path directory = temporary();
    BanList bans = new BanList(directory);
    bans.ban(BanList.Kind.NAME, "Briefly", "cooling off", "Op", System.currentTimeMillis() + 60_000);
    require(bans.find("Briefly", null, null).isPresent(), "a temporary ban is a ban");
    require(!bans.find("Briefly", null, null).orElseThrow().permanent(), "and knows it is not permanent");
    require(bans.find("Briefly", null, null).orElseThrow().remaining(System.currentTimeMillis()).isPresent(),
        "and can say how long is left");

    bans.ban(BanList.Kind.NAME, "Expired", "over", "Op", System.currentTimeMillis() - 1);
    require(bans.find("Expired", null, null).isEmpty(), "a ban whose time has passed stops matching");
    require(bans.active().stream().noneMatch(entry -> entry.value().equals("expired")), "and is not listed");
    // And is gone from the file rather than read back in next start.
    require(new BanList(directory).find("Expired", null, null).isEmpty(), "and does not come back on a restart");
  }

  private static void aDurationIsToldFromAReason() {
    require(BanList.parseExpiry("30m").isPresent(), "30m is a duration");
    require(BanList.parseExpiry("2h").isPresent(), "2h is a duration");
    require(BanList.parseExpiry("7d").isPresent(), "7d is a duration");
    require(BanList.parseExpiry("4w").isPresent(), "4w is a duration");
    require(BanList.parseExpiry("perm").orElseThrow() == BanList.PERMANENT, "perm is permanent");
    require(BanList.parseExpiry("forever").orElseThrow() == BanList.PERMANENT, "and so is forever");
    // The words an operator actually types as a reason must not be read as a length.
    require(BanList.parseExpiry("griefing").isEmpty(), "a reason is not a duration");
    require(BanList.parseExpiry("spam").isEmpty(), "nor is a one-word reason ending in a letter");
    require(BanList.parseExpiry("0d").isEmpty(), "nor is a zero length");
    require(BanList.parseExpiry("-5h").isEmpty(), "nor a negative one");
    require(BanList.parseExpiry("").isEmpty(), "nor nothing at all");
    // A length past the end of time is a permanent ban rather than an overflow into the past.
    require(BanList.parseExpiry("999999999999w").orElseThrow() == BanList.PERMANENT, "an absurd length is permanent");
  }

  private static void banningTheSameNameTwiceReplacesIt() throws Exception {
    BanList bans = new BanList(temporary());
    bans.ban(BanList.Kind.NAME, "Twice", "first", "Op", BanList.PERMANENT);
    bans.ban(BanList.Kind.NAME, "twice", "second", "Op", BanList.PERMANENT);
    require(bans.active().size() == 1, "one entry, not two, got " + bans.active());
    require(bans.find("Twice", null, null).orElseThrow().reason().equals("second"), "the newer reason wins");
  }

  private static void aPardonLiftsIt() throws Exception {
    Path directory = temporary();
    BanList bans = new BanList(directory);
    bans.ban(BanList.Kind.NAME, "Sorry", "a mistake", "Op", BanList.PERMANENT);
    require(bans.pardonAny("Sorry"), "the pardon reports that it did something");
    require(bans.find("Sorry", null, null).isEmpty(), "and the ban is gone at once");
    require(!bans.pardonAny("Sorry"), "pardoning again reports that there was nothing to do");
    require(new BanList(directory).find("Sorry", null, null).isEmpty(), "and it stays gone across a restart");
  }

  private static void addressesAreToldFromNames() {
    require(BanList.looksLikeAddress("198.51.100.7"), "an IPv4 address");
    require(BanList.looksLikeAddress("2001:db8::1"), "an IPv6 address");
    require(!BanList.looksLikeAddress("Steve"), "a player name is not an address");
    require(!BanList.looksLikeAddress("Notch_2"), "nor is one with an underscore and a digit");
  }

  private static void aReasonSurvivesTheFormat() throws Exception {
    Path directory = temporary();
    BanList bans = new BanList(directory);
    // The file is tab-separated, so a reason holding a tab or a newline must not split the record.
    String awkward = "said\tsomething\nrude \\ and slashed";
    bans.ban(BanList.Kind.NAME, "Awkward", awkward, "Op", BanList.PERMANENT);
    BanList afterRestart = new BanList(directory);
    require(afterRestart.find("Awkward", null, null).isPresent(), "the record still reads back as one record");
    require(afterRestart.find("Awkward", null, null).orElseThrow().reason().equals(awkward),
        "with its reason intact, got " + afterRestart.find("Awkward", null, null).orElseThrow().reason());
  }

  private static void theWhitelistIsInstantAndSurvivesARestart() throws Exception {
    Path directory = temporary();
    Whitelist whitelist = new Whitelist(directory);
    require(!whitelist.isEnabled(), "off until someone turns it on");
    require(whitelist.allows("Anyone", false), "and while it is off anyone may join");

    require(whitelist.setEnabled(true), "turning it on reports that it changed");
    require(!whitelist.setEnabled(true), "turning it on again reports that it did not");
    require(!whitelist.allows("Anyone", false), "with it on and nobody on it, nobody may join");

    require(whitelist.add("Steve"), "adding reports that it changed");
    require(!whitelist.add("steve"), "adding the same name in another case does not");
    require(whitelist.allows("Steve", false), "and the player may join at once, with nothing reloaded");
    require(whitelist.allows("STEVE", false), "whatever case they type it in");
    require(!whitelist.allows("Alex", false), "while everyone else still may not");

    Whitelist afterRestart = new Whitelist(directory);
    require(afterRestart.isEnabled(), "still on after a restart");
    require(afterRestart.allows("Steve", false), "with its list intact");
    require(afterRestart.names().equals(List.of("Steve")), "as typed, got " + afterRestart.names());

    require(afterRestart.remove("steve"), "removing reports that it changed");
    require(!afterRestart.allows("Steve", false), "and they are shut out at once");
    require(!afterRestart.remove("steve"), "removing again reports that there was nothing to do");
  }

  private static void theWhitelistIsOffWhenItIsOff() throws Exception {
    Path directory = temporary();
    Whitelist whitelist = new Whitelist(directory);
    whitelist.add("OnlyThem");
    // A list with people on it but switched off must let everyone in: the switch is the policy.
    require(whitelist.allows("Nobody", false), "a list that is off is not a policy");
    whitelist.setEnabled(true);
    require(!whitelist.allows("Nobody", false), "and one that is on is");
    require(whitelist.clear() == 1, "clearing says how many it took off");
    require(whitelist.names().isEmpty(), "and the list is empty");
    require(whitelist.isEnabled(), "clearing the list does not turn it off");
  }

  private static void aBypassGetsInWithoutBeingOnIt() throws Exception {
    Whitelist whitelist = new Whitelist(temporary());
    whitelist.setEnabled(true);
    require(!whitelist.allows("Staff", false), "not on the list and no bypass");
    require(whitelist.allows("Staff", true), "the bypass is how the staff who turned it on stay in");
  }

  private static Path temporary() throws Exception {
    Path directory = Files.createTempDirectory("conduit-bans");
    directory.toFile().deleteOnExit();
    return directory;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

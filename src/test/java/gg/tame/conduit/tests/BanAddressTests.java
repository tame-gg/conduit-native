// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.ops.BanList;
import java.nio.file.Path;

/**
 * An address ban matches the address a connection reports, whatever form the operator typed it in.
 *
 * <p>{@code /gban ::1} was recorded as typed, and a login from that address reported itself as
 * {@code 0:0:0:0:0:0:0:1}: the operator was told the ban was made, and it never fired.
 */
public final class BanAddressTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    Path directory = TempFiles.dir("ban-address");
    BanList bans = new BanList(directory);
    bans.ban(BanList.Kind.ADDRESS, "::1", "abuse", "Owner", BanList.PERMANENT);
    bans.ban(BanList.Kind.ADDRESS, "2001:DB8::5", "abuse", "Owner", BanList.PERMANENT);
    bans.ban(BanList.Kind.ADDRESS, "10.0.0.7", "abuse", "Owner", BanList.PERMANENT);
    require(bans.find(null, null, "0:0:0:0:0:0:0:1").isPresent(), "a short IPv6 literal bans the expanded address");
    require(bans.find(null, null, "2001:db8:0:0:0:0:0:5").isPresent(), "case and zero runs do not matter");
    require(bans.find(null, null, "fe80:0:0:0:0:0:0:1%eth0").isEmpty(), "an unrelated address is not caught");
    require(bans.find(null, null, "10.0.0.7").isPresent(), "IPv4 still matches as typed");
    require(bans.pardon(BanList.Kind.ADDRESS, "0:0:0:0:0:0:0:1"), "pardon accepts either form");
    require(bans.find(null, null, "::1").isEmpty(), "and the ban is gone");
    require(new BanList(directory).find(null, null, "2001:db8::5").isPresent(), "the canonical form survives a restart");
    cidrRanges(directory);
    System.out.println("BanAddressTests OK");
  }

  private static void cidrRanges(Path directory) throws Exception {
    BanList bans = new BanList(directory);
    BanList.Entry range = bans.ban(BanList.Kind.ADDRESS, "198.51.100.77/24", "open proxies", "Owner", BanList.PERMANENT);
    require(range.value().equals("198.51.100.0/24"), "a range is stored as its network address: " + range.value());
    require(bans.find(null, null, "198.51.100.1").isPresent(), "the first address in the range is caught");
    require(bans.find(null, null, "198.51.100.254").isPresent(), "and the last");
    require(bans.find(null, null, "198.51.101.1").isEmpty(), "the next network is not");
    require(bans.find(null, null, "0:0:0:0:0:0:0:1").isEmpty(), "an IPv4 range says nothing about IPv6");
    require(bans.find(null, null, "198.51.100.0/24").isPresent(), "the range itself is found, for the already-banned check");
    require(bans.find(null, null, "198.51.100.0/16").isEmpty(), "but a different range is not");
    bans.ban(BanList.Kind.ADDRESS, "2001:DB8:abcd::/48", "open proxies", "Owner", BanList.PERMANENT);
    require(bans.find(null, null, "2001:db8:abcd:1:2:3:4:5").isPresent(), "an IPv6 range matches inside its prefix");
    require(bans.find(null, null, "2001:db8:abce:0:0:0:0:1").isEmpty(), "and not one bit outside it");
    require(bans.pardon(BanList.Kind.ADDRESS, "198.51.100.9/24"), "a pardon finds the range under any address in it");
    require(bans.find(null, null, "198.51.100.1").isEmpty(), "and lifts it");
    require(BanList.validAddress("10.0.0.0/8") && BanList.validAddress("::/0") && BanList.validAddress("10.0.0.1"), "valid forms");
    require(!BanList.validAddress("10.0.0.0/33") && !BanList.validAddress("10.0.0.0/") && !BanList.validAddress("::1/129")
        && !BanList.validAddress("example.com"), "invalid forms are refused before they reach the list");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.VelocityCompatTests.require;

import gg.tame.conduit.config.VersionGateSettings;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.version.SupportedVersions;
import gg.tame.conduit.version.VersionGate;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The {@code version.name} in a status answer, which is what a server list site, an uptime monitor
 * or a query bot reads to say what a server is.
 *
 * <p>It used to be the version of whichever packet table answered the ping, so anything asking with
 * an old protocol number was told "Conduit 1.8.9" -- one release out of the range Conduit admits.
 */
public final class PingVersionNameTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    withNothingGatedItIsTheWholeRange();
    aGatedVersionIsTheAnswer();
    theRangeEndsAreTheOnesThatCanActuallyJoin();
    aSpanOfVersionsReadsAsASpan();
    aGateThatIsOffAdvertisesNothingItDoesNotEnforce();
    System.out.println("PingVersionNameTests OK");
  }

  /** Nothing configured: the oldest release that can log in, through the newest that can be carried. */
  private static void withNothingGatedItIsTheWholeRange() {
    String name = SupportedVersions.versionName(new VersionGate(VersionGateSettings.defaults()), 47);
    require(name.startsWith("Conduit "), "the brand, got " + name);
    require(name.equals("Conduit " + SupportedVersions.range()), "then the range, got " + name);
    require(!name.equals("Conduit 1.8.9"), "not the table that answered, got " + name);
    require(SupportedVersions.range().startsWith("1.7.6"), "the floor is the oldest release that can join, got "
        + SupportedVersions.range());
    require(SupportedVersions.range().contains("-"), "and it is a range, got " + SupportedVersions.range());
  }

  /** An operator who allows one version is advertising that version, not a range. */
  private static void aGatedVersionIsTheAnswer() {
    VersionGate gate = new VersionGate(gated(Set.of(754)));
    require(SupportedVersions.versionName(gate, 47).equals("Conduit 1.16.5"),
        "the allowed version, got " + SupportedVersions.versionName(gate, 47));
    // The client that is allowed is told the same thing as the one that is not: what this proxy takes.
    require(SupportedVersions.versionName(gate, 754).equals("Conduit 1.16.5"),
        "for an allowed client too, got " + SupportedVersions.versionName(gate, 754));
    VersionGate two = new VersionGate(gated(Set.of(754, 765)));
    String name = SupportedVersions.versionName(two, 47);
    require(name.contains("1.16.5") && name.contains("1.20.4"), "both allowed versions, got " + name);
    // A gate that is on but gates nothing is not a gate, and the range comes back.
    VersionGate empty = new VersionGate(new VersionGateSettings(true, Set.of(), OptionalInt.empty(), OptionalInt.empty(),
        VersionGateSettings.DEFAULT_PING, VersionGateSettings.defaults().kickMessage(),
        VersionGateSettings.defaults().kickMessageRange(), false));
    require(SupportedVersions.versionName(empty, 47).equals("Conduit " + SupportedVersions.range()),
        "nothing gated is the range, got " + SupportedVersions.versionName(empty, 47));
  }

  /** The ends are the protocols the login path and the translation path really reach. */
  private static void theRangeEndsAreTheOnesThatCanActuallyJoin() {
    int floor = SupportedVersions.floorProtocol();
    int ceiling = SupportedVersions.ceilingProtocol();
    require(ProtocolDefinition.hasCodec(floor), "the floor has a codec, got " + floor);
    require(floor == 5, "which is protocol 5, 1.7.6-1.7.10, got " + floor);
    require(!ProtocolDefinition.hasCodec(floor - 1), "and nothing below it does");
    int newestCodec = ProtocolDefinition.all().keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
    require(ceiling >= newestCodec, "the ceiling is at least the newest codec, got " + ceiling + " for " + newestCodec);
  }

  /** Every release between two bounds is a span, not a list no server list entry could show. */
  private static void aSpanOfVersionsReadsAsASpan() {
    VersionGateSettings defaults = VersionGateSettings.defaults();
    VersionGate span = new VersionGate(new VersionGateSettings(true, Set.of(), OptionalInt.of(765), OptionalInt.of(769),
        VersionGateSettings.DEFAULT_PING, defaults.kickMessage(), defaults.kickMessageRange(), false));
    String name = SupportedVersions.versionName(span, 47);
    require(name.equals("Conduit 1.20.4-1.21.4"), "the two ends and nothing between, got " + name);
    // The kick message still names them all: there the reader is a player being told what to install.
    require(span.kickMessage().contains("1.20.5"), "the kick message still lists them, got " + span.kickMessage());
  }

  /**
   * Rules written with the switch off gate nothing, so the answer is still the whole range: a
   * server list that named the allowed version would be advertising a refusal that never happens.
   */
  private static void aGateThatIsOffAdvertisesNothingItDoesNotEnforce() {
    VersionGateSettings defaults = VersionGateSettings.defaults();
    VersionGate off = new VersionGate(new VersionGateSettings(false, Set.of(769), OptionalInt.empty(), OptionalInt.empty(),
        VersionGateSettings.DEFAULT_PING, defaults.kickMessage(), defaults.kickMessageRange(), false));
    require(off.allows(47), "an old client still joins with the gate off");
    require(SupportedVersions.versionName(off, 47).equals("Conduit " + SupportedVersions.range()),
        "so the range is what is advertised, got " + SupportedVersions.versionName(off, 47));
  }

  private static VersionGateSettings gated(Set<Integer> allowed) {
    VersionGateSettings defaults = VersionGateSettings.defaults();
    return new VersionGateSettings(true, allowed, OptionalInt.empty(), OptionalInt.empty(),
        VersionGateSettings.DEFAULT_PING, defaults.kickMessage(), defaults.kickMessageRange(), false);
  }
}

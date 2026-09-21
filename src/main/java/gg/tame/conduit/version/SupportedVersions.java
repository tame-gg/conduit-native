// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.version;

import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.ProtocolVersion;

/**
 * What Conduit tells the outside world it speaks, as the string in a status answer's
 * {@code version.name}.
 *
 * <p>A client ignores that string unless the protocol number beside it differs from its own, so it
 * is read mostly by the things that ask a server what it is: a server list site, an uptime monitor,
 * a query bot. Those used to be told the version of whichever packet table answered the ping --
 * "Conduit 1.8.9" for anything that pinged with an old protocol number -- which named one release
 * out of the range Conduit actually admits and was wrong for every player on any other.
 *
 * <p>So with nothing configured it is the range: the oldest release that can log in through the
 * newest one that can be carried. The floor is the oldest protocol Conduit has a codec for, since
 * the login path refuses anything below it. The ceiling is the highest release ViaVersion knows,
 * because Via is what carries a client past the newest table Conduit has of its own -- and a Via
 * that has updated itself raises it without a new Conduit release. With translation off it is the
 * newest codec instead, which is all that could then be reached.
 *
 * <p>An operator who has gated the versions is not advertising a range at all: what they allow is
 * the answer, and {@code versions.ping-version-name} already says how to word it.
 */
public final class SupportedVersions {
  private SupportedVersions() {}

  /** The whole {@code version.name} string, brand included, for the ping being answered. */
  public static String versionName(VersionGate gate, int clientProtocol) {
    if (gate != null && gate.isEnabled()) return gate.pingVersionName(clientProtocol);
    return gg.tame.conduit.Conduit.BRAND + " " + range();
  }

  /** The supported range on its own, such as {@code 1.7.6-26.2}. */
  public static String range() {
    String low = release(floorProtocol(), true);
    String high = release(ceilingProtocol(), false);
    return low.equals(high) ? low : low + "-" + high;
  }

  /** Oldest protocol a client may log in with: below it there is no codec and the login is refused. */
  public static int floorProtocol() {
    return ProtocolDefinition.all().keySet().stream().mapToInt(Integer::intValue).min()
        .orElse(ProtocolVersion.MINECRAFT_1_8_9.number());
  }

  /** Newest protocol that can reach a backend: Via's newest release when it is carrying, else Conduit's. */
  public static int ceilingProtocol() {
    int native_ = ProtocolDefinition.all().keySet().stream().mapToInt(Integer::intValue).max()
        .orElse(ProtocolVersion.MINECRAFT_1_8_9.number());
    var viaHighest = gg.tame.conduit.viaversion.ConduitViaSupport.highestKnownProtocol();
    return viaHighest.isPresent() ? Math.max(native_, viaHighest.getAsInt()) : native_;
  }

  /**
   * The releases a pre-flattening protocol number covers, for a proxy running with translation
   * turned off and no Via to ask. The catalog names one release per number, and naming 1.7.10 as
   * the floor would hide the 1.7.6 clients that log in on the same number.
   */
  private static String spans(int protocol) {
    return switch (protocol) {
      case 5 -> "1.7.6-1.7.10";
      case 47 -> "1.8-1.8.9";
      default -> ProtocolVersion.display(protocol);
    };
  }

  /**
   * The release name for a protocol number. Where several releases share one -- protocol 5 is
   * 1.7.6 through 1.7.10 -- {@code first} picks which end of that span to name, so a range reads
   * from the oldest release that can join to the newest that can.
   */
  private static String release(int protocol, boolean first) {
    String name = gg.tame.conduit.viaversion.ConduitViaSupport.releaseName(protocol)
        .orElseGet(() -> spans(protocol));
    int dash = name.indexOf('-');
    if (dash < 0) return name;
    return first ? name.substring(0, dash) : name.substring(dash + 1);
  }
}

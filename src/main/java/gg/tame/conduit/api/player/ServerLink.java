// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.player;

import gg.tame.conduit.api.text.Text;
import java.net.URI;
import java.util.Objects;

/**
 * A link in a 1.21+ client's pause menu: one of the {@link Type}s the client names itself, in its own
 * language, or a label of the plugin's, and the address it opens. Exactly one of {@code type} and
 * {@code label} is set.
 */
public record ServerLink(Type type, Text label, URI url) {
  public ServerLink {
    Objects.requireNonNull(url, "url");
    if ((type == null) == (label == null)) throw new IllegalArgumentException("a link has a type or a label, not both or neither");
  }
  public static ServerLink of(Type type, URI url) { return new ServerLink(Objects.requireNonNull(type, "type"), null, url); }
  public static ServerLink of(Text label, URI url) { return new ServerLink(null, Objects.requireNonNull(label, "label"), url); }

  /** The links the client knows by kind. In the order of their ids on the wire, which is what the ordinal is. */
  public enum Type { BUG_REPORT, COMMUNITY_GUIDELINES, SUPPORT, STATUS, FEEDBACK, COMMUNITY, WEBSITE, FORUMS, NEWS, ANNOUNCEMENTS }
}

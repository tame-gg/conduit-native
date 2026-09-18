// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.diff;

import java.util.List;

/** Structured description of a protocol change between two versions. */
public record ProtocolChange(
    int fromProtocol,
    int toProtocol,
    ChangeKind kind,
    String packetOrArea,
    String detail
) {
  public enum ChangeKind {
    ADDED_PACKET,
    REMOVED_PACKET,
    CHANGED_PACKET,
    UNCHANGED_PACKET,
    SEMANTIC_CHANGE,
    STATE_MODEL,
    CAPABILITY
  }

  public static ProtocolChange of(int from, int to, ChangeKind kind, String area, String detail) {
    return new ProtocolChange(from, to, kind, area, detail);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import java.util.List;

/**
 * One protocol version expressed as a delta against an already-defined base.
 *
 * <p>Adding a Minecraft version to Conduit should cost roughly what the version
 * actually changed. Most releases in the 1.13&ndash;26.2 range shift a handful of
 * play-state packet ids and leave handshake, status and login untouched, so a
 * revision carries only the mappings that moved, were added, or were removed
 * ({@link PacketMapping#removed}). The base table supplies everything else.
 *
 * <p>A revision always records where its numbers came from ({@link #source})
 * and how far they have been validated ({@link #status}). A revision whose
 * deltas are unknown is simply not registered &mdash; Conduit reports the
 * protocol as having no codec rather than silently inheriting a neighbour's
 * table and calling it support.
 */
/*
 * capabilities may be null, meaning "inherit whatever the base version
 * declares". Revisions are constructed while ProtocolDefinition's registry is
 * still being built, so a revision must not reach back into that registry to
 * look its base up; inheritance is resolved by derive() once the base is known.
 */
public record ProtocolRevision(
    ProtocolVersion version,
    int baseProtocol,
    ProtocolCapabilities capabilities,
    CodecStatus status,
    String source,
    List<PacketMapping> deltas
) {

  public ProtocolRevision {
    if (version == null) throw new IllegalArgumentException("revision requires a protocol version");
    if (baseProtocol == version.number()) {
      throw new IllegalArgumentException("protocol " + baseProtocol + " cannot derive from itself");
    }
    if (status == null || status == CodecStatus.NONE) {
      throw new IllegalArgumentException("a registered revision must have a usable codec status");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("revision requires a provenance note for its packet ids");
    }
    deltas = List.copyOf(deltas == null ? List.of() : deltas);
  }

  /** A revision derived from {@code baseProtocol}, changing only the listed mappings. */
  public static ProtocolRevision of(ProtocolVersion version, int baseProtocol,
                                    ProtocolCapabilities capabilities, CodecStatus status,
                                    String source, PacketMapping... deltas) {
    return new ProtocolRevision(version, baseProtocol, capabilities, status, source, List.of(deltas));
  }

  public int protocol() {
    return version.number();
  }
}

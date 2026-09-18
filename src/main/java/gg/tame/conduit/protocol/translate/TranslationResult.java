// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.translate;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.semantic.SemanticPacket;

/** Result of attempting to translate one packet. */
public sealed interface TranslationResult {
  record Translated(SemanticPacket packet) implements TranslationResult {}
  record Dropped(String reason) implements TranslationResult {}
  record Unsupported(String reason) implements TranslationResult {}
  record Passthrough(ConnectionState state, PacketDirection direction, int sourceId, byte[] body) implements TranslationResult {}
}

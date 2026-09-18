// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.semantic;

import java.util.Optional;

/**
 * Chat content independent of signed-chat wire formats.
 * Does not fabricate cryptographic signatures.
 */
public record SemanticChat(
    String plain,
    Optional<String> jsonComponent,
    ChatKind kind,
    boolean signed
) {
  public SemanticChat {
    if (plain == null) plain = "";
    if (jsonComponent == null) jsonComponent = Optional.empty();
    if (kind == null) kind = ChatKind.SYSTEM;
  }

  public static SemanticChat system(String plain) {
    return new SemanticChat(plain, Optional.empty(), ChatKind.SYSTEM, false);
  }

  public static SemanticChat player(String plain) {
    return new SemanticChat(plain, Optional.empty(), ChatKind.PLAYER, false);
  }

  public enum ChatKind { SYSTEM, PLAYER, ACTION_BAR, GAME_INFO }
}

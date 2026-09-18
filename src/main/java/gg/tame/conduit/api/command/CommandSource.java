package gg.tame.conduit.api.command;

import gg.tame.conduit.api.permission.PermissionSubject;
import gg.tame.conduit.api.text.Text;

/**
 * Whoever ran a command: a {@link gg.tame.conduit.api.player.Player}, or the proxy's console
 * ({@link gg.tame.conduit.api.ConduitProxy#console()}). Test {@code instanceof Player} to tell them apart.
 */
public interface CommandSource extends PermissionSubject {
  /** The player's name, or {@code CONSOLE}. */
  String username();
  void sendMessage(String message);
  default void sendMessage(Text text) {
    sendMessage(text == null ? "" : text.plain());
  }
}

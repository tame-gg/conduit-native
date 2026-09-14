package gg.tame.conduit.command;

import gg.tame.conduit.api.text.Text;

public interface CommandSource {
  String username();
  boolean hasPermission(String permission);
  void sendMessage(String message);
  default void sendMessage(Text text) {
    sendMessage(text == null ? "" : text.plain());
  }
  String currentBackend();
}

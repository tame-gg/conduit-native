package gg.tame.conduit.command;

public interface CommandSource {
  String username();
  boolean hasPermission(String permission);
  void sendMessage(String message);
  String currentBackend();
}

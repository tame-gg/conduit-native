package gg.tame.conduit.command;

/** The operator at the proxy's own terminal: every permission, no backend, output to stdout. */
public final class ConsoleCommandSource implements CommandSource {
  @Override public String username() { return "CONSOLE"; }
  @Override public boolean hasPermission(String permission) { return true; }
  @Override public void sendMessage(String message) { System.out.println(message); }
  @Override public String currentBackend() { return ""; }
}

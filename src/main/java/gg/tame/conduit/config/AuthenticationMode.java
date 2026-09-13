package gg.tame.conduit.config;

public enum AuthenticationMode {
  ONLINE, OFFLINE;
  public static AuthenticationMode parse(String raw) {
    return switch (raw) {
      case "online" -> ONLINE;
      case "offline" -> OFFLINE;
      default -> throw new IllegalArgumentException("authentication.mode must be online or offline");
    };
  }
}

package gg.tame.conduit.command;

import gg.tame.conduit.Conduit;
import gg.tame.conduit.routing.ServerMatch;
import gg.tame.conduit.routing.ServerRegistry;
import gg.tame.conduit.session.PlayerSession;
import java.util.ArrayList;
import java.util.List;

public final class CoreCommands {
  private CoreCommands() {}
  public static void register(CommandManager manager, ServerRegistry registry) {
    manager.register(new RegisteredCommand("server", List.of(), "conduit.command.server",
        (source, arguments) -> server(source, registry, arguments),
        (source, arguments) -> complete(registry, arguments)));
    manager.register(new RegisteredCommand("conduit", List.of(), "conduit.command.conduit",
        (source, arguments) -> conduit(source), (source, arguments) -> List.of()));
  }
  private static void server(CommandSource source, ServerRegistry registry, List<String> arguments) {
    if (arguments.isEmpty()) {
      source.sendMessage("Available servers:");
      for (String name : registry.names()) source.sendMessage("- " + name);
      return;
    }
    ServerMatch match = registry.resolve(arguments.getFirst());
    if (match.kind() == ServerMatch.Kind.NONE) {
      source.sendMessage("Unknown server.");
      return;
    }
    if (match.kind() == ServerMatch.Kind.AMBIGUOUS) {
      source.sendMessage("Multiple servers match:");
      for (String name : match.candidates()) source.sendMessage(name);
      return;
    }
    String name = match.server().orElseThrow().name();
    if (name.equalsIgnoreCase(source.currentBackend())) {
      source.sendMessage("You are already connected to " + name + ".");
      return;
    }
    if (!(source instanceof PlayerSession session)) {
      source.sendMessage("Unable to connect to " + name + ".");
      return;
    }
    session.requestSwitch(name);
  }
  private static void conduit(CommandSource source) {
    source.sendMessage("Conduit");
    source.sendMessage("Version: " + Conduit.VERSION);
    source.sendMessage("Backend: " + (source.currentBackend().isBlank() ? "none" : source.currentBackend()));
  }
  private static List<String> complete(ServerRegistry registry, List<String> arguments) {
    if (arguments.size() > 1) return List.of();
    String prefix = arguments.isEmpty() ? "" : arguments.getFirst();
    List<String> names = new ArrayList<>();
    for (String name : registry.names()) {
      if (prefix.isEmpty() || name.toLowerCase().startsWith(prefix.toLowerCase())) names.add(name);
    }
    return names;
  }
}

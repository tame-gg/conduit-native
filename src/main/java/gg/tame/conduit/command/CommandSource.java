package gg.tame.conduit.command;

/** The API's command source plus the backend it is on, which the built-in commands need. */
public interface CommandSource extends gg.tame.conduit.api.command.CommandSource {
  String currentBackend();
}

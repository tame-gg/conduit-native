package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.Component;

/** The proxy's console as a Velocity command source: every permission, messages to Conduit's console. */
final class VelocityConsole implements ConsoleCommandSource, Unsupported.ChatOnly {
  private final gg.tame.conduit.api.command.CommandSource console;
  VelocityConsole(gg.tame.conduit.api.command.CommandSource console) { this.console = console; }
  @Override public Tristate getPermissionValue(String permission) { return Tristate.TRUE; }
  @Override public void deliver(Component message) { console.sendMessage(Texts.toConduit(message)); }
  @Override public String toString() { return "CONSOLE"; }
}

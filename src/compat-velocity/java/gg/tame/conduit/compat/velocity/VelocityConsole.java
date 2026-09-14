package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.permission.Tristate;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;

final class VelocityConsole implements ConsoleCommandSource {
  @Override public Tristate getPermissionValue(String permission) { return Tristate.TRUE; }
  @Override public void sendMessage(Identity source, Component message, MessageType type) {
    System.out.println("[console] " + Texts.plain(message));
  }
}

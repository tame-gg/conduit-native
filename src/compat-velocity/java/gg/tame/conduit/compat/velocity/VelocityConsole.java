// SPDX-License-Identifier: GPL-3.0-or-later
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
  // A terminal has nowhere to show any of these, and Adventure's contract for an audience that
  // cannot is to do nothing. Thrown, an announcement run from the console failed on the console
  // itself before it reached a single player.
  @Override public void sendActionBar(Component message) { }
  @Override public void sendPlayerListHeaderAndFooter(Component header, Component footer) { }
  @Override public void sendPlayerListHeader(Component header) { }
  @Override public void sendPlayerListFooter(Component footer) { }
  @Override public void showTitle(net.kyori.adventure.title.Title title) { }
  @Override public <T> void sendTitlePart(net.kyori.adventure.title.TitlePart<T> part, T value) { }
  @Override public void clearTitle() { }
  @Override public void resetTitle() { }
  @Override public void showBossBar(net.kyori.adventure.bossbar.BossBar bar) { }
  @Override public void hideBossBar(net.kyori.adventure.bossbar.BossBar bar) { }
  @Override public void playSound(net.kyori.adventure.sound.Sound sound) { }
  @Override public void playSound(net.kyori.adventure.sound.Sound sound, double x, double y, double z) { }
  @Override public void playSound(net.kyori.adventure.sound.Sound sound, net.kyori.adventure.sound.Sound.Emitter emitter) { }
  @Override public void stopSound(net.kyori.adventure.sound.SoundStop stop) { }
  @Override public void openBook(net.kyori.adventure.inventory.Book book) { }
  @Override public void sendResourcePacks(net.kyori.adventure.resource.ResourcePackRequest request) { }
  @Override public void removeResourcePacks(Iterable<java.util.UUID> ids) { }
  @Override public void removeResourcePacks(java.util.UUID id, java.util.UUID... others) { }
  @Override public void clearResourcePacks() { }
  @Override public void showDialog(net.kyori.adventure.dialog.DialogLike dialog) { }
  @Override public void closeDialog() { }
}

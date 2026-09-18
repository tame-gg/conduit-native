// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import java.util.UUID;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identified;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;

/** How the adapter refuses what Conduit cannot do: loudly, naming the Velocity API. */
final class Unsupported {
  private Unsupported() {}

  static UnsupportedOperationException api(String api) {
    return new UnsupportedOperationException(api + " is not supported by Conduit's Velocity compatibility layer");
  }

  /**
   * Adventure's Audience methods are no-op defaults, so a plugin showing a title or a boss bar would
   * see nothing happen and no error. Every audience in the adapter is one of these (chat messages
   * reach {@link #deliver}, everything else throws) or a {@link PlayerGroup}.
   */
  interface ChatOnly extends net.kyori.adventure.audience.Audience {
    void deliver(Component message);
    @Override default void sendMessage(Component message) { deliver(message); }
    @Override default void sendMessage(Identified source, Component message) { deliver(message); }
    @Override default void sendMessage(Identity source, Component message) { deliver(message); }
    @Override default void sendMessage(Component message, MessageType type) { deliver(message); }
    @Override default void sendMessage(Identified source, Component message, MessageType type) { deliver(message); }
    @Override default void sendMessage(Identity source, Component message, MessageType type) { deliver(message); }
    @Override default void sendMessage(Component message, ChatType.Bound boundChatType) { deliver(message); }
    @Override default void sendMessage(SignedMessage signedMessage, ChatType.Bound boundChatType) { throw api("Audience.sendMessage(SignedMessage)"); }
    @Override default void deleteMessage(SignedMessage.Signature signature) { throw api("Audience.deleteMessage"); }
    @Override default void sendActionBar(Component message) { throw api("Audience.sendActionBar"); }
    @Override default void sendPlayerListHeaderAndFooter(Component header, Component footer) { throw api("Audience.sendPlayerListHeaderAndFooter"); }
    @Override default void sendPlayerListHeader(Component header) { throw api("Audience.sendPlayerListHeader"); }
    @Override default void sendPlayerListFooter(Component footer) { throw api("Audience.sendPlayerListFooter"); }
    @Override default void showTitle(Title title) { throw api("Audience.showTitle"); }
    @Override default <T> void sendTitlePart(TitlePart<T> part, T value) { throw api("Audience.sendTitlePart"); }
    @Override default void clearTitle() { throw api("Audience.clearTitle"); }
    @Override default void resetTitle() { throw api("Audience.resetTitle"); }
    @Override default void showBossBar(BossBar bar) { throw api("Audience.showBossBar"); }
    @Override default void hideBossBar(BossBar bar) { throw api("Audience.hideBossBar"); }
    @Override default void playSound(Sound sound) { throw api("Audience.playSound"); }
    @Override default void playSound(Sound sound, double x, double y, double z) { throw api("Audience.playSound"); }
    @Override default void playSound(Sound sound, Sound.Emitter emitter) { throw api("Audience.playSound"); }
    @Override default void stopSound(SoundStop stop) { throw api("Audience.stopSound"); }
    @Override default void openBook(Book book) { throw api("Audience.openBook"); }
    @Override default void sendResourcePacks(ResourcePackRequest request) { throw api("Audience.sendResourcePacks"); }
    @Override default void removeResourcePacks(Iterable<UUID> ids) { throw api("Audience.removeResourcePacks"); }
    @Override default void removeResourcePacks(UUID id, UUID... others) { throw api("Audience.removeResourcePacks"); }
    @Override default void clearResourcePacks() { throw api("Audience.clearResourcePacks"); }
    @Override default void showDialog(DialogLike dialog) { throw api("Audience.showDialog"); }
    @Override default void closeDialog() { throw api("Audience.closeDialog"); }
  }

  /**
   * The proxy and a server as audiences: on Velocity they forward to their players, and plugins
   * announce to everyone through them (TitleAnnouncer's "all" target, for one) or walk their
   * {@link #audiences()}. Chat reaches {@link #deliver}, which may add the console; everything else
   * reaches each player, who shows it or refuses it as they would alone.
   */
  interface PlayerGroup extends net.kyori.adventure.audience.ForwardingAudience {
    void deliver(Component message);
    @Override default void sendMessage(Component message) { deliver(message); }
    @Override default void sendMessage(Identified source, Component message) { deliver(message); }
    @Override default void sendMessage(Identity source, Component message) { deliver(message); }
    @Override default void sendMessage(Component message, MessageType type) { deliver(message); }
    @Override default void sendMessage(Identified source, Component message, MessageType type) { deliver(message); }
    @Override default void sendMessage(Identity source, Component message, MessageType type) { deliver(message); }
    @Override default void sendMessage(Component message, ChatType.Bound boundChatType) { deliver(message); }
  }
}

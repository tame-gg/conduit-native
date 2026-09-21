// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

/**
 * How backend plugins may talk to the proxy.
 *
 * <p>{@code bungeeCordChannel} is the {@code BungeeCord} / {@code bungeecord:main} plugin channel,
 * which is the only way a plugin on a Paper or Spigot backend can ask its proxy for anything: to
 * move a player, to count the network, to pass a payload to the same plugin on another server. Hub
 * plugins and backend-side queue plugins are built on it. It is on by default, because a plugin that
 * expects it and does not get it fails silently -- the message is delivered and simply never acted
 * on -- and that is a far worse default than answering.
 *
 * <p>Turn it off for a network where no backend is trusted to move players around: the channel lets
 * any plugin on any backend connect, kick and message anyone. Conduit answers it only for a message
 * that arrived from a backend, never one from a client, so a modded client cannot forge one.
 */
public record MessagingSettings(boolean bungeeCordChannel) {
  public static final boolean DEFAULT_BUNGEECORD_CHANNEL = true;

  public static MessagingSettings defaults() {
    return new MessagingSettings(DEFAULT_BUNGEECORD_CHANNEL);
  }
}

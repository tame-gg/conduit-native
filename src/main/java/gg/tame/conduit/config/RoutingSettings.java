// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

/**
 * What the proxy does with a player whose server stops wanting them.
 *
 * <p>{@code fallbackOnKick} decides the commonest event on a network there is: a backend shutting
 * down. A server being stopped says "Server closed" to everyone on it and then goes, and a proxy
 * that passes that on has thrown every player off the whole network because one server restarted.
 * With this on, they are moved to the next server that will take them and told why they moved; with
 * it off, the backend's own kick screen reaches the player as it did before, which is what to use
 * when a kick is meant to be final and visible.
 *
 * <p>It does not apply when a plugin decided the kick: a {@code PlayerKickedFromServerEvent}
 * listener that asked for a disconnect or a redirect is obeyed either way. This is only the default
 * for a kick nobody handled.
 */
public record RoutingSettings(boolean fallbackOnKick) {
  public static final boolean DEFAULT_FALLBACK_ON_KICK = true;

  public static RoutingSettings defaults() {
    return new RoutingSettings(DEFAULT_FALLBACK_ON_KICK);
  }
}

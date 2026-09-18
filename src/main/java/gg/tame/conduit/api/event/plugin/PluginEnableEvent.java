// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.plugin;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.plugin.Plugin;

/** {@code plugin}'s {@code onEnable} returned. Fired on the thread loading plugins. */
public record PluginEnableEvent(Plugin plugin) implements Event {}

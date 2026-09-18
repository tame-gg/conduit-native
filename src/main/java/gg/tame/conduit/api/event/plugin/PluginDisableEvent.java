package gg.tame.conduit.api.event.plugin;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.plugin.Plugin;

/**
 * {@code plugin} is about to be disabled; its {@code onDisable} runs next, and its own listeners
 * still receive this. Fired on whichever thread disabled it.
 */
public record PluginDisableEvent(Plugin plugin) implements Event {}

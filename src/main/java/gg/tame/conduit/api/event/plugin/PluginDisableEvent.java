package gg.tame.conduit.api.event.plugin;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.plugin.Plugin;

public record PluginDisableEvent(Plugin plugin) implements Event {}

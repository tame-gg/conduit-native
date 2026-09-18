package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;

/**
 * The proxy is stopping: players have been moved or kicked and no new ones are accepted. Every
 * plugin is still enabled and is disabled right after. Fired once, on the thread shutting down,
 * and only if {@link ProxyStartEvent} was.
 */
public record ProxyShutdownEvent(ConduitProxy proxy) implements Event {}

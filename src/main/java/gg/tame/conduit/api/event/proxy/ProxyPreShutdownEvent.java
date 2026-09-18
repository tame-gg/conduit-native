// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;

/**
 * The proxy has begun to stop: it accepts no new players, and every player online is still connected
 * -- the last point at which a plugin can move or message them. The shutdown waits for every listener,
 * on the thread shutting down, so a listener must not wait for anything that waits for the shutdown.
 * Fired once, and only if {@link ProxyStartEvent} was; {@link ProxyShutdownEvent} follows once
 * players have been moved or kicked.
 */
public record ProxyPreShutdownEvent(ConduitProxy proxy) implements Event {}

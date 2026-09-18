// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.server.RegisteredServer;

/**
 * A plugin unregistered {@code server} through {@code ServerManager.unregister}: it can no longer be
 * looked up or routed to. Players already on it stay there. Fired on the thread that unregistered it,
 * after the fact.
 */
public record ServerUnregisteredEvent(RegisteredServer server) implements Event {}

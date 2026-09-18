// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.event.Event;
import gg.tame.conduit.api.server.RegisteredServer;

/**
 * A plugin registered {@code server} through {@code ServerManager.register}, and it can now be looked
 * up and connected to. Not fired for the servers in the configuration, which exist before any plugin
 * loads. Fired on the thread that registered it, after the fact: nothing a listener does changes it.
 */
public record ServerRegisteredEvent(RegisteredServer server) implements Event {}

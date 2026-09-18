// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;

/** Every plugin is enabled and the proxy is about to accept players. Fired once, on the serving thread. */
public record ProxyStartEvent(ConduitProxy proxy) implements Event {}

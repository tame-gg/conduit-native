// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;

/**
 * {@code /conduit reload} read the configuration again and applied it (settings that need a restart
 * are left as they were and reported). Not fired for a reload that failed. Fired on the thread that
 * ran the reload.
 */
public record ProxyReloadEvent(ConduitProxy proxy) implements Event {}

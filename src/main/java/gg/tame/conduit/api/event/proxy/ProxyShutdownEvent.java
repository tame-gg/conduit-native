package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;

public record ProxyShutdownEvent(ConduitProxy proxy) implements Event {}

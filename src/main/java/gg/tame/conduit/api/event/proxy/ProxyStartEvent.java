package gg.tame.conduit.api.event.proxy;

import gg.tame.conduit.api.ConduitProxy;
import gg.tame.conduit.api.event.Event;

public record ProxyStartEvent(ConduitProxy proxy) implements Event {}

package gg.tame.conduit.forwarding;

import gg.tame.conduit.login.PlayerProfile;
import java.net.InetAddress;

public record ForwardingRequest(PlayerProfile player, InetAddress clientAddress, int protocolVersion) { }

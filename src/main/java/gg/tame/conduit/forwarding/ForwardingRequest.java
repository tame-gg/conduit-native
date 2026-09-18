// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.login.PlayerProfile;
import java.net.InetAddress;

public record ForwardingRequest(PlayerProfile player, InetAddress clientAddress, int minecraftProtocolVersion, int forwardingVersion) { }

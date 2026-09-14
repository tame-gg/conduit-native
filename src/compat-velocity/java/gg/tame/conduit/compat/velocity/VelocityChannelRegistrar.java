package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class VelocityChannelRegistrar implements ChannelRegistrar {
  private final Set<String> channels = ConcurrentHashMap.newKeySet();
  @Override public void register(ChannelIdentifier... identifiers) {
    for (ChannelIdentifier identifier : identifiers) channels.add(identifier.getId());
  }
  @Override public void unregister(ChannelIdentifier... identifiers) {
    for (ChannelIdentifier identifier : identifiers) channels.remove(identifier.getId());
  }
  boolean registered(String id) { return channels.contains(id); }
}

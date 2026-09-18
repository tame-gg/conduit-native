package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import java.util.concurrent.ConcurrentHashMap;

/** The channels Velocity plugins listen on: only their messages raise a Velocity PluginMessageEvent. */
final class VelocityChannelRegistrar implements ChannelRegistrar {
  private final ConcurrentHashMap<String, ChannelIdentifier> channels = new ConcurrentHashMap<>();
  @Override public void register(ChannelIdentifier... identifiers) {
    for (ChannelIdentifier identifier : identifiers) channels.put(identifier.getId(), identifier);
  }
  @Override public void unregister(ChannelIdentifier... identifiers) {
    for (ChannelIdentifier identifier : identifiers) channels.remove(identifier.getId());
  }
  /** The identifier a plugin registered for {@code channel}, or null when none did. */
  ChannelIdentifier find(String channel) { return channel == null ? null : channels.get(channel); }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The channels Velocity plugins listen on: only their messages raise a Velocity PluginMessageEvent.
 *
 * <p>Velocity's registrar is not told who is registering, so the plugin is found from its code on
 * the calling stack. A channel stays while any plugin that registered it is enabled. Kept for the
 * life of the proxy, a disabled plugin's channels went on raising PluginMessageEvent for every
 * other plugin listening to it, on channels nobody served any more.
 */
final class VelocityChannelRegistrar implements ChannelRegistrar {
  /** Who registered a channel; {@code unowned} when code outside any plugin did, which never lets go. */
  private record Registration(ChannelIdentifier identifier, Set<VelocityPluginHost.Container> owners, boolean unowned) {}
  private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private final VelocityPluginHost plugins;
  private final ConcurrentHashMap<String, Registration> channels = new ConcurrentHashMap<>();

  VelocityChannelRegistrar(VelocityPluginHost plugins) { this.plugins = plugins; }

  @Override public void register(ChannelIdentifier... identifiers) {
    VelocityPluginHost.Container caller = caller();
    for (ChannelIdentifier identifier : identifiers) {
      channels.compute(identifier.getId(), (id, known) -> {
        Set<VelocityPluginHost.Container> owners = known == null ? new HashSet<>() : new HashSet<>(known.owners);
        if (caller != null) owners.add(caller);
        return new Registration(identifier, Set.copyOf(owners), caller == null || known != null && known.unowned);
      });
    }
  }
  @Override public void unregister(ChannelIdentifier... identifiers) {
    for (ChannelIdentifier identifier : identifiers) channels.remove(identifier.getId());
  }
  /** The identifier a plugin registered for {@code channel}, or null when none did. */
  ChannelIdentifier find(String channel) {
    Registration registration = channel == null ? null : channels.get(channel);
    return registration == null ? null : registration.identifier;
  }
  /** A disabled plugin's channels go, unless another plugin registered them too. */
  void release(VelocityPluginHost.Container plugin) {
    for (String id : channels.keySet()) {
      channels.computeIfPresent(id, (key, registration) -> {
        if (!registration.owners.contains(plugin)) return registration;
        Set<VelocityPluginHost.Container> owners = new HashSet<>(registration.owners);
        owners.remove(plugin);
        return owners.isEmpty() && !registration.unowned ? null : new Registration(registration.identifier, Set.copyOf(owners), registration.unowned);
      });
    }
  }
  /** The plugin whose class is nearest the top of the calling stack, or null when no plugin's is on it. */
  private VelocityPluginHost.Container caller() {
    return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
        .map(type -> plugins.loadedBy(type.getClassLoader()))
        .filter(java.util.Objects::nonNull).findFirst().orElse(null));
  }
}

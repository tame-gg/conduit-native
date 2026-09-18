// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import com.velocitypowered.api.util.GameProfile;
import java.util.Optional;

/** Game profiles both ways. Velocity has no signature for a property that has none: its null. */
final class Profiles {
  private Profiles() { }

  static GameProfile toVelocity(gg.tame.conduit.api.player.GameProfile profile) {
    return new GameProfile(profile.uniqueId(), profile.name(), profile.properties().stream()
        .map(property -> new GameProfile.Property(property.name(), property.value(), property.signature().orElse(null)))
        .toList());
  }

  /** @throws IllegalArgumentException for a name no player could log in with, as Conduit's profile refuses one */
  static gg.tame.conduit.api.player.GameProfile toConduit(GameProfile profile) {
    return new gg.tame.conduit.api.player.GameProfile(profile.getId(), profile.getName(), profile.getProperties().stream()
        .map(property -> new gg.tame.conduit.api.player.GameProfile.Property(property.getName(), property.getValue(),
            Optional.ofNullable(property.getSignature()).filter(signature -> !signature.isEmpty())))
        .toList());
  }
}

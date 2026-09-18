// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;

/** Registers Conduit-specific Via providers. */
public final class ConduitViaPlatformLoader implements ViaPlatformLoader {
  @Override
  public void load() {
    Via.getManager().getProviders().use(VersionProvider.class, new ConduitViaVersionProvider());
  }

  @Override
  public void unload() {
  }
}

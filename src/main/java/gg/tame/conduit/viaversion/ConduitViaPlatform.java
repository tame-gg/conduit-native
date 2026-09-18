// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;
import java.io.File;
import java.util.logging.Logger;

/**
 * Conduit's ViaVersion platform. Proxy semantics; one logical player maps to one UserConnection
 * used by {@link ConduitViaSession}.
 */
public final class ConduitViaPlatform extends UserConnectionViaVersionPlatform {
  private final String platformVersion;
  private final Logger logger;

  public ConduitViaPlatform(File dataFolder, String platformVersion) {
    super(dataFolder);
    this.platformVersion = platformVersion == null ? "0.0.0" : platformVersion;
    this.logger = Logger.getLogger("Conduit-Via");
  }

  @Override
  public Logger createLogger(String name) {
    return Logger.getLogger(name == null || name.isBlank() ? "Conduit-Via" : name);
  }

  @Override
  public boolean isProxy() {
    return true;
  }

  @Override
  public String getPlatformName() {
    return "Conduit";
  }

  @Override
  public String getPlatformVersion() {
    return platformVersion;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }
}

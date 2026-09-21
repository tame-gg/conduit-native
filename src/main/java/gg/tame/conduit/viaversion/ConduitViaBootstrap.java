// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.log.ConduitLog;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bootstraps the ViaVersion ecosystem for Conduit using published public APIs only.
 */
public final class ConduitViaBootstrap {
  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static final AtomicBoolean SHUTDOWN_HOOK = new AtomicBoolean();
  private static volatile TranslationSettings settings = TranslationSettings.defaults();
  private static volatile boolean available;

  private ConduitViaBootstrap() {}

  public static synchronized void start(Path configDirectory, String conduitVersion, TranslationSettings translation) {
    settings = translation == null ? TranslationSettings.defaults() : translation;
    if (!settings.enabled()) {
      available = false;
      ConduitLog.info("ViaVersion translation disabled by configuration");
      return;
    }
    if (settings.engine() == TranslationSettings.TranslationEngine.NATIVE) {
      available = false;
      ConduitLog.info("Translation engine=native; ViaVersion not loaded");
      return;
    }
    if (!installed()) {
      // No ViaVersion on the class path at all, which is an ordinary state now that it is not
      // inside the jar: a first start that could not reach the repository, or an operator who
      // emptied lib/via. Conduit still carries every player its own codecs cover; what is off is
      // the cross-version path Via provides.
      available = false;
      ConduitLog.warn("ViaVersion is not installed, so cross-version play is off: a client may only join a"
          + " backend on its own protocol. Conduit downloads it into lib/via on a start that can reach"
          + " repo.viaversion.com, or you can put the jars there yourself. Set translation.enabled = false"
          + " to stop looking for it.");
      return;
    }
    if (!STARTED.compareAndSet(false, true)) {
      // Already loaded in this JVM. A runtime in between that had translation off marked it
      // unavailable, and returning here left this one, which turned it on, without it.
      available = ConduitViaPlatformStarter.loaded();
      return;
    }
    try {
      available = ConduitViaPlatformStarter.launch(configDirectory, conduitVersion, settings);
      if (SHUTDOWN_HOOK.compareAndSet(false, true)) {
        Runtime.getRuntime().addShutdownHook(new Thread(ConduitViaBootstrap::stop, "conduit-via-shutdown"));
      }
    } catch (Throwable failure) {
      available = false;
      STARTED.set(false);
      ConduitLog.error("ViaVersion platform failed to start", failure);
    }
  }

  /**
   * Whether ViaVersion is on the class path, asked without naming a Via type.
   *
   * <p>A method that mentions one cannot be entered when it is missing: the error comes from
   * resolving the method, before any {@code try} inside it, which is how an absent Via used to take
   * the whole proxy down rather than only translation.
   */
  private static boolean installed() {
    try {
      Class.forName("com.viaversion.viaversion.api.Via", false, ConduitViaBootstrap.class.getClassLoader());
      return true;
    } catch (Throwable missing) {
      return false;
    }
  }

  /**
   * Shuts the Via platform down.
   *
   * <p>Via's platform executors are not daemon threads, so a process that started Via and never
   * stopped it stays alive after its last foreground thread finishes. Conduit therefore owns an
   * explicit stop, and registers it as a shutdown hook so an abrupt exit still releases them.
   */
  public static synchronized void stop() {
    if (!STARTED.get()) {
      return;
    }
    available = false;
    try {
      ConduitViaPlatformStarter.destroy();
    } catch (Throwable failure) {
      ConduitLog.warn("ViaVersion shutdown reported " + failure);
    } finally {
      STARTED.set(false);
    }
  }

  public static boolean available() {
    return available && ConduitViaPlatformStarter.loaded();
  }

  public static TranslationSettings settings() {
    return settings;
  }



}

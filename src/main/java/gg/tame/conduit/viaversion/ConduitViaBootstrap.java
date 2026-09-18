// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viabackwards.api.ViaBackwardsPlatform;
import com.viaversion.viarewind.api.ViaRewindPlatform;
import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.log.ConduitLog;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.raphimc.vialegacy.platform.ViaLegacyPlatform;

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
    if (!STARTED.compareAndSet(false, true)) {
      return;
    }
    try {
      File dataFolder = configDirectory.resolve(settings.dataFolder()).toFile();
      if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
        throw new IllegalStateException("unable to create Via data folder: " + dataFolder);
      }
      ConduitViaPlatform platform = new ConduitViaPlatform(dataFolder, conduitVersion);

      // Addon platforms must register during Via enable listeners (before onServerLoaded).
      List<Runnable> enableListeners = new ArrayList<>();
      if (settings.loadViaBackwards()) {
        enableListeners.add(() -> initBackwards(dataFolder));
      }
      if (settings.loadViaRewind()) {
        enableListeners.add(() -> initRewind(dataFolder));
      }
      if (settings.loadViaLegacy()) {
        enableListeners.add(() -> {
          try {
            initLegacy(dataFolder);
          } catch (Throwable legacyFailure) {
            ConduitLog.error("ViaLegacy failed to load (modern→≤1.7.10 backends unavailable). "
                + "Install ViaLegacy transitive deps or set translation.via-legacy=false. "
                + legacyFailure);
          }
        });
      }

      ViaManagerImpl.initAndLoad(
          platform,
          new ConduitViaInjector(),
          new ViaCommandHandler(false),
          new ConduitViaPlatformLoader(),
          enableListeners.toArray(Runnable[]::new));

      available = Via.isLoaded();
      if (SHUTDOWN_HOOK.compareAndSet(false, true)) {
        Runtime.getRuntime().addShutdownHook(new Thread(ConduitViaBootstrap::stop, "conduit-via-shutdown"));
      }
      ConduitLog.info("ViaVersion platform ready (version "
          + Via.getAPI().getVersion()
          + ", backwards=" + settings.loadViaBackwards()
          + ", rewind=" + settings.loadViaRewind()
          + ", legacy=" + settings.loadViaLegacy() + ")");
    } catch (Throwable failure) {
      available = false;
      STARTED.set(false);
      ConduitLog.error("ViaVersion platform failed to start", failure);
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
      if (Via.isLoaded() && Via.getManager() instanceof ViaManagerImpl manager) {
        manager.destroy();
      }
    } catch (Throwable failure) {
      ConduitLog.warn("ViaVersion shutdown reported " + failure);
    } finally {
      STARTED.set(false);
    }
  }

  public static boolean available() {
    return available && Via.isLoaded();
  }

  public static TranslationSettings settings() {
    return settings;
  }

  private static void initBackwards(File dataFolder) {
    ViaBackwardsPlatform platform = new ViaBackwardsPlatform() {
      private final Logger logger = Logger.getLogger("ViaBackwards");
      @Override public Logger getLogger() { return logger; }
      @Override public void disable() { }
      @Override public File getDataFolder() { return dataFolder; }
      @Override public boolean isOutdated() { return false; }
    };
    platform.init(new File(dataFolder, "viabackwards.yml"));
  }

  private static void initRewind(File dataFolder) {
    ViaRewindPlatform platform = new ViaRewindPlatform() {
      private final Logger logger = Logger.getLogger("ViaRewind");
      @Override public Logger getLogger() { return logger; }
      @Override public File getDataFolder() { return dataFolder; }
    };
    platform.init(new File(dataFolder, "viarewind.yml"));
  }

  private static void initLegacy(File dataFolder) {
    ViaLegacyPlatform platform = new ViaLegacyPlatform() {
      private final Logger logger = Logger.getLogger("ViaLegacy");
      @Override public Logger getLogger() { return logger; }
      @Override public File getDataFolder() { return dataFolder; }
    };
    platform.init(new File(dataFolder, "vialegacy.yml"));
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.viaversion;

import com.viaversion.viabackwards.api.ViaBackwardsPlatform;
import com.viaversion.viarewind.api.ViaRewindPlatform;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.log.ConduitLog;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.raphimc.vialegacy.platform.ViaLegacyPlatform;

/**
 * Everything that names a ViaVersion type, kept in one class so that the rest of Conduit can run
 * without one.
 *
 * <p>ViaVersion is no longer inside the jar: it is installed into {@code lib/via} on a start that
 * can reach its repository, and an operator may have neither. A method that mentions a missing
 * class cannot be entered at all -- the error comes from resolving the method, not from running it,
 * so no {@code try} inside it can catch it. {@link ConduitViaBootstrap} therefore checks that Via is
 * there before it calls anything here, and every call from elsewhere goes through
 * {@link ConduitViaBootstrap#available()}, which is false long before this class is touched.
 */
final class ConduitViaPlatformStarter {
  private ConduitViaPlatformStarter() {}

  /** Starts the platform and its addons; true when Via came up. */
  static boolean launch(Path configDirectory, String conduitVersion, TranslationSettings settings) {
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

    boolean loaded = Via.isLoaded();
    ConduitLog.info("ViaVersion platform ready (version "
        + Via.getAPI().getVersion()
        + ", backwards=" + settings.loadViaBackwards()
        + ", rewind=" + settings.loadViaRewind()
        + ", legacy=" + settings.loadViaLegacy() + ")");
    return loaded;
  }

  static boolean loaded() {
    return Via.isLoaded();
  }

  static void destroy() {
    if (Via.isLoaded() && Via.getManager() instanceof ViaManagerImpl manager) {
      manager.destroy();
    }
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

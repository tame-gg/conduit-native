// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * What a user actually downloads: one jar, in a folder with nothing else in it.
 *
 * <p>Conduit provisions {@code lib/via} on its own, because a jar cannot update itself and Via moves
 * faster than a Conduit release. Nothing provisions the rest of what a Velocity plugin needs --
 * Adventure, Configurate, Guice, Gson, night-config, Brigadier, SLF4J -- so it has to already be in
 * the jar. It was not, once, and the mistake was invisible on a development machine, where the same
 * libraries sat in {@code lib/} beside the jar from a build that had fetched them.
 *
 * <p>So this starts the release jar in an empty directory and requires a real Velocity plugin to
 * enable in it, and separately requires the jar to carry every library
 * {@code config/velocity-runtime.lock} names. It is skipped, with a line saying so, when there is no
 * release jar to test: the suite runs before one is built.
 */
public final class ReleaseJarTests {
  /** How long the proxy gets to start and enable the plugin. Via's mappings are the slow part. */
  private static final long START_TIMEOUT_SECONDS = 120;

  public static void main(String[] arguments) throws Exception {
    run(arguments.length > 0 ? Path.of(arguments[0]) : releaseJar(true));
  }

  /**
   * Within the suite, only a jar named by {@code -Dconduit.release.jar} is tested.
   *
   * <p>The suite runs before the jar is packaged, so anything found in {@code dist/} at that moment is
   * the previous build's -- and failing today's run over yesterday's jar tells nobody anything.
   * scripts/build-jar.ps1 names the jar it has just written.
   */
  public static void run() throws Exception {
    run(releaseJar(false));
  }

  private static void run(Path jar) throws Exception {
    if (jar == null || !Files.isRegularFile(jar)) {
      System.out.println("ReleaseJarTests skipped: no -Dconduit.release.jar (scripts/build-jar.ps1 runs this)");
      return;
    }
    theJarCarriesEveryLockedLibrary(jar);
    aPluginLoadsInAnEmptyFolder(jar);
    System.out.println("ReleaseJarTests OK");
  }

  /**
   * {@code dist/conduit-*.jar}, or the one named by {@code -Dconduit.release.jar}.
   *
   * <p>The property is how scripts/build-jar.ps1 points this at the jar it has just packaged.
   */
  private static Path releaseJar(boolean orWhateverIsInDist) throws IOException {
    String named = System.getProperty("conduit.release.jar");
    if (named != null && !named.isBlank()) return Path.of(named);
    if (!orWhateverIsInDist) return null;
    Path dist = Path.of("dist");
    if (!Files.isDirectory(dist)) return null;
    try (Stream<Path> files = Files.list(dist)) {
      return files.filter(file -> file.getFileName().toString().matches("conduit-.*\\.jar")).findFirst().orElse(null);
    }
  }

  /**
   * Every library the build resolved is inside the jar.
   *
   * <p>Read from the lock rather than from a list written here, so a library that joins the set --
   * because one of velocity-api's own POMs gained a dependency -- is covered the day it appears. Each
   * locked jar in {@code lib/} lends one of its class entries, and that entry has to be in the release
   * jar too.
   */
  private static void theJarCarriesEveryLockedLibrary(Path releaseJar) throws Exception {
    Path lock = Path.of("config", "velocity-runtime.lock");
    if (!Files.isRegularFile(lock)) {
      System.out.println("  no config/velocity-runtime.lock beside the working directory, skipping the containment check");
      return;
    }
    List<String> missing = new ArrayList<>();
    int checked = 0;
    try (JarFile release = new JarFile(releaseJar.toFile())) {
      for (String line : Files.readAllLines(lock, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) continue;
        String name = line.split("\t")[0];
        name = name.substring(name.lastIndexOf('/') + 1);
        Path library = Path.of("lib", name);
        if (!Files.isRegularFile(library)) {
          missing.add(name + " (not in lib/ either; run scripts/fetch-velocity-compat.ps1)");
          continue;
        }
        String sample = aClassEntry(library);
        if (sample == null) continue; // A jar of annotations and nothing else has nothing to check.
        checked++;
        if (release.getEntry(sample) == null) missing.add(name + " (" + sample + " is not in the jar)");
      }
    }
    require(missing.isEmpty(), "the release jar is missing libraries config/velocity-runtime.lock names:\n  "
        + String.join("\n  ", missing));
    require(checked > 0, "the lock named at least one library to check");
    System.out.println("  " + checked + " locked libraries are inside " + releaseJar.getFileName());
  }

  /** One class entry from a jar, for use as a marker; null when it holds no classes. */
  private static String aClassEntry(Path jar) throws IOException {
    try (JarFile file = new JarFile(jar.toFile())) {
      return file.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/"))
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * A Velocity plugin enables in a folder that held nothing but the jar.
   *
   * <p>The proxy is started with its working directory in a new empty directory, so every path it
   * resolves -- {@code plugins}, {@code lib}, the configuration -- is one it has to create itself.
   * The configuration is the sample out of the jar, with the port moved off 25565 so the test does not
   * fight whatever is already listening there, and Via's update check is switched off: this is about
   * what the jar carries, not about what it can fetch.
   *
   * <p>Afterwards {@code lib/} must hold no jar of its own. A release that quietly needed one there
   * would have failed above, but a release that started writing them would be the bug coming back in
   * the other direction.
   */
  private static void aPluginLoadsInAnEmptyFolder(Path releaseJar) throws Exception {
    Path folder = TempFiles.dir("conduit-release");
    Files.writeString(folder.resolve("conduit.toml"), sampleConfig(releaseJar, freePort()));

    // Compiled somewhere else entirely: the compiler's working files must not land in the folder
    // whose contents this test is about.
    Path plugins = Files.createDirectory(folder.resolve("plugins"));
    VelocityCompatTests.jar(
        VelocityCompatTests.compile(TempFiles.dir("conduit-release-probe"), "release.ReleaseProbe", PROBE, List.of(), true),
        plugins.resolve("ReleaseProbe.jar"), null);

    List<String> command = List.of(
        ProcessHandle.current().info().command().orElse("java"),
        "-Dconduit.via.update=false",
        "-jar", releaseJar.toAbsolutePath().toString());
    Process proxy = new ProcessBuilder(command)
        .directory(folder.toFile())
        .redirectErrorStream(true)
        .start();
    List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());
    try {
      require(waitFor(proxy, output, "release-probe: enabled"),
          "the probe plugin enabled from a folder that held only the jar:\n" + String.join("\n", output));
    } finally {
      proxy.destroy();
      if (!proxy.waitFor(30, TimeUnit.SECONDS)) proxy.destroyForcibly();
    }

    Path lib = folder.resolve("lib");
    Set<String> provisioned = new LinkedHashSet<>();
    if (Files.isDirectory(lib)) {
      try (Stream<Path> files = Files.list(lib)) {
        files.filter(file -> file.getFileName().toString().endsWith(".jar"))
            .forEach(file -> provisioned.add(file.getFileName().toString()));
      }
    }
    require(provisioned.isEmpty(), "a release needs no jar in lib/, but wrote " + provisioned);
  }

  /** Reads the sample configuration out of the jar and moves the listener onto {@code port}. */
  private static String sampleConfig(Path releaseJar, int port) throws IOException {
    String entry = "gg/tame/conduit/config/conduit.toml";
    try (JarFile jar = new JarFile(releaseJar.toFile())) {
      var resource = jar.getEntry(entry);
      require(resource != null, releaseJar.getFileName() + " ships " + entry + ", which a first start writes");
      try (var stream = jar.getInputStream(resource)) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
            .replaceFirst("(?m)^port = 25565$", "port = " + port);
      }
    }
  }

  /** A port nothing is listening on. Racy in principle; nothing else here binds in between. */
  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * Reads the proxy's output until {@code marker} appears, it exits, or the time runs out.
   *
   * <p>On its own thread, because a proxy that is up and quiet blocks in {@code readLine} for as long
   * as it is running, and a deadline checked after that call is no deadline at all.
   */
  private static boolean waitFor(Process proxy, List<String> output, String marker) throws InterruptedException {
    java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(1);
    Thread reader = new Thread(() -> {
      try (BufferedReader lines = new BufferedReader(
          new InputStreamReader(proxy.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lines.readLine()) != null) {
          output.add(line);
          if (line.contains(marker)) seen.countDown();
        }
      } catch (IOException closed) {
        // The stream ends when the process is destroyed, which is how this thread is meant to finish.
      }
    }, "release-jar-output");
    reader.setDaemon(true);
    reader.start();
    return seen.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * A Velocity plugin that uses what velocity-api's POM promises it.
   *
   * <p>Each library is touched rather than merely imported, so a class that is missing from the jar
   * fails here instead of the first time an operator's own plugin reaches for it. Guice supplies the
   * constructor arguments, which is itself the test of Guice and of velocity-api's annotations.
   */
  private static final String PROBE = """
      package release;

      import com.google.inject.Inject;
      import com.velocitypowered.api.event.Subscribe;
      import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
      import com.velocitypowered.api.plugin.Plugin;
      import com.velocitypowered.api.proxy.ProxyServer;

      @Plugin(id = "release-probe", name = "ReleaseProbe", version = "1.0")
      public final class ReleaseProbe {
        private final ProxyServer proxy;

        @Inject
        public ReleaseProbe(ProxyServer proxy, org.slf4j.Logger logger) {
          this.proxy = proxy;
          logger.info("release-probe: constructed");
        }

        @Subscribe
        public void started(ProxyInitializeEvent event) throws Exception {
          // Adventure, and the serializers velocity-api's signatures hand a plugin.
          net.kyori.adventure.text.Component message =
              net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<red>probe</red>");
          check(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(message).equals("probe"), "adventure");
          check(!net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
              .serialize(message).isEmpty(), "adventure-gson");
          check(!net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer.ansi()
              .serialize(message).isEmpty(), "adventure-ansi");
          // Configurate 4 and HOCON, which a plugin's own config loader uses.
          check(org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
              .buildAndLoadString("a = 1").node("a").getInt() == 1, "configurate-hocon");
          check(org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder() != null, "configurate-yaml");
          check(new org.yaml.snakeyaml.Yaml().load("a: 1") != null, "snakeyaml");
          // The two TOML readers a Velocity plugin may reach for.
          check(new com.moandjiezana.toml.Toml().read("a = 1").getLong("a") == 1L, "toml4j");
          check(new com.electronwill.nightconfig.toml.TomlParser().parse("a = 1").get("a") != null, "night-config");
          check(new com.google.gson.Gson().toJson(1).equals("1"), "gson");
          check(com.github.benmanes.caffeine.cache.Caffeine.newBuilder().build() != null, "caffeine");
          // Velocity's Brigadier fork, not Mojang's: only the fork has requiresWithContext.
          check(com.mojang.brigadier.builder.LiteralArgumentBuilder.<Object>literal("probe")
              .requiresWithContext((source, reader) -> true) != null, "velocity-brigadier");
          check(proxy.getPluginManager().isLoaded("release-probe"), "plugin manager");
          System.out.println("release-probe: enabled");
        }

        private static void check(boolean held, String what) {
          if (!held) throw new IllegalStateException("release-probe: " + what + " did not work");
        }
      }
      """;

  private static void require(boolean held, String what) {
    if (!held) throw new AssertionError(what);
  }
}

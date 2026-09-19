// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.launcher;

import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import gg.tame.conduit.forwarding.ForwardingSecret;
import gg.tame.conduit.forwarding.Forwarders;
import gg.tame.conduit.log.ConduitLog;
import gg.tame.conduit.network.MinecraftProxy;
import java.nio.file.Path;

public final class Main {
  private Main() {}
  public static void main(String[] arguments) throws Exception {
    // Before anything can log: this is what puts ViaVersion, the Velocity compatibility layer and
    // Conduit's own lines on one colour-coded console instead of two formats and two streams.
    gg.tame.conduit.log.ConduitConsoleLogging.install();
    boolean checkOnly = arguments.length > 0 && arguments[0].equals("--check-config");
    if (arguments.length < (checkOnly ? 2 : 1)) {
      System.err.println("Usage: java gg.tame.conduit.launcher.Main [--check-config] <path to conduit.toml>");
      System.exit(2);
    }
    Path configPath = Path.of(arguments[checkOnly ? 1 : 0]);
    ConduitConfiguration config;
    try {
      // A bare folder is made startable before anything tries to read a configuration out of it: the
      // jar carries the file it would otherwise have complained was missing.
      gg.tame.conduit.config.ConfigBootstrap.ensure(configPath, !checkOnly);
      // Then into this version's layout, keeping every value already set. Only a real start writes,
      // so --check-config reads the file exactly as it is on disk.
      if (!checkOnly) {
        gg.tame.conduit.config.ConfigRewriter.report(configPath, gg.tame.conduit.config.ConfigRewriter.rewrite(configPath));
      }
      // A real start may create the modern-forwarding secret; --check-config
      // validates and writes nothing.
      config = ConfigurationLoader.load(configPath, !checkOnly);
      // The secret file is created in every mode, so its presence no longer means
      // forwarding is signed. Only say so when something actually reads it.
      if (config.forwardingMode() == gg.tame.conduit.config.ForwardingMode.MODERN && config.forwardingSecretFile().isPresent()) {
        ConduitLog.info("Modern forwarding secret loaded (fingerprint " + ForwardingSecret.load(config.forwardingSecretFile().get()).fingerprint() + ").");
      } else {
        ConduitLog.info("Player info forwarding: " + config.forwardingMode().name().toLowerCase(java.util.Locale.ROOT));
      }
      ConduitLog.info("Authentication mode: " + config.authentication().mode().name().toLowerCase(java.util.Locale.ROOT));
      if (config.proxyProtocol()) {
        // Said out loud on every start, because the setting moves who a connection claims to be
        // out of the socket and into an unauthenticated header: whoever can reach the port decides.
        ConduitLog.warn("listener.proxy-protocol is on: the address of every connection is taken"
            + " from a PROXY header, which nothing signs. Reach " + config.listener().getHostString()
            + ":" + config.listener().getPort() + " only from the reverse proxy that sends it,"
            + " or anyone can claim any address and walk through a ban.");
      }
      Forwarders.create(config);
    } catch (IllegalArgumentException | java.io.IOException invalid) {
      // The operator's mistake, not Conduit's: the message names it, and a stack trace would bury it.
      System.err.println("Configuration error: " + invalid.getMessage());
      System.exit(1);
      return;
    }
    if (checkOnly) { System.out.println("Configuration valid."); return; }
    Path plugins = configPath.toAbsolutePath().getParent() == null ? Path.of("plugins") : configPath.toAbsolutePath().getParent().resolve("plugins");
    // Loaded again only when defaults were appended: every load repeats the file's warnings.
    if (gg.tame.conduit.config.ConfigMigrator.migrate(configPath).changed()) config = ConfigurationLoader.load(configPath, true);
    MinecraftProxy proxy;
    try {
      proxy = new MinecraftProxy(config, gg.tame.conduit.auth.Authenticators.create(config.authentication()), gg.tame.conduit.crypto.RsaKeys.generate(), plugins);
    } catch (java.net.BindException taken) {
      System.err.println("Cannot listen on " + config.listener().getHostString() + ":" + config.listener().getPort()
          + " (listener.host and listener.port in " + configPath.getFileName() + "): " + taken.getMessage());
      System.exit(1);
      return;
    }
    // Ctrl+C or a service manager's stop: the same graceful shutdown as /conduit shutdown, so players
    // are told and plugins disabled instead of every connection being cut. close() is synchronized
    // and runs once, so a shutdown already under way is waited for rather than repeated.
    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("conduit-stop").unstarted(() -> {
      try { proxy.close(); } catch (java.io.IOException | RuntimeException ignored) { }
    }));
    try (MinecraftProxy listener = proxy) {
      listener.runtime().bindConfigPath(configPath);
      ConduitLog.info("Conduit " + gg.tame.conduit.Conduit.VERSION + " listening on "
          + config.listener().getHostString() + ":" + listener.port());
      listener.probeBackends();
      consoleCommands(listener.runtime());
      listener.serve();
    }
    // The shutdown is done and everything Conduit owns is closed. Anything still holding a
    // foreground thread at this point belongs to a library or a plugin that did not stop one of its
    // own, and waiting on it would leave the process -- and a Windows console window -- open for no
    // reason. Exiting explicitly also gives run.bat the 0 it needs to close that window without a
    // "press any key", while every failure above keeps its own nonzero code and its pause.
    System.exit(0);
  }

  /** Commands typed at the proxy's own terminal. Daemon: it must not hold shutdown open. */
  private static void consoleCommands(gg.tame.conduit.runtime.ConduitRuntime runtime) {
    Thread reader = new Thread(() -> {
      var console = new gg.tame.conduit.command.ConsoleCommandSource();
      try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
        for (String line = input.readLine(); line != null; line = input.readLine()) {
          // A shell piping UTF-8 in may begin with a byte-order mark, which made the first command unknown.
          if (line.startsWith("﻿")) line = line.substring(1);
          if (line.isBlank()) continue;
          try {
            if (!runtime.commandManager().dispatch(console, line)) console.sendMessage("Unknown command. Try /conduit help");
          } catch (RuntimeException failure) { gg.tame.conduit.log.ConduitLog.error("console command failed", failure); }
        }
      } catch (java.io.IOException closed) { }
    }, "conduit-console");
    reader.setDaemon(true);
    reader.start();
  }
}

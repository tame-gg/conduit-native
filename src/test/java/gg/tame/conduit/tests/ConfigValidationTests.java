// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A mistake in conduit.toml stops the load with one message naming the file, the line, the key,
 * what is written there and what is allowed; a legal but odd value is one warning. No network: the
 * unresolvable host is a malformed IPv6 literal, which Java refuses without a lookup.
 */
public final class ConfigValidationTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aValidConfigurationLoadsAsBefore();
    malformedLinesNameTheirLine();
    portsAndAddresses();
    serverNamesAndRouting();
    numbersAndChoices();
    forwarding();
    metricsAddress();
    suspiciousValuesAreOneWarning();
    theLauncherPrintsOneLineAndNoStackTrace();
    System.out.println("ConfigValidationTests OK");
  }

  // Line numbers in the expected messages count from here.
  private static final String BASE = """
      [listener]
      host = "127.0.0.1"
      port = 25565
      max-frame-bytes = 1048576
      [forwarding]
      mode = "none"
      [servers.lobby]
      host = "127.0.0.1"
      port = 25566
      [routing]
      initial = ["lobby"]
      fallback = ["lobby"]
      """;

  private static void aValidConfigurationLoadsAsBefore() throws Exception {
    String[] warnings = new String[1];
    ConduitConfiguration plain = load(BASE, warnings);
    require(warnings[0].isEmpty(), "a valid configuration loads without a warning:\n" + warnings[0]);
    require(plain.listener().getPort() == 25565 && plain.listener().getHostString().equals("127.0.0.1"), "listener");
    require(plain.backends().size() == 1 && plain.backends().getFirst().name().equals("lobby")
        && plain.backends().getFirst().address().getPort() == 25566, "servers");
    require(plain.initialBackends().equals(List.of("lobby")) && plain.fallbackBackends().equals(List.of("lobby")), "routing");
    require(plain.forwardingSecretFile().isEmpty() && plain.ops().metrics().prometheusAddress().isEmpty(), "defaults");

    // Trailing comments are TOML; they used to become part of the value.
    ConduitConfiguration commented = load("""
        [listener] # where players connect
        host = "127.0.0.1" # loopback only
        port = 25565 # the default
        max-frame-bytes = 1048576
        [forwarding]
        mode = "none"
        [servers.lobby]
        address = "127.0.0.1:25566" # the lobby
        [routing]
        initial = ["lobby"] # first stop
        fallback = ["lobby"]
        [status]
        motd = "Server #1 # kept" # dropped
        """, warnings);
    require(warnings[0].isEmpty(), "comments are not settings:\n" + warnings[0]);
    require(commented.listener().equals(plain.listener()) && commented.backends().equals(plain.backends())
        && commented.initialBackends().equals(plain.initialBackends()), "a commented configuration loads the same");
    require(commented.status().motd().plain().equals("Server #1 # kept"), "a # inside quotes stays: " + commented.status().motd().plain());
  }

  private static void malformedLinesNameTheirLine() throws Exception {
    fails(BASE.replace("port = 25565", "port 25565"), "conduit.toml line 3: expected key = value, found port 25565");
    fails(BASE.replace("[forwarding]", "[forwarding"), "conduit.toml line 5: a [section] header must end with ], found [forwarding");
    fails("host = \"127.0.0.1\"\n" + BASE, "conduit.toml line 1: a setting must come after a [section] header, found host = \"127.0.0.1\"");
    fails(BASE.replace("host = \"127.0.0.1\"\nport = 25565", "host = \"127.0.0.1\nport = 25565"),
        "conduit.toml line 2: a string must end with \", found host = \"127.0.0.1");
    fails(BASE.replace("initial = [\"lobby\"]", "initial = [\n  \"lobby\",\n]"),
        "conduit.toml line 11: an array must open and close on one line, found initial = [");
    fails(BASE + "[listener]\nport = 25570\n", "conduit.toml line 14: listener.port is already set on line 3, found port = 25570");
    require(failure(Path.of("does-not-exist", "conduit.toml")).endsWith("conduit.toml does not exist. Copy the sample config/conduit.toml there and edit it."),
        "a missing file says what to do");
    Path latin1 = TempFiles.file("conduit", ".toml");
    Files.write(latin1, (BASE + "[status]\nmotd = \"Café\"\n").getBytes(StandardCharsets.ISO_8859_1));
    require(failure(latin1).equals("conduit.toml is not UTF-8 text. Save it as UTF-8."), "a file saved in another encoding");
  }

  private static void portsAndAddresses() throws Exception {
    fails(BASE.replace("port = 25565", "port = 70000"), "conduit.toml line 3: listener.port must be 1..65535, found 70000");
    fails(BASE.replace("port = 25565", "port = 0"), "conduit.toml line 3: listener.port must be 1..65535, found 0");
    fails(BASE.replace("port = 25565", "port = \"25565a\""), "conduit.toml line 3: listener.port must be an integer, found \"25565a\"");
    fails(BASE.replace("port = 25566", "port = -1"), "conduit.toml line 9: servers.lobby.port must be 1..65535, found -1");
    fails(BASE.replace("host = \"127.0.0.1\"\nport = 25566", "address = \"127.0.0.1:70000\""),
        "conduit.toml line 8: servers.lobby.address port must be 1..65535, found \"127.0.0.1:70000\"");
    fails(BASE.replace("host = \"127.0.0.1\"\nport = 25566", "address = \"127.0.0.1\""),
        "conduit.toml line 8: servers.lobby.address must be host:port, found \"127.0.0.1\"");
    fails(BASE.replace("host = \"127.0.0.1\"\nport = 25566", "port = 25566"),
        "conduit.toml line 7: servers.lobby needs host and port, or address = \"host:port\"");
    fails(BASE.replace("host = \"127.0.0.1\"\nport = 25565", "host = \"[::zz]\"\nport = 25565"),
        "conduit.toml line 2: listener.host must be an IP address or a host name that resolves, such as 0.0.0.0 or 127.0.0.1, found \"[::zz]\"");
    // Pointed at itself, Conduit proxied every player back into itself.
    fails(BASE.replace("port = 25566", "port = 25565"),
        "conduit.toml line 7: servers.lobby is Conduit's own listener (127.0.0.1:25565); a server needs another address or port");
    fails(BASE.replace("host = \"127.0.0.1\"\nport = 25565", "host = \"0.0.0.0\"\nport = 25566"),
        "conduit.toml line 7: servers.lobby is Conduit's own listener (127.0.0.1:25566); a server needs another address or port");
  }

  private static void serverNamesAndRouting() throws Exception {
    String twoServers = BASE.replace("[routing]", "[servers.Lobby]\nhost = \"127.0.0.1\"\nport = 25567\n[routing]");
    fails(twoServers, "conduit.toml line 10: servers.Lobby has the same name as servers.lobby (server names ignore case)");
    fails(BASE.replace("[servers.lobby]", "[servers.my.lobby]").replace("[\"lobby\"]", "[\"my.lobby\"]"),
        "conduit.toml line 7: servers.my.lobby is not a valid server name: use 1 to 64 letters, digits, _ or -");
    fails(BASE.replace("fallback = [\"lobby\"]", "fallback = [\"lobby\", \"survival\"]"),
        "conduit.toml line 12: routing.fallback names unknown server survival, found [\"lobby\", \"survival\"]");
    fails(BASE.replace("initial = [\"lobby\"]", "initial = [\"Lobby\"]"),
        "conduit.toml line 11: routing.initial names unknown server Lobby (did you mean lobby?), found [\"Lobby\"]");
    fails(BASE.replace("initial = [\"lobby\"]", "initial = []"), "conduit.toml line 11: routing.initial must name at least one backend, found []");
    fails(BASE.replace("initial = [\"lobby\"]\n", ""), "conduit.toml: missing required setting: routing.initial");
    fails(BASE.replace("[routing]", "mod-loaders = [\"forgee\"]\n[routing]"),
        "conduit.toml line 10: servers.lobby.mod-loaders may only name vanilla, fabric, quilt, forge or neoforge (unknown mod loader: forgee), found [\"forgee\"]");
  }

  private static void numbersAndChoices() throws Exception {
    fails(BASE + "[health]\ninterval-ms = 0\n", "conduit.toml line 14: health.interval-ms must be >= 500, found 0");
    fails(BASE + "[shutdown]\ntimeout-ms = -5\n", "conduit.toml line 14: shutdown.timeout-ms must be >= 100, found -5");
    fails(BASE + "[authentication]\ntimeout-millis = 0\n", "conduit.toml line 14: authentication.timeout-millis must be 100..60000, found 0");
    fails(BASE + "[security.throttle]\nmax-concurrent = 0\n", "conduit.toml line 14: security.throttle.max-concurrent must be >= 1, found 0");
    fails(BASE + "[modded]\npacket-queue-max-depth = 0\n", "conduit.toml line 14: modded.packet-queue-max-depth must be 1..16384, found 0");
    fails(BASE + "[status]\ndisplay-max-players = -1\n", "conduit.toml line 14: status.display-max-players must be >= 0, found -1");
    fails(BASE.replace("max-frame-bytes = 1048576", "max-frame-bytes = 0"), "conduit.toml line 4: listener.max-frame-bytes must be 1..8388608, found 0");
    fails(BASE + "[health]\nenabled = yes\n", "conduit.toml line 14: health.enabled must be true or false, found yes");
    fails(BASE + "[translation]\nengine = \"fast\"\n", "conduit.toml line 14: translation.engine must be via-preferred, via or native, found \"fast\"");
    fails(BASE + "[modded]\nunknown-policy = \"kick\"\n", "conduit.toml line 14: modded.unknown-policy must be allow, deny, or route_to_fallback, found \"kick\"");
    fails(BASE + "[security.channel-guard]\ndefault-action = \"ban\"\n",
        "conduit.toml line 14: security.channel-guard.default-action must be off, log, drop or kick, found \"ban\"");
    fails(BASE + "[versions]\nallow = [\"1.99.9\"]\n",
        "conduit.toml line 14: versions.allow must name Minecraft releases such as 1.20.4, or protocol numbers (unknown Minecraft version: 1.99.9), found [\"1.99.9\"]");
  }

  private static void forwarding() throws Exception {
    fails(BASE.replace("mode = \"none\"", "mode = \"legacy\""),
        "conduit.toml line 6: forwarding.mode must be none or modern: Conduit does not implement legacy forwarding, found \"legacy\"");
    fails(BASE.replace("mode = \"none\"", "mode = \"velocity\""), "conduit.toml line 6: forwarding.mode must be none or modern, found \"velocity\"");
    String modern = BASE.replace("mode = \"none\"", "mode = \"modern\"");
    fails(modern, "conduit.toml: forwarding.secret-file is required for modern forwarding");
    String withSecret = modern.replace("[servers.lobby]", "secret-file = \"forwarding.secret\"\n[servers.lobby]");
    Path config = write(withSecret);
    require(failure(config).equals("conduit.toml line 7: forwarding.secret-file does not exist at "
        + config.getParent().resolve("forwarding.secret") + ", found \"forwarding.secret\""), "a missing secret: " + failure(config));
    Files.writeString(config.getParent().resolve("forwarding.secret"), " \n");
    require(failure(config).startsWith("conduit.toml line 7: forwarding.secret-file is empty at "), "an empty secret: " + failure(config));
    Files.writeString(config.getParent().resolve("forwarding.secret"), "s3cret\n");
    require(ConfigurationLoader.load(config).forwardingSecretFile().orElseThrow().endsWith("forwarding.secret"), "a readable secret loads");
  }

  private static void metricsAddress() throws Exception {
    fails(BASE + "[metrics]\nprometheus-address = \"127.0.0.1:25565\"\n",
        "conduit.toml line 14: metrics.prometheus-address uses the port Conduit listens on (127.0.0.1:25565); give it another port, found \"127.0.0.1:25565\"");
    fails(BASE + "[metrics]\nprometheus-address = \"0.0.0.0:25565\"\n",
        "conduit.toml line 14: metrics.prometheus-address uses the port Conduit listens on (127.0.0.1:25565); give it another port, found \"0.0.0.0:25565\"");
    fails(BASE + "[metrics]\nprometheus-address = \"nope\"\n", "conduit.toml line 14: metrics.prometheus-address must be host:port, found \"nope\"");
    fails(BASE + "[metrics]\nprometheus-address = \"[::zz]:9225\"\n",
        "conduit.toml line 14: metrics.prometheus-address must be an IP address or a host name that resolves, with a port, found \"[::zz]:9225\"");
    require(load(BASE + "[metrics]\nprometheus-address = \"127.0.0.1:9225\"\n", new String[1]).ops().metrics().prometheusAddress().orElseThrow().getPort() == 9225,
        "another port is fine");
  }

  private static void suspiciousValuesAreOneWarning() throws Exception {
    String[] warnings = new String[1];
    load(BASE.replace("host = \"127.0.0.1\"\nport = 25566", "host = \"[::zz]\"\nport = 25566"), warnings);
    require(warnings[0].equals("WARN servers.lobby host [::zz] in conduit.toml does not resolve; Conduit looks it up only at start,"
        + " so lobby is unreachable until a restart" + System.lineSeparator()), "an unresolved server:\n" + warnings[0]);
    load(BASE + "[versions]\nenabled = true\n", warnings);
    require(warnings[0].equals("WARN versions.enabled is true in conduit.toml, but versions.allow, versions.minimum and versions.maximum"
        + " are all unset, so no Minecraft version is turned away" + System.lineSeparator()), "a gate with nothing to gate:\n" + warnings[0]);
    load(BASE + "[versions]\nenabled = true\nminimum = \"1.20.4\"\n", warnings);
    require(warnings[0].isEmpty(), "a gate with a minimum:\n" + warnings[0]);
    load(BASE + "[helth]\nenabled = false\n", warnings);
    require(warnings[0].equals("WARN Unknown setting helth.enabled in conduit.toml is ignored" + System.lineSeparator()), "unknown settings:\n" + warnings[0]);
  }

  /** What an operator sees: one line on stderr, exit code 1, no stack trace. */
  private static void theLauncherPrintsOneLineAndNoStackTrace() throws Exception {
    Path config = write(BASE.replace("port = 25565", "port = 70000"));
    Process launcher = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), "gg.tame.conduit.launcher.Main", "--check-config", config.toString())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    try {
      require(launcher.waitFor(60, TimeUnit.SECONDS), "the launcher did not exit");
      String stderr = new String(launcher.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      require(launcher.exitValue() == 1, "exit code " + launcher.exitValue());
      require(stderr.equals("Configuration error: conduit.toml line 3: listener.port must be 1..65535, found 70000" + System.lineSeparator()),
          "stderr:\n" + stderr);
    } finally {
      launcher.destroyForcibly();
    }
  }

  private static Path write(String toml) throws Exception {
    Path config = TempFiles.file("conduit", ".toml");
    Files.writeString(config, toml);
    return config;
  }

  /** Loads, putting what it printed to stderr in warnings[0]. */
  private static ConduitConfiguration load(String toml, String[] warnings) throws Exception {
    Path config = write(toml);
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try { return ConfigurationLoader.load(config); }
    finally {
      System.setErr(original);
      warnings[0] = captured.toString(StandardCharsets.UTF_8);
    }
  }

  private static String failure(Path config) throws Exception {
    try { ConfigurationLoader.load(config); }
    catch (IllegalArgumentException expected) { return expected.getMessage(); }
    throw new AssertionError(config + " was accepted");
  }

  private static void fails(String toml, String expected) throws Exception {
    String message = failure(write(toml));
    require(message.equals(expected), "expected\n  " + expected + "\nbut got\n  " + message);
  }

  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

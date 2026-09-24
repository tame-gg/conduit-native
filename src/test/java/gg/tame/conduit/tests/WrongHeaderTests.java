// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import static gg.tame.conduit.tests.NativeApiTests.require;

import gg.tame.conduit.config.ConfigurationLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** A setting uncommented under a header that was not is read from its own section. */
public final class WrongHeaderTests {
  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    aSettingUnderTheWrongHeaderIsReadFromItsOwn();
    System.out.println("WrongHeaderTests OK");
  }

  private static void aSettingUnderTheWrongHeaderIsReadFromItsOwn() throws Exception {
    Path file = TempFiles.dir("conduit-wrongheader").resolve("conduit.toml");
    String base = """
        [listener]
        host = "127.0.0.1"
        port = 25565
        [forwarding]
        mode = "none"
        [servers.lobby]
        host = "127.0.0.1"
        port = 25566
        [routing]
        initial = ["lobby"]
        fallback = ["lobby"]
        [messaging]
        bungeecord-channel = true
        """;
    // webhook-url uncommented under an [alerts] header that was not: it lands under [messaging],
    // where it used to be ignored. It is read as alerts.webhook-url, and an empty one is simply off.
    Files.writeString(file, base + "webhook-url = \"https://example.invalid/hook\"\n");
    var loaded = ConfigurationLoader.load(file);
    require(loaded.ops().metrics().alertWebhook().map(url -> url.getHost().equals("example.invalid")).orElse(false),
        "the webhook is read from where it should have been, got " + loaded.ops().metrics().alertWebhook());
    Files.writeString(file, base + "webhook-url = \"\"\n");
    require(ConfigurationLoader.load(file).ops().metrics().alertWebhook().isEmpty(), "an empty one under the wrong header still boots, and is off");
  }
}

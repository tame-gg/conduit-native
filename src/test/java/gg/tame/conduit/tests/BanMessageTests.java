// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.config.BanSettings;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ConfigurationLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The screen a banned player is shown, from {@code [bans]}.
 *
 * <p>What matters is that a file with no {@code [bans]} section kicks exactly as Conduit always did,
 * that a template fills in, and that the reason given to one ban lands inside the template rather
 * than replacing it.
 */
public final class BanMessageTests {
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

  public static void main(String[] arguments) throws Exception { run(); }

  public static void run() throws Exception {
    theDefaultReadsAsItAlwaysHas();
    aTemplateFillsItsPlaceholders();
    aBansReasonComposesWithTheTemplate();
    System.out.println("BanMessageTests OK");
  }

  private static void theDefaultReadsAsItAlwaysHas() throws Exception {
    BanSettings bans = load(BASE).ops().bans();
    require(bans.equals(BanSettings.defaults()), "no [bans] section is the defaults");
    require(bans.render("griefing", "Op", Optional.empty()).equals("&cgriefing\n&7This ban is permanent."),
        "a permanent ban reads as it did before");
    require(bans.render("griefing", "Op", Optional.of("2d 3h")).equals("&cgriefing\n&7Expires in 2d 3h"),
        "a temporary ban reads as it did before");
    require(bans.render(null, "Op", Optional.empty()).startsWith("&cBanned from this network.\n"),
        "no reason is the built-in one");
  }

  private static void aTemplateFillsItsPlaceholders() throws Exception {
    BanSettings bans = load(BASE + """
        [bans]
        message = "<red>Banned: {reason}\\n{expiry}\\nby {actor}"
        temporary = "Back in {remaining}"
        permanent = "Forever."
        default-reason = "Rules."
        """).ops().bans();
    require(bans.render("spam", "Alex", Optional.of("30m")).equals("<red>Banned: spam\nBack in 30m\nby Alex"),
        "every placeholder of a temporary ban is filled");
    require(bans.render("", "Alex", Optional.empty()).equals("<red>Banned: Rules.\nForever.\nby Alex"),
        "a permanent ban uses permanent, and an empty reason the configured default");
    BanSettings remainingOnly = new BanSettings("{reason} ({remaining})", null, null, null);
    require(remainingOnly.render("x", "Op", Optional.empty()).equals("x ()"), "a permanent ban has nothing remaining");
  }

  private static void aBansReasonComposesWithTheTemplate() throws Exception {
    BanSettings bans = load(BASE + """
        [bans]
        message = "&4Example Network\\n&c{reason}\\n&7Appeal at example.com"
        """).ops().bans();
    String first = bans.render("hacking", "Op", Optional.empty());
    String second = bans.render("x-ray", "Op", Optional.of("7d"));
    require(first.equals("&4Example Network\n&chacking\n&7Appeal at example.com"), "one ban's reason fills the template");
    require(second.equals("&4Example Network\n&cx-ray\n&7Appeal at example.com"), "another ban keeps the same wording");
    require(bans.reasonOr("hacking").equals("hacking") && bans.reasonOr(" ").equals(BanSettings.DEFAULT_REASON),
        "a given reason is kept and a blank one takes the default");
    require(bans.render("say {actor}", "Op", Optional.empty()).contains("say {actor}"),
        "a reason is shown as typed, not expanded");
  }

  private static ConduitConfiguration load(String toml) throws Exception {
    Path config = TempFiles.file("conduit", ".toml");
    Files.writeString(config, toml);
    return ConfigurationLoader.load(config);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.config;

import java.util.Optional;

/**
 * What a banned player is shown, from {@code [bans]} in {@code conduit.toml}.
 *
 * <p>The message is one template for every ban, and a ban only fills it in: the reason an operator
 * gives {@code /gban} lands in {@code {reason}}, so a network can word its ban screen once and every
 * ban after that keeps the wording. A ban given no reason fills in {@code default-reason}.
 *
 * <p>The placeholders are {@code {reason}}, {@code {actor}} (who banned them), {@code {remaining}}
 * (how long is left, or nothing for a permanent ban) and {@code {expiry}}, which is
 * {@code temporary} with its own {@code {remaining}} filled in, or {@code permanent}. The reader keeps
 * a quoted {@code \n} as the two characters it is written as, so they are read as a line break here.
 *
 * <p>Every default is the screen Conduit showed before any of this was configurable, so a file with
 * no {@code [bans]} section kicks exactly as it did.
 */
public record BanSettings(String message, String temporary, String permanent, String defaultReason) {
  public static final String DEFAULT_MESSAGE = "&c{reason}\\n&7{expiry}";
  public static final String DEFAULT_TEMPORARY = "Expires in {remaining}";
  public static final String DEFAULT_PERMANENT = "This ban is permanent.";
  public static final String DEFAULT_REASON = "Banned from this network.";

  public BanSettings {
    if (message == null || message.isBlank()) message = DEFAULT_MESSAGE;
    if (temporary == null || temporary.isBlank()) temporary = DEFAULT_TEMPORARY;
    if (permanent == null) permanent = DEFAULT_PERMANENT;
    if (defaultReason == null || defaultReason.isBlank()) defaultReason = DEFAULT_REASON;
  }

  public static BanSettings defaults() {
    return new BanSettings(DEFAULT_MESSAGE, DEFAULT_TEMPORARY, DEFAULT_PERMANENT, DEFAULT_REASON);
  }

  /** The reason a ban is recorded with: what the operator typed, or {@code default-reason}. */
  public String reasonOr(String given) {
    return given == null || given.isBlank() ? defaultReason : given;
  }

  /**
   * The screen for one ban, still in {@code &}-code or MiniMessage form for the MOTD parser.
   *
   * <p>{@code {reason}} is filled last, so a reason that happens to contain {@code {actor}} is shown
   * as typed rather than expanded.
   */
  public String render(String reason, String actor, Optional<String> remaining) {
    String left = remaining.orElse("");
    String expiry = remaining.isPresent() ? temporary.replace("{remaining}", left) : permanent;
    return lines(message)
        .replace("{expiry}", lines(expiry))
        .replace("{remaining}", left)
        .replace("{actor}", actor == null ? "" : actor)
        .replace("{reason}", reasonOr(reason));
  }

  private static String lines(String text) { return text.replace("\\n", "\n"); }
}

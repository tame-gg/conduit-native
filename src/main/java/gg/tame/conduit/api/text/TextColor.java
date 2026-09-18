// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.api.text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A text colour: one of the sixteen named Minecraft colours, or any RGB colour. Clients before 1.16
 * only know the named ones, and are sent {@link #nearestNamed()} in place of an RGB colour. A named
 * colour stays named on the wire even where RGB exists, so it follows a resource pack that recolours
 * the names; {@code rgb(0xFF5555)} and {@link #RED} are therefore different colours.
 */
public final class TextColor {
  // Declared in formatting-code order, 0 (black) to f (white); RGB values are the game's own.
  public static final TextColor BLACK = named("black", 0x000000);
  public static final TextColor DARK_BLUE = named("dark_blue", 0x0000AA);
  public static final TextColor DARK_GREEN = named("dark_green", 0x00AA00);
  public static final TextColor DARK_AQUA = named("dark_aqua", 0x00AAAA);
  public static final TextColor DARK_RED = named("dark_red", 0xAA0000);
  public static final TextColor DARK_PURPLE = named("dark_purple", 0xAA00AA);
  public static final TextColor GOLD = named("gold", 0xFFAA00);
  public static final TextColor GRAY = named("gray", 0xAAAAAA);
  public static final TextColor DARK_GRAY = named("dark_gray", 0x555555);
  public static final TextColor BLUE = named("blue", 0x5555FF);
  public static final TextColor GREEN = named("green", 0x55FF55);
  public static final TextColor AQUA = named("aqua", 0x55FFFF);
  public static final TextColor RED = named("red", 0xFF5555);
  public static final TextColor LIGHT_PURPLE = named("light_purple", 0xFF55FF);
  public static final TextColor YELLOW = named("yellow", 0xFFFF55);
  public static final TextColor WHITE = named("white", 0xFFFFFF);

  private static final List<TextColor> NAMED = List.of(BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE,
      GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE);

  private final String name;
  private final int rgb;

  private TextColor(String name, int rgb) {
    this.name = name;
    this.rgb = rgb;
  }

  private static TextColor named(String name, int rgb) { return new TextColor(name, rgb); }

  /** An RGB colour, {@code 0xRRGGBB}. */
  public static TextColor rgb(int rgb) { return new TextColor(null, rgb & 0xFFFFFF); }

  /** The sixteen named colours, in formatting-code order: index 0 is {@code §0}, index 15 {@code §f}. */
  public static List<TextColor> named() { return NAMED; }

  /** A colour as components name it: a name such as {@code "red"}, or {@code "#rrggbb"}. Empty for anything else. */
  public static Optional<TextColor> parse(String value) {
    if (value == null) return Optional.empty();
    if (value.length() == 7 && value.charAt(0) == '#') {
      try { return Optional.of(rgb(Integer.parseInt(value.substring(1), 16))); }
      catch (NumberFormatException notHex) { return Optional.empty(); }
    }
    for (TextColor color : NAMED) if (color.name.equals(value)) return Optional.of(color);
    return Optional.empty();
  }

  /** Whether this is one of the sixteen named colours. */
  public boolean isNamed() { return name != null; }
  /** {@code 0xRRGGBB}; a named colour's is the game's default for it. */
  public int rgb() { return rgb; }
  /** How a component names it: {@code "red"}, or {@code "#rrggbb"} for an RGB colour. */
  public String colorName() { return name != null ? name : String.format(Locale.ROOT, "#%06x", rgb); }

  /** This colour if it is named, else the named colour closest to it, for clients with no RGB colours. */
  public TextColor nearestNamed() {
    if (name != null) return this;
    TextColor nearest = BLACK;
    int best = Integer.MAX_VALUE;
    for (TextColor color : NAMED) {
      int red = ((rgb >> 16) & 0xFF) - ((color.rgb >> 16) & 0xFF);
      int green = ((rgb >> 8) & 0xFF) - ((color.rgb >> 8) & 0xFF);
      int blue = (rgb & 0xFF) - (color.rgb & 0xFF);
      int distance = red * red + green * green + blue * blue;
      if (distance < best) { best = distance; nearest = color; }
    }
    return nearest;
  }

  @Override public boolean equals(Object other) {
    return other instanceof TextColor color && color.rgb == rgb && java.util.Objects.equals(color.name, name);
  }
  @Override public int hashCode() { return 31 * rgb + (name == null ? 0 : name.hashCode()); }
  @Override public String toString() { return colorName(); }
}

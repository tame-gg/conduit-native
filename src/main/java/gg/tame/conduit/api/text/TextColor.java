package gg.tame.conduit.api.text;

/** Named Minecraft text colors for Conduit components. */
public enum TextColor {
  BLACK("black"),
  DARK_BLUE("dark_blue"),
  DARK_GREEN("dark_green"),
  DARK_AQUA("dark_aqua"),
  DARK_RED("dark_red"),
  DARK_PURPLE("dark_purple"),
  GOLD("gold"),
  GRAY("gray"),
  DARK_GRAY("dark_gray"),
  BLUE("blue"),
  GREEN("green"),
  AQUA("aqua"),
  RED("red"),
  LIGHT_PURPLE("light_purple"),
  YELLOW("yellow"),
  WHITE("white");

  private final String name;
  TextColor(String name) { this.name = name; }
  public String colorName() { return name; }
}

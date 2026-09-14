package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Adventure ↔ Conduit Text bridge for Velocity-compatible plugins. */
final class Texts {
  private Texts() {}

  static String plain(Component component) {
    if (component == null) return "";
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  static Component text(String value) {
    return Component.text(value == null ? "" : value);
  }

  static Text toConduit(Component component) {
    if (component == null) return Text.empty();
    if (component instanceof TextComponent textComponent) {
      Text text = Text.of(textComponent.content());
      if (textComponent.color() instanceof NamedTextColor named) {
        TextColor mapped = mapColor(named);
        if (mapped != null) text = text.color(mapped);
      }
      if (textComponent.hasDecoration(TextDecoration.BOLD)) text = text.bold();
      if (textComponent.hasDecoration(TextDecoration.ITALIC)) text = text.italic();
      ClickEvent click = textComponent.clickEvent();
      if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) {
        text = text.clickRun(click.value());
      }
      HoverEvent<?> hover = textComponent.hoverEvent();
      if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component hoverComponent) {
        text = text.hover(toConduit(hoverComponent));
      }
      for (Component child : textComponent.children()) {
        text = text.append(toConduit(child));
      }
      return text;
    }
    return Text.of(plain(component));
  }

  private static TextColor mapColor(NamedTextColor color) {
    if (color.equals(NamedTextColor.BLACK)) return TextColor.BLACK;
    if (color.equals(NamedTextColor.DARK_BLUE)) return TextColor.DARK_BLUE;
    if (color.equals(NamedTextColor.DARK_GREEN)) return TextColor.DARK_GREEN;
    if (color.equals(NamedTextColor.DARK_AQUA)) return TextColor.DARK_AQUA;
    if (color.equals(NamedTextColor.DARK_RED)) return TextColor.DARK_RED;
    if (color.equals(NamedTextColor.DARK_PURPLE)) return TextColor.DARK_PURPLE;
    if (color.equals(NamedTextColor.GOLD)) return TextColor.GOLD;
    if (color.equals(NamedTextColor.GRAY)) return TextColor.GRAY;
    if (color.equals(NamedTextColor.DARK_GRAY)) return TextColor.DARK_GRAY;
    if (color.equals(NamedTextColor.BLUE)) return TextColor.BLUE;
    if (color.equals(NamedTextColor.GREEN)) return TextColor.GREEN;
    if (color.equals(NamedTextColor.AQUA)) return TextColor.AQUA;
    if (color.equals(NamedTextColor.RED)) return TextColor.RED;
    if (color.equals(NamedTextColor.LIGHT_PURPLE)) return TextColor.LIGHT_PURPLE;
    if (color.equals(NamedTextColor.YELLOW)) return TextColor.YELLOW;
    if (color.equals(NamedTextColor.WHITE)) return TextColor.WHITE;
    return null;
  }
}

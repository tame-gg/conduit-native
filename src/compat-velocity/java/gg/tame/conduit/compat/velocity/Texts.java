// SPDX-License-Identifier: GPL-3.0-or-later
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

/**
 * Adventure components as Conduit Text, and back. Keeps what Text can carry -- colour (hex snapped
 * to the nearest named colour), bold, italic, run-command clicks and text hovers -- and flattens
 * anything else (translatable, keybind, score, selector components) to its plain rendering.
 */
final class Texts {
  private Texts() {}

  static String plain(Component component) {
    return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
  }

  static Text toConduit(Component component) {
    if (component == null) return Text.empty();
    Text text = Text.of(component instanceof TextComponent literal ? literal.content() : plain(component));
    if (component.color() != null) text = text.color(TextColor.valueOf(NamedTextColor.nearestTo(component.color()).toString().toUpperCase(java.util.Locale.ROOT)));
    if (component.hasDecoration(TextDecoration.BOLD)) text = text.bold();
    if (component.hasDecoration(TextDecoration.ITALIC)) text = text.italic();
    ClickEvent click = component.clickEvent();
    if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) text = text.clickRun(click.value());
    HoverEvent<?> hover = component.hoverEvent();
    if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component shown) {
      text = text.hover(toConduit(shown));
    }
    // A non-text component was rendered whole above, children included.
    if (component instanceof TextComponent) for (Component child : component.children()) text = text.append(toConduit(child));
    return text;
  }

  /** Conduit Text as an Adventure component: everything Text holds has an exact counterpart. */
  static Component toAdventure(Text text) {
    if (text == null) return Component.empty();
    TextComponent.Builder builder = Component.text().content(text.content());
    if (text.color() != null) builder.color(NamedTextColor.NAMES.value(text.color().colorName()));
    if (text.isBold()) builder.decoration(TextDecoration.BOLD, true);
    if (text.isItalic()) builder.decoration(TextDecoration.ITALIC, true);
    if (text.clickCommand() != null) builder.clickEvent(ClickEvent.runCommand(text.clickCommand()));
    if (text.hover() != null) builder.hoverEvent(HoverEvent.showText(toAdventure(text.hover())));
    for (Text child : text.children()) builder.append(toAdventure(child));
    return builder.build();
  }
}

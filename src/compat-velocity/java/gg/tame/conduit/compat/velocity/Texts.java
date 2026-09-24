// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.compat.velocity;

import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentBuilder;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Adventure components as Conduit Text, and back.
 *
 * <p>Carried both ways: literal and translatable components (arguments and fallback included),
 * named and RGB colours, all five decorations as on, off or unset, insertion, show-text hovers, and
 * open-URL, run-command, suggest-command, copy-to-clipboard and change-page clicks. Keybind, score,
 * selector, NBT and object components become their plain rendering, children included, keeping their
 * own style. Fonts, shadow colours, show-item and show-entity hovers, and open-file, dialog, custom
 * and callback clicks are dropped.
 */
final class Texts {
  private Texts() {}

  static String plain(Component component) {
    return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
  }

  static Text toConduit(Component component) {
    if (component == null) return Text.empty();
    Text text;
    boolean withChildren = true;
    if (component instanceof TextComponent literal) {
      text = Text.of(literal.content());
    } else if (component instanceof TranslatableComponent translatable) {
      List<Text> arguments = new ArrayList<>();
      for (TranslationArgument argument : translatable.arguments()) arguments.add(toConduit(argument.asComponent()));
      text = Text.translatable(translatable.key(), arguments.toArray(Text[]::new)).fallback(translatable.fallback());
    } else {
      // Rendered whole, children included, so they are not appended again below.
      text = Text.of(plain(component));
      withChildren = false;
    }
    if (component.color() != null) {
      text = text.color(component.color() instanceof NamedTextColor named
          ? TextColor.parse(NamedTextColor.NAMES.key(named)).orElseThrow()
          : TextColor.rgb(component.color().value()));
    }
    for (TextDecoration decoration : TextDecoration.values()) {
      TextDecoration.State state = component.decoration(decoration);
      if (state != TextDecoration.State.NOT_SET) text = text.decoration(decoration(decoration), state == TextDecoration.State.TRUE);
    }
    if (component.insertion() != null) text = text.insertion(component.insertion());
    Text.ClickEvent click = click(component.clickEvent());
    if (click != null) text = text.click(click);
    HoverEvent<?> hover = component.hoverEvent();
    if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component shown) {
      text = text.hover(toConduit(shown));
    }
    if (withChildren) for (Component child : component.children()) text = text.append(toConduit(child));
    return text;
  }

  /** Conduit Text as an Adventure component: everything Text holds has an exact counterpart. */
  static Component toAdventure(Text text) {
    if (text == null) return Component.empty();
    if (text.translationKey() == null) return style(Component.text().content(text.content()), text).build();
    List<Component> arguments = new ArrayList<>();
    for (Text argument : text.arguments()) arguments.add(toAdventure(argument));
    return style(Component.translatable().key(text.translationKey()).fallback(text.fallback()).arguments(arguments), text).build();
  }

  private static <B extends ComponentBuilder<?, B>> B style(B builder, Text text) {
    if (text.color() != null) {
      builder.color(text.color().isNamed()
          ? NamedTextColor.NAMES.value(text.color().colorName())
          : net.kyori.adventure.text.format.TextColor.color(text.color().rgb()));
    }
    for (TextDecoration decoration : TextDecoration.values()) {
      Boolean state = text.decoration(decoration(decoration));
      if (state != null) builder.decoration(decoration, state);
    }
    if (text.insertion() != null) builder.insertion(text.insertion());
    if (text.clickEvent() != null) builder.clickEvent(click(text.clickEvent()));
    if (text.hover() != null) builder.hoverEvent(HoverEvent.showText(toAdventure(text.hover())));
    for (Text child : text.children()) builder.append(toAdventure(child));
    return builder;
  }

  // Both name the same five decorations.
  private static Text.Decoration decoration(TextDecoration decoration) {
    return Text.Decoration.valueOf(decoration.name());
  }

  /** The click, or null for one Text does not carry. */
  private static Text.ClickEvent click(ClickEvent click) {
    if (click == null) return null;
    ClickEvent.Payload payload = click.payload();
    String value = payload instanceof ClickEvent.Payload.Text string ? string.value()
        : payload instanceof ClickEvent.Payload.Int number ? Integer.toString(number.integer())
        : null;
    if (value == null) return null;
    return switch (click.action()) {
      case OPEN_URL -> Text.ClickEvent.openUrl(value);
      case RUN_COMMAND -> Text.ClickEvent.runCommand(value);
      case SUGGEST_COMMAND -> Text.ClickEvent.suggestCommand(value);
      case COPY_TO_CLIPBOARD -> Text.ClickEvent.copyToClipboard(value);
      case CHANGE_PAGE -> new Text.ClickEvent(Text.ClickEvent.Action.CHANGE_PAGE, value);
      default -> null;
    };
  }

  private static ClickEvent click(Text.ClickEvent click) {
    return switch (click.action()) {
      case OPEN_URL -> ClickEvent.openUrl(click.value());
      case RUN_COMMAND -> ClickEvent.runCommand(click.value());
      case SUGGEST_COMMAND -> ClickEvent.suggestCommand(click.value());
      case COPY_TO_CLIPBOARD -> ClickEvent.copyToClipboard(click.value());
      case CHANGE_PAGE -> {
        try { yield ClickEvent.changePage(Integer.parseInt(click.value())); }
        catch (NumberFormatException notNumber) { yield ClickEvent.changePage(click.value()); }
      }
    };
  }

  /**
   * A signed message as the chat line its chat type makes of it, the way a vanilla client decorates
   * one: the unsigned content where the message has one, else its text, with the sender's name and the
   * target in the places the built-in chat types give them. A type that is not built in is shown as
   * plain chat.
   */
  static Component chat(net.kyori.adventure.chat.SignedMessage message, net.kyori.adventure.chat.ChatType.Bound bound) {
    Component content = message.unsignedContent() != null ? message.unsignedContent() : Component.text(message.message());
    Component name = bound.name();
    Component target = bound.target() == null ? Component.empty() : bound.target();
    return switch (bound.type().key().asString()) {
      case "minecraft:say_command" -> Component.translatable("chat.type.announcement", name, content);
      case "minecraft:emote_command" -> Component.translatable("chat.type.emote", name, content);
      case "minecraft:msg_command_incoming" -> Component.translatable("commands.message.display.incoming", NamedTextColor.GRAY, name, content)
          .decorate(TextDecoration.ITALIC);
      case "minecraft:msg_command_outgoing" -> Component.translatable("commands.message.display.outgoing", NamedTextColor.GRAY, target, content)
          .decorate(TextDecoration.ITALIC);
      case "minecraft:team_msg_command_incoming" -> Component.translatable("chat.type.team.text", target, name, content);
      case "minecraft:team_msg_command_outgoing" -> Component.translatable("chat.type.team.sent", target, name, content);
      default -> Component.translatable("chat.type.text", name, content);
    };
  }
}

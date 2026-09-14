package gg.tame.conduit.compat.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class Texts {
  private Texts() {}
  static String plain(Component component) {
    if (component == null) return "";
    return PlainTextComponentSerializer.plainText().serialize(component);
  }
  static Component text(String value) {
    return Component.text(value == null ? "" : value);
  }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.config.StatusSettings;
import gg.tame.conduit.protocol.DisplayPackets;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.StatusResponder;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Text fidelity: one richly formatted component encoded for every era of client, exactly, as JSON
 * and as network NBT; RGB downsampling; 1.21.5's renamed and validated events; the legacy
 * section-code form; decoding what backends write back into Text, in both key styles; and the
 * status answer and chat packets built from it.
 */
public final class TextFidelityTests {
  public static void main(String[] a) throws Exception { run(); }

  public static void run() throws Exception {
    colours();
    everyEraGetsWhatItCanShow();
    nbtIsTheSameTreeByteForByte();
    snakeCaseEventsDropWhatTheClientWouldReject();
    roundTrips();
    backendTextIsReadRichly();
    legacySectionCodes();
    theServerListIsWrittenForThePingingClient();
    chatPacketsCarryTheEraForm();
    System.out.println("TextFidelityTests OK");
  }

  /**
   * RGB {@code #123456} with explicit underline on and italic off, an insertion, a URL click and a
   * gold hover, and a struck-through translation child with two arguments, a fallback and a
   * copy-to-clipboard click. The Velocity tests send the same component from a plugin.
   */
  static final Text SAMPLE = Text.of("Hi").color(TextColor.rgb(0x123456))
      .decoration(Text.Decoration.UNDERLINED, true).decoration(Text.Decoration.ITALIC, false)
      .insertion("ins")
      .click(Text.ClickEvent.openUrl("https://example.com"))
      .hover(Text.of("tip").color(TextColor.GOLD))
      .append(Text.translatable("chat.type.text", Text.of("A"), Text.of("B")).fallback("fb")
          .decorate(Text.Decoration.STRIKETHROUGH).click(Text.ClickEvent.copyToClipboard("copied")));

  /** 1.7: no insertion, no copy-to-clipboard, no fallback, the nearest named colour, the hover under "value". */
  static final String JSON_17 = "{\"text\":\"Hi\",\"color\":\"dark_gray\",\"italic\":false,\"underlined\":true,"
      + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://example.com\"},"
      + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"tip\",\"color\":\"gold\"}},"
      + "\"extra\":[{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"strikethrough\":true}]}";
  /** 1.8-1.14: as 1.7, with the insertion. */
  static final String JSON_18 = "{\"text\":\"Hi\",\"color\":\"dark_gray\",\"italic\":false,\"underlined\":true,\"insertion\":\"ins\","
      + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://example.com\"},"
      + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"tip\",\"color\":\"gold\"}},"
      + "\"extra\":[{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"strikethrough\":true}]}";
  /** 1.15: the copy-to-clipboard click too. */
  static final String JSON_115 = "{\"text\":\"Hi\",\"color\":\"dark_gray\",\"italic\":false,\"underlined\":true,\"insertion\":\"ins\","
      + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://example.com\"},"
      + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"tip\",\"color\":\"gold\"}},"
      + "\"extra\":[{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"strikethrough\":true,"
      + "\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"copied\"}}]}";
  /** 1.16-1.19.3: RGB, and the hover under "contents". */
  static final String JSON_116 = "{\"text\":\"Hi\",\"color\":\"#123456\",\"italic\":false,\"underlined\":true,\"insertion\":\"ins\","
      + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://example.com\"},"
      + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"tip\",\"color\":\"gold\"}},"
      + "\"extra\":[{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"strikethrough\":true,"
      + "\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"copied\"}}]}";
  /** 1.19.4-1.21.4: the fallback too; NBT from 1.20.3 carries this same tree. */
  static final String JSON_1194 = "{\"text\":\"Hi\",\"color\":\"#123456\",\"italic\":false,\"underlined\":true,\"insertion\":\"ins\","
      + "\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://example.com\"},"
      + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":{\"text\":\"tip\",\"color\":\"gold\"}},"
      + "\"extra\":[{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"fallback\":\"fb\",\"strikethrough\":true,"
      + "\"clickEvent\":{\"action\":\"copy_to_clipboard\",\"value\":\"copied\"}}]}";
  /** 1.21.5 onwards: click_event with a key per action, hover_event with its text under "value". */
  static final String JSON_1215 = "{\"text\":\"Hi\",\"color\":\"#123456\",\"italic\":false,\"underlined\":true,\"insertion\":\"ins\","
      + "\"click_event\":{\"action\":\"open_url\",\"url\":\"https://example.com\"},"
      + "\"hover_event\":{\"action\":\"show_text\",\"value\":{\"text\":\"tip\",\"color\":\"gold\"}},"
      + "\"extra\":[{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"A\"},{\"text\":\"B\"}],\"fallback\":\"fb\",\"strikethrough\":true,"
      + "\"click_event\":{\"action\":\"copy_to_clipboard\",\"value\":\"copied\"}}]}";

  /** What each protocol is sent for {@link #SAMPLE}. */
  static String expected(int protocol) {
    if (protocol >= 770) return JSON_1215;
    if (protocol >= 762) return JSON_1194;
    if (protocol >= 735) return JSON_116;
    if (protocol >= 573) return JSON_115;
    if (protocol >= 47) return JSON_18;
    return JSON_17;
  }

  private static void colours() {
    require(TextColor.parse("red").orElseThrow() == TextColor.RED, "named colours parse to the constant");
    require(TextColor.parse("#12AbEf").orElseThrow().equals(TextColor.rgb(0x12ABEF)), "hex parses in either case");
    require(TextColor.parse("#12345").isEmpty() && TextColor.parse("crimson").isEmpty() && TextColor.parse("#12345g").isEmpty(),
        "anything else is not a colour");
    require(TextColor.rgb(0x12ABEF).colorName().equals("#12abef") && TextColor.RED.colorName().equals("red"), "names on the wire");
    require(!TextColor.rgb(0xFF5555).equals(TextColor.RED) && TextColor.rgb(0xFF5555).nearestNamed() == TextColor.RED,
        "an RGB colour equal to a named one's value is still RGB, and downsamples to it");
    require(TextColor.rgb(0x123456).nearestNamed() == TextColor.DARK_GRAY, "the nearest named colour by RGB distance");
    require(TextColor.rgb(0xFE0000).nearestNamed() == TextColor.DARK_RED, "pure red is nearer dark_red than red's pink");
    require(TextColor.named().size() == 16 && TextColor.named().get(12) == TextColor.RED, "named colours in code order: c is red");
  }

  private static void everyEraGetsWhatItCanShow() {
    for (int protocol : new int[] {5, 47, 340, 393, 404, 477, 573, 578, 735, 754, 759, 761, 762, 763, 764, 765, 767, 769, 770, 773, 775, 776}) {
      String json = TextCodec.toJson(SAMPLE, protocol);
      require(json.equals(expected(protocol)), "protocol " + protocol + ":\n  got      " + json + "\n  expected " + expected(protocol));
    }
  }

  /**
   * From 1.20.3 the same tree travels as network NBT: checked once by hand for booleans (byte tags)
   * and an RGB colour, then for the sample against the NBT of the expected JSON, read back to it.
   */
  private static void nbtIsTheSameTreeByteForByte() throws Exception {
    Text small = Text.of("Hi").color(TextColor.rgb(0x123456)).decorate(Text.Decoration.UNDERLINED);
    ByteArrayOutputStream hand = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(hand)) {
      out.writeByte(10);
      out.writeByte(8); out.writeUTF("text"); out.writeUTF("Hi");
      out.writeByte(8); out.writeUTF("color"); out.writeUTF("#123456");
      out.writeByte(1); out.writeUTF("underlined"); out.writeByte(1);
      out.writeByte(0);
    }
    require(Arrays.equals(written(small, 765), hand.toByteArray()), "hand-built NBT: " + HexFormat.of().formatHex(written(small, 765)));
    for (int protocol : new int[] {765, 766, 767, 769, 770, 776}) {
      byte[] nbt = written(SAMPLE, protocol);
      require(Arrays.equals(nbt, ComponentCodec.jsonToNbtBytes(expected(protocol))), "protocol " + protocol + " NBT");
      require(ComponentCodec.nbtBytesToJson(nbt).equals(expected(protocol)), "protocol " + protocol + " NBT reads back to the JSON");
    }
    for (int protocol : new int[] {47, 340, 754, 764}) {
      ByteArrayOutputStream string = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(string)) { MinecraftOutput.string(out, expected(protocol)); }
      require(Arrays.equals(written(SAMPLE, protocol), string.toByteArray()), "protocol " + protocol + " is a JSON string");
    }
    ByteArrayOutputStream login = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(login)) { TextCodec.write(out, SAMPLE, 776, false); }
    require(MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(login.toByteArray())), 1 << 16).equals(JSON_1215),
        "Login's JSON form in 1.21.5+ has the 1.21.5 shape");
  }

  private static byte[] written(Text text, int protocol) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) { TextCodec.write(out, text, protocol); }
    return bytes.toByteArray();
  }

  /** A 1.21.5 client refuses the whole component over a bad click, so such a click is left off; older ones take it. */
  private static void snakeCaseEventsDropWhatTheClientWouldReject() {
    Text ftp = Text.of("x").click(Text.ClickEvent.openUrl("ftp://example.com"));
    require(TextCodec.toJson(ftp, 770).equals("{\"text\":\"x\"}"), "a non-http URL click is dropped for 1.21.5");
    require(TextCodec.toJson(ftp, 769).equals("{\"text\":\"x\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"ftp://example.com\"}}"),
        "and kept for 1.21.4, which only declines to open it");
    Text section = Text.of("x").click(Text.ClickEvent.runCommand("/say §cred"));
    require(TextCodec.toJson(section, 776).equals("{\"text\":\"x\"}"), "a command chat cannot hold is dropped for 1.21.5+");
    require(TextCodec.toJson(Text.of("x").click(Text.ClickEvent.suggestCommand("/msg ")), 776)
        .equals("{\"text\":\"x\",\"click_event\":{\"action\":\"suggest_command\",\"command\":\"/msg \"}}"), "commands under \"command\"");
    Text page = Text.of("x").click(Text.ClickEvent.changePage(3));
    require(TextCodec.toJson(page, 770).equals("{\"text\":\"x\",\"click_event\":{\"action\":\"change_page\",\"page\":3}}"),
        "a page is a number from 1.21.5");
    require(TextCodec.toJson(page, 765).equals("{\"text\":\"x\",\"clickEvent\":{\"action\":\"change_page\",\"value\":\"3\"}}"),
        "and a string before");
    require(TextCodec.toJson(Text.of("x").click(new Text.ClickEvent(Text.ClickEvent.Action.CHANGE_PAGE, "first")), 770)
        .equals("{\"text\":\"x\"}"), "a page that is not a positive number is dropped for 1.21.5+");
  }

  private static void roundTrips() {
    for (int protocol : new int[] {762, 765, 767, 770, 776}) {
      Text back = TextCodec.fromJson(TextCodec.toJson(SAMPLE, protocol));
      require(back.equals(SAMPLE), "protocol " + protocol + " reads back to the same Text: " + TextCodec.toJson(back, 776));
    }
    Text older = TextCodec.fromJson(TextCodec.toJson(SAMPLE, 754));
    require(older.equals(withoutFallback()), "1.16 keeps all but the fallback");
    Text legacy = TextCodec.fromJson(TextCodec.toJson(SAMPLE, 47));
    require(legacy.color() == TextColor.DARK_GRAY && legacy.hover().equals(SAMPLE.hover()), "1.8 keeps the hover, downsampled colour");
    require(SAMPLE.plain().equals("Hifb"), "plain text reads a translation as its fallback");
  }

  private static Text withoutFallback() {
    Text child = SAMPLE.children().getFirst();
    Text rebuilt = Text.of("Hi").color(TextColor.rgb(0x123456))
        .decoration(Text.Decoration.UNDERLINED, true).decoration(Text.Decoration.ITALIC, false).insertion("ins")
        .click(SAMPLE.clickEvent()).hover(SAMPLE.hover());
    return rebuilt.append(Text.translatable(child.translationKey(), child.arguments().toArray(Text[]::new))
        .decorate(Text.Decoration.STRIKETHROUGH).click(child.clickEvent()));
  }

  /** Kick reasons and descriptions as backends write them, in the pre-1.21.5 and the 1.21.5 key styles. */
  private static void backendTextIsReadRichly() {
    Text banned = TextCodec.fromJson("{\"translate\":\"multiplayer.disconnect.banned\","
        + "\"with\":[{\"text\":\"Griefing\",\"color\":\"#ff00aa\",\"bold\":false},7],\"fallback\":\"Banned\","
        + "\"clickEvent\":{\"action\":\"suggest_command\",\"value\":\"/appeal\"},"
        + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"Appeal here\"},"
        + "\"extra\":[{\"keybind\":\"key.jump\",\"color\":\"red\",\"obfuscated\":true}]}");
    require("multiplayer.disconnect.banned".equals(banned.translationKey()) && "Banned".equals(banned.fallback()), "the translation");
    Text reason = banned.arguments().getFirst();
    require(reason.content().equals("Griefing") && reason.color().equals(TextColor.rgb(0xFF00AA))
        && Boolean.FALSE.equals(reason.decoration(Text.Decoration.BOLD)), "an argument keeps its RGB colour and explicit false");
    require(banned.arguments().get(1).equals(Text.of("7")), "a number argument is its text");
    require(banned.clickEvent().equals(Text.ClickEvent.suggestCommand("/appeal")), "a suggest-command click");
    require(banned.hover().equals(Text.of("Appeal here")), "a hover given as a bare string under \"value\"");
    Text keybind = banned.children().getFirst();
    require(keybind.content().isEmpty() && keybind.color() == TextColor.RED
        && Boolean.TRUE.equals(keybind.decoration(Text.Decoration.OBFUSCATED)), "a keybind keeps its style around empty text");

    Text modern = TextCodec.fromJson("{\"text\":\"x\",\"click_event\":{\"action\":\"run_command\",\"command\":\"spawn\"},"
        + "\"hover_event\":{\"action\":\"show_text\",\"value\":{\"text\":\"h\",\"underlined\":true}}}");
    require(modern.clickEvent().equals(Text.ClickEvent.runCommand("spawn")), "1.21.5's run_command, taken as written");
    require(modern.hover().equals(Text.of("h").decorate(Text.Decoration.UNDERLINED)), "1.21.5's hover_event");
    require(TextCodec.fromJson("{\"text\":\"p\",\"click_event\":{\"action\":\"change_page\",\"page\":5}}").clickEvent()
        .equals(Text.ClickEvent.changePage(5)), "1.21.5's numeric page");
    require(TextCodec.fromJson("{\"text\":\"f\",\"clickEvent\":{\"action\":\"open_file\",\"value\":\"a.png\"}}").clickEvent() == null,
        "a click Text does not carry is dropped");
    require(TextCodec.fromJson("{\"text\":\"both\",\"translate\":\"k\"}").translationKey() == null, "text wins over translate, as in the client");
  }

  private static void legacySectionCodes() {
    Text text = Text.of("a").color(TextColor.rgb(0x123456)).decorate(Text.Decoration.UNDERLINED)
        .append(Text.of("b").decoration(Text.Decoration.UNDERLINED, false).decorate(Text.Decoration.OBFUSCATED),
            Text.of("c").decorate(Text.Decoration.STRIKETHROUGH).decorate(Text.Decoration.BOLD));
    require(TextCodec.toLegacy(text).equals("§8§na§8§kb§8§l§n§mc"),
        "RGB as the nearest code, each decoration its code, an explicit off honoured: " + TextCodec.toLegacy(text));
    require(TextCodec.toLegacy(Text.translatable("k").fallback("shown")).equals("shown"), "a translation as its fallback");
    Text motd = StatusSettings.parseMotd("&nu&mm&kk&r&lb");
    require(motd.children().size() == 4 && Boolean.TRUE.equals(motd.children().get(2).decoration(Text.Decoration.OBFUSCATED))
        && Boolean.TRUE.equals(motd.children().get(2).decoration(Text.Decoration.UNDERLINED)), "&n, &m and &k are kept in a configured MOTD");
  }

  /** The status answer is written in the pinging client's release, including one Conduit has no table for. */
  private static void theServerListIsWrittenForThePingingClient() throws Exception {
    for (int[] pair : new int[][] {{340, 340}, {47, 110}, {765, 765}, {776, 776}}) {
      ProtocolDefinition table = ProtocolDefinition.forVersion(pair[0]);
      ServerListPingEvent ping = new ServerListPingEvent(new InetSocketAddress("127.0.0.1", 1), Optional.empty(), 25565, pair[1],
          SAMPLE, 20, 1, List.of(), "Conduit", pair[1], Optional.empty());
      byte[] answer = StatusResponder.response(table, new byte[] {0x00}, ping);
      String json = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(answer, 1, answer.length - 1)), 1 << 16);
      Object description = ((Map<?, ?>) ComponentCodec.parseJson(json)).get("description");
      require(ComponentCodec.toJson(description).equals(expected(pair[1])), "protocol " + pair[1] + " MOTD: " + json);
    }
  }

  private static void chatPacketsCarryTheEraForm() throws Exception {
    byte[] legacy = PlayPackets.systemChat(ProtocolDefinition.forVersion(47), SAMPLE);
    require(MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(legacy, 1, legacy.length - 1)), 1 << 16).equals(JSON_18),
        "1.8 chat");
    byte[] modern = PlayPackets.systemChat(ProtocolDefinition.forVersion(776), SAMPLE);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(modern))) {
      MinecraftInput.varInt(input);
      require(ComponentCodec.nbtToJson(input).equals(JSON_1215) && !input.readBoolean(), "26.2 system chat, NBT, not the action bar");
    }
    byte[] actionBar = DisplayPackets.actionBar(ProtocolDefinition.forVersion(47), SAMPLE).orElseThrow();
    String line = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(actionBar, 1, actionBar.length - 1)), 1 << 16);
    require(line.equals(ComponentCodec.literalJson("§8§nHi§8§n§mfb")),
        "the 1.8 action bar is section codes: italic off drops nothing, underline inherited: " + line);
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

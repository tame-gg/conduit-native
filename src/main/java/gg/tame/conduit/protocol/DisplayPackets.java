// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.player.BossBar;
import gg.tame.conduit.api.player.Sound;
import gg.tame.conduit.api.player.TabListEntry;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Titles, the action bar, boss bars, the tab-list header and footer, tab-list entries and sounds, built
 * in one client's own protocol for the proxy to send on a plugin's behalf.
 *
 * <p>Which packet carries what is the packet table's to say ({@link ProtocolDefinition#defines}):
 * 1.8-1.16 put every title action behind one Title packet, 1.17 gave each its own packet, boss bars
 * start at 1.9 and the tab-list header at 1.8. A builder returns empty for a client whose release has
 * no such packet, rather than bytes it would misread. Text is a JSON string until 1.20.3 and network
 * NBT from it.
 */
public final class DisplayPackets {
  private DisplayPackets() {}

  // Title packet actions shared by every release that has one.
  private static final int TITLE = 0;
  private static final int SUBTITLE = 1;
  /** 1.11+ only; its arrival moved times, hide and reset up by one. */
  private static final int ACTION_BAR = 2;

  // Boss bar operations, unchanged from 1.9 to 26.2.
  private static final int BOSS_ADD = 0;
  private static final int BOSS_REMOVE = 1;
  private static final int BOSS_PROGRESS = 2;
  private static final int BOSS_NAME = 3;
  private static final int BOSS_STYLE = 4;
  private static final int BOSS_FLAGS = 5;

  /** Chat position 2, "game info", is where 1.8-1.10 show the action bar. */
  private static final byte GAME_INFO = 2;

  public static Optional<byte[]> actionBar(ProtocolDefinition protocol, Text text) throws IOException {
    int number = protocol.version().number();
    if (defines(protocol, PacketKind.PLAY_SET_ACTION_BAR)) return Optional.of(text(protocol, PacketKind.PLAY_SET_ACTION_BAR, text));
    if (defines(protocol, PacketKind.PLAY_TITLE) && ProtocolEras.titleActionBar(number)) {
      return Optional.of(titleText(protocol, ACTION_BAR, text));
    }
    if (ProtocolEras.chatHasPosition(number) && protocol.capabilities().legacyPlayChat()
        && defines(protocol, PacketKind.PLAY_SYSTEM_CHAT)) {
      return Optional.of(packet(protocol, PacketKind.PLAY_SYSTEM_CHAT, output -> {
        MinecraftOutput.string(output, ComponentCodec.literalJson(TextCodec.toLegacy(orEmpty(text))));
        output.writeByte(GAME_INFO);
        if (ProtocolEras.chatHasSender(number)) { output.writeLong(0L); output.writeLong(0L); }
      }));
    }
    return Optional.empty();
  }

  /** Shows {@code title} now, with whatever subtitle and times the client already has. */
  public static Optional<byte[]> title(ProtocolDefinition protocol, Text title) throws IOException {
    if (defines(protocol, PacketKind.PLAY_SET_TITLE_TEXT)) return Optional.of(text(protocol, PacketKind.PLAY_SET_TITLE_TEXT, title));
    if (defines(protocol, PacketKind.PLAY_TITLE)) return Optional.of(titleText(protocol, TITLE, title));
    return Optional.empty();
  }

  /** Sets the subtitle the next title is shown with. */
  public static Optional<byte[]> subtitle(ProtocolDefinition protocol, Text subtitle) throws IOException {
    if (defines(protocol, PacketKind.PLAY_SET_SUBTITLE)) return Optional.of(text(protocol, PacketKind.PLAY_SET_SUBTITLE, subtitle));
    if (defines(protocol, PacketKind.PLAY_TITLE)) return Optional.of(titleText(protocol, SUBTITLE, subtitle));
    return Optional.empty();
  }

  /** Fade-in, stay and fade-out in ticks. */
  public static Optional<byte[]> titleTimes(ProtocolDefinition protocol, int fadeIn, int stay, int fadeOut) throws IOException {
    Body times = output -> { output.writeInt(fadeIn); output.writeInt(stay); output.writeInt(fadeOut); };
    if (defines(protocol, PacketKind.PLAY_SET_TITLE_TIMES)) return Optional.of(packet(protocol, PacketKind.PLAY_SET_TITLE_TIMES, times));
    if (!defines(protocol, PacketKind.PLAY_TITLE)) return Optional.empty();
    int action = ProtocolEras.titleActionBar(protocol.version().number()) ? 3 : 2;
    return Optional.of(packet(protocol, PacketKind.PLAY_TITLE, output -> {
      MinecraftOutput.varInt(output, action);
      times.write(output);
    }));
  }

  /**
   * Hides the title being shown. With {@code reset} the client's subtitle and times go back to its
   * defaults too, which on 1.8-1.16 is a separate action and from 1.17 a flag on Clear Titles.
   */
  public static Optional<byte[]> clearTitle(ProtocolDefinition protocol, boolean reset) throws IOException {
    if (defines(protocol, PacketKind.PLAY_CLEAR_TITLES)) {
      return Optional.of(packet(protocol, PacketKind.PLAY_CLEAR_TITLES, output -> output.writeBoolean(reset)));
    }
    if (defines(protocol, PacketKind.PLAY_TITLE)) {
      int hide = ProtocolEras.titleActionBar(protocol.version().number()) ? 4 : 3;
      return Optional.of(packet(protocol, PacketKind.PLAY_TITLE, output -> MinecraftOutput.varInt(output, reset ? hide + 1 : hide)));
    }
    return Optional.empty();
  }

  /** Adds {@code bar} to the client with everything it currently shows. Adding one it has replaces it. */
  public static Optional<byte[]> bossBarAdd(ProtocolDefinition protocol, BossBar bar) throws IOException {
    return bossBar(protocol, bar.id(), BOSS_ADD, output -> {
      TextCodec.write(output, bar.name(), protocol.version().number());
      output.writeFloat(bar.progress());
      MinecraftOutput.varInt(output, color(bar.color()));
      MinecraftOutput.varInt(output, overlay(bar.overlay()));
      output.writeByte(flags(bar.flags()));
    });
  }

  public static Optional<byte[]> bossBarRemove(ProtocolDefinition protocol, UUID id) throws IOException {
    return bossBar(protocol, id, BOSS_REMOVE, output -> { });
  }

  /**
   * The one update {@code change} needs, read from the bar as it is now. Only for a bar the client
   * already has: an update for one it does not know is a crash in the vanilla client.
   */
  public static Optional<byte[]> bossBarUpdate(ProtocolDefinition protocol, BossBar bar, BossBar.Change change) throws IOException {
    return switch (change) {
      case PROGRESS -> bossBar(protocol, bar.id(), BOSS_PROGRESS, output -> output.writeFloat(bar.progress()));
      case NAME -> bossBar(protocol, bar.id(), BOSS_NAME, output -> TextCodec.write(output, bar.name(), protocol.version().number()));
      case STYLE -> bossBar(protocol, bar.id(), BOSS_STYLE, output -> {
        MinecraftOutput.varInt(output, color(bar.color()));
        MinecraftOutput.varInt(output, overlay(bar.overlay()));
      });
      case FLAGS -> bossBar(protocol, bar.id(), BOSS_FLAGS, output -> output.writeByte(flags(bar.flags())));
    };
  }

  /** The text above and below the tab list. An empty one is how the client is told there is none. */
  public static Optional<byte[]> playerListHeaderAndFooter(ProtocolDefinition protocol, Text header, Text footer) throws IOException {
    if (!defines(protocol, PacketKind.PLAY_TAB_LIST_HEADER)) return Optional.empty();
    return Optional.of(packet(protocol, PacketKind.PLAY_TAB_LIST_HEADER, output -> {
      TextCodec.write(output, orEmpty(header), protocol.version().number());
      TextCodec.write(output, orEmpty(footer), protocol.version().number());
    }));
  }

  // ---- tab-list entries ---------------------------------------------------------------------
  //
  // 1.8-1.19.2 have one Player Info packet with one action per packet; 1.19.3 split it into Player
  // Info Update, whose actions are bits so one packet can carry several, and Player Info Remove. The
  // table says which a release has: only the split releases define PLAY_PLAYER_INFO_REMOVE.

  // Pre-1.19.3 Player Info actions.
  private static final int INFO_ADD = 0;
  private static final int INFO_GAME_MODE = 1;
  private static final int INFO_LATENCY = 2;
  private static final int INFO_DISPLAY_NAME = 3;
  private static final int INFO_REMOVE = 4;
  /** What an add sets on 1.19.3+. Initialize Chat is left out: the proxy's entries have no chat session. */
  private static final int ADD_ACTIONS = PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.UPDATE_GAME_MODE
      | PlayerInfoUpdate.UPDATE_LISTED | PlayerInfoUpdate.UPDATE_LATENCY | PlayerInfoUpdate.UPDATE_DISPLAY_NAME
      | PlayerInfoUpdate.UPDATE_LIST_PRIORITY | PlayerInfoUpdate.UPDATE_HAT;

  /** The actions of {@code wanted} this release's Player Info Update has; list order is 1.21.2+, the hat 1.21.4+. */
  private static int supported(ProtocolDefinition protocol, int wanted) {
    int number = protocol.version().number();
    int actions = wanted;
    if (!ProtocolEras.playerInfoListOrder(number)) actions &= ~PlayerInfoUpdate.UPDATE_LIST_PRIORITY;
    if (!ProtocolEras.playerInfoHat(number)) actions &= ~PlayerInfoUpdate.UPDATE_HAT;
    return actions;
  }

  /** Whether this client can be sent the proxy's tab-list entries at all. */
  public static boolean tabListEntries(ProtocolDefinition protocol) {
    return ProtocolEras.playerInfoByUuid(protocol.version().number()) && defines(protocol, PacketKind.PLAY_PLAYER_INFO_UPDATE);
  }

  /** Adds {@code entries} in one packet. Sent for entries the client has, it sets them again. */
  public static Optional<byte[]> tabListAdd(ProtocolDefinition protocol, List<TabListEntry> entries) throws IOException {
    if (!tabListEntries(protocol) || entries.isEmpty()) return Optional.empty();
    if (splitPlayerInfo(protocol)) return Optional.of(playerInfoUpdate(protocol, supported(protocol, ADD_ACTIONS), entries));
    boolean profileKey = ProtocolEras.playerInfoProfileKey(protocol.version().number());
    return Optional.of(packet(protocol, PacketKind.PLAY_PLAYER_INFO_UPDATE, output -> {
      MinecraftOutput.varInt(output, INFO_ADD);
      MinecraftOutput.varInt(output, entries.size());
      for (TabListEntry entry : entries) {
        GameProfiles.writeUuid(output, entry.id());
        MinecraftOutput.string(output, entry.name());
        GameProfiles.writeProperties(output, properties(entry));
        MinecraftOutput.varInt(output, entry.gameMode());
        MinecraftOutput.varInt(output, entry.latency());
        optionalText(output, protocol, entry.displayName());
        if (profileKey) output.writeBoolean(false);
      }
    }));
  }

  /**
   * The updates that turn {@code before} into {@code after}, two versions of one entry with the same
   * profile: one packet on 1.19.3+, one per changed field before it. A field the release does not have
   * (listed before 1.19.3, list order before 1.21.2, the hat before 1.21.4) sends nothing.
   */
  public static List<byte[]> tabListUpdate(ProtocolDefinition protocol, TabListEntry before, TabListEntry after) throws IOException {
    if (!tabListEntries(protocol)) return List.of();
    boolean gameMode = before.gameMode() != after.gameMode();
    boolean latency = before.latency() != after.latency();
    boolean displayName = !java.util.Objects.equals(before.displayName(), after.displayName());
    if (splitPlayerInfo(protocol)) {
      int actions = supported(protocol, (gameMode ? PlayerInfoUpdate.UPDATE_GAME_MODE : 0)
          | (latency ? PlayerInfoUpdate.UPDATE_LATENCY : 0)
          | (displayName ? PlayerInfoUpdate.UPDATE_DISPLAY_NAME : 0)
          | (before.listed() != after.listed() ? PlayerInfoUpdate.UPDATE_LISTED : 0)
          | (before.listOrder() != after.listOrder() ? PlayerInfoUpdate.UPDATE_LIST_PRIORITY : 0)
          | (before.showHat() != after.showHat() ? PlayerInfoUpdate.UPDATE_HAT : 0));
      return actions == 0 ? List.of() : List.of(playerInfoUpdate(protocol, actions, List.of(after)));
    }
    List<byte[]> packets = new ArrayList<>();
    if (gameMode) packets.add(playerInfoAction(protocol, INFO_GAME_MODE, after.id(), output -> MinecraftOutput.varInt(output, after.gameMode())));
    if (latency) packets.add(playerInfoAction(protocol, INFO_LATENCY, after.id(), output -> MinecraftOutput.varInt(output, after.latency())));
    if (displayName) packets.add(playerInfoAction(protocol, INFO_DISPLAY_NAME, after.id(), output -> optionalText(output, protocol, after.displayName())));
    return packets;
  }

  public static Optional<byte[]> tabListRemove(ProtocolDefinition protocol, List<UUID> ids) throws IOException {
    if (!tabListEntries(protocol) || ids.isEmpty()) return Optional.empty();
    boolean split = splitPlayerInfo(protocol);
    return Optional.of(packet(protocol, split ? PacketKind.PLAY_PLAYER_INFO_REMOVE : PacketKind.PLAY_PLAYER_INFO_UPDATE, output -> {
      if (!split) MinecraftOutput.varInt(output, INFO_REMOVE);
      MinecraftOutput.varInt(output, ids.size());
      for (UUID id : ids) GameProfiles.writeUuid(output, id);
    }));
  }

  private static boolean splitPlayerInfo(ProtocolDefinition protocol) {
    return defines(protocol, PacketKind.PLAY_PLAYER_INFO_REMOVE);
  }

  /** A 1.19.3+ Player Info Update. Each entry's fields follow in action-bit order, as the client reads them. */
  private static byte[] playerInfoUpdate(ProtocolDefinition protocol, int actions, List<TabListEntry> entries) throws IOException {
    return packet(protocol, PacketKind.PLAY_PLAYER_INFO_UPDATE, output -> {
      output.writeByte(actions);
      MinecraftOutput.varInt(output, entries.size());
      for (TabListEntry entry : entries) {
        GameProfiles.writeUuid(output, entry.id());
        if ((actions & PlayerInfoUpdate.ADD_PLAYER) != 0) {
          MinecraftOutput.string(output, entry.name());
          GameProfiles.writeProperties(output, properties(entry));
        }
        if ((actions & PlayerInfoUpdate.UPDATE_GAME_MODE) != 0) MinecraftOutput.varInt(output, entry.gameMode());
        if ((actions & PlayerInfoUpdate.UPDATE_LISTED) != 0) output.writeBoolean(entry.listed());
        if ((actions & PlayerInfoUpdate.UPDATE_LATENCY) != 0) MinecraftOutput.varInt(output, entry.latency());
        if ((actions & PlayerInfoUpdate.UPDATE_DISPLAY_NAME) != 0) optionalText(output, protocol, entry.displayName());
        if ((actions & PlayerInfoUpdate.UPDATE_LIST_PRIORITY) != 0) MinecraftOutput.varInt(output, entry.listOrder());
        if ((actions & PlayerInfoUpdate.UPDATE_HAT) != 0) output.writeBoolean(entry.showHat());
      }
    });
  }

  private static byte[] playerInfoAction(ProtocolDefinition protocol, int action, UUID id, Body body) throws IOException {
    return packet(protocol, PacketKind.PLAY_PLAYER_INFO_UPDATE, output -> {
      MinecraftOutput.varInt(output, action);
      MinecraftOutput.varInt(output, 1);
      GameProfiles.writeUuid(output, id);
      body.write(output);
    });
  }

  private static void optionalText(DataOutputStream output, ProtocolDefinition protocol, Text text) throws IOException {
    output.writeBoolean(text != null);
    if (text != null) TextCodec.write(output, text, protocol.version().number());
  }

  private static List<ProfileProperty> properties(TabListEntry entry) {
    List<ProfileProperty> properties = new ArrayList<>(entry.properties().size());
    for (TabListEntry.Property property : entry.properties()) {
      properties.add(new ProfileProperty(property.name(), property.value(), Optional.ofNullable(property.signature())));
    }
    return properties;
  }

  // ---- sounds -------------------------------------------------------------------------------
  //
  // Always by name, so no release's sound registry is needed. Until 1.19.2 that is Named Sound
  // Effect; 1.19.3 removed it and let Sound Effect and Entity Sound Effect carry the name inline
  // instead of a registry id (an "ID or" field of 0 followed by the sound event).

  /** {@code sound} at a position, fixed-point to an eighth of a block as every release sends it. */
  public static Optional<byte[]> soundAt(ProtocolDefinition protocol, Sound sound,
                                         double x, double y, double z, long seed) throws IOException {
    int number = protocol.version().number();
    Body position = output -> {
      output.writeInt((int) (x * 8.0));
      output.writeInt((int) (y * 8.0));
      output.writeInt((int) (z * 8.0));
    };
    if (ProtocolEras.soundInlineEvent(number)) {
      if (!defines(protocol, PacketKind.PLAY_SOUND_EFFECT)) return Optional.empty();
      return Optional.of(packet(protocol, PacketKind.PLAY_SOUND_EFFECT, output -> {
        inlineSoundEvent(output, sound);
        MinecraftOutput.varInt(output, source(sound.source(), number));
        position.write(output);
        output.writeFloat(sound.volume());
        output.writeFloat(sound.pitch());
        output.writeLong(seed);
      }));
    }
    if (!defines(protocol, PacketKind.PLAY_NAMED_SOUND_EFFECT)) return Optional.empty();
    return Optional.of(packet(protocol, PacketKind.PLAY_NAMED_SOUND_EFFECT, output -> {
      MinecraftOutput.string(output, sound.name());
      if (ProtocolEras.soundCategory(number)) MinecraftOutput.varInt(output, source(sound.source(), number));
      position.write(output);
      output.writeFloat(sound.volume());
      // 1.7-1.9 send pitch as a byte where 63 is normal pitch.
      if (ProtocolEras.soundPitchFloat(number)) output.writeFloat(sound.pitch());
      else output.writeByte(Math.max(0, Math.min(255, (int) (sound.pitch() * 63))));
      if (ProtocolEras.soundSeed(number)) output.writeLong(seed);
    }));
  }

  /**
   * {@code sound} following entity {@code entityId}. Only 1.19.3+ clients take an entity's sound by
   * name; an older one would need the sound's registry id, so it is sent nothing.
   */
  public static Optional<byte[]> soundFollowing(ProtocolDefinition protocol, Sound sound,
                                                int entityId, long seed) throws IOException {
    int number = protocol.version().number();
    if (!ProtocolEras.soundInlineEvent(number) || !defines(protocol, PacketKind.PLAY_ENTITY_SOUND_EFFECT)) return Optional.empty();
    return Optional.of(packet(protocol, PacketKind.PLAY_ENTITY_SOUND_EFFECT, output -> {
      inlineSoundEvent(output, sound);
      MinecraftOutput.varInt(output, source(sound.source(), number));
      MinecraftOutput.varInt(output, entityId);
      output.writeFloat(sound.volume());
      output.writeFloat(sound.pitch());
      output.writeLong(seed);
    }));
  }

  /**
   * Stops sounds named {@code name} (null for any) in {@code source} (null for all). 1.13+ has a
   * packet for it; 1.9.3-1.12.2 the MC|StopSound channel, whose category and then sound name are
   * strings with empty meaning any; 1.7 and 1.8 nothing at all.
   */
  public static Optional<byte[]> stopSound(ProtocolDefinition protocol, String name,
                                           Sound.Source source) throws IOException {
    int number = protocol.version().number();
    if (defines(protocol, PacketKind.PLAY_STOP_SOUND)) {
      return Optional.of(packet(protocol, PacketKind.PLAY_STOP_SOUND, output -> {
        output.writeByte((source != null ? 1 : 0) | (name != null ? 2 : 0));
        if (source != null) MinecraftOutput.varInt(output, source(source, number));
        if (name != null) MinecraftOutput.string(output, name);
      }));
    }
    if (!ProtocolEras.stopSound(number) || !defines(protocol, PacketKind.PLAY_PLUGIN_MESSAGE)) return Optional.empty();
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(payload)) {
      MinecraftOutput.string(output, source == null ? "" : sourceName(source, number));
      MinecraftOutput.string(output, name == null ? "" : name);
    }
    return Optional.of(new PluginMessage("MC|StopSound", payload.toByteArray())
        .encode(protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLUGIN_MESSAGE)));
  }

  /** "ID or Sound Event" with the event inline: 0, then its name and no fixed range. */
  private static void inlineSoundEvent(DataOutputStream output, Sound sound) throws IOException {
    MinecraftOutput.varInt(output, 0);
    MinecraftOutput.string(output, sound.name());
    output.writeBoolean(false);
  }

  /** The category's wire value: declaration order, 0 (master) to 10 (ui); ui only where the release has it. */
  private static int source(Sound.Source source, int protocol) {
    if (source == Sound.Source.UI && !ProtocolEras.soundUiSource(protocol)) return 0;
    return source.ordinal();
  }

  private static String sourceName(Sound.Source source, int protocol) {
    return Sound.Source.values()[source(source, protocol)].name().toLowerCase(java.util.Locale.ROOT);
  }

  private static Optional<byte[]> bossBar(ProtocolDefinition protocol, UUID id, int operation, Body body) throws IOException {
    if (!defines(protocol, PacketKind.PLAY_BOSS_BAR)) return Optional.empty();
    return Optional.of(packet(protocol, PacketKind.PLAY_BOSS_BAR, output -> {
      GameProfiles.writeUuid(output, id);
      MinecraftOutput.varInt(output, operation);
      body.write(output);
    }));
  }

  // Wire values, spelled out rather than taken from declaration order.
  private static int color(BossBar.Color color) {
    return switch (color) {
      case PINK -> 0; case BLUE -> 1; case RED -> 2; case GREEN -> 3; case YELLOW -> 4; case PURPLE -> 5; case WHITE -> 6;
    };
  }

  private static int overlay(BossBar.Overlay overlay) {
    return switch (overlay) {
      case PROGRESS -> 0; case NOTCHED_6 -> 1; case NOTCHED_10 -> 2; case NOTCHED_12 -> 3; case NOTCHED_20 -> 4;
    };
  }

  private static int flags(java.util.Set<BossBar.Flag> flags) {
    int bits = 0;
    for (BossBar.Flag flag : flags) {
      bits |= switch (flag) {
        case DARKEN_SCREEN -> 0x01;
        case PLAY_BOSS_MUSIC -> 0x02;
        case CREATE_WORLD_FOG -> 0x04;
      };
    }
    return bits;
  }

  private static byte[] titleText(ProtocolDefinition protocol, int action, Text text) throws IOException {
    return packet(protocol, PacketKind.PLAY_TITLE, output -> {
      MinecraftOutput.varInt(output, action);
      TextCodec.write(output, orEmpty(text), protocol.version().number());
    });
  }

  private static byte[] text(ProtocolDefinition protocol, PacketKind kind, Text text) throws IOException {
    return packet(protocol, kind, output -> TextCodec.write(output, orEmpty(text), protocol.version().number()));
  }

  private interface Body { void write(DataOutputStream output) throws IOException; }

  private static byte[] packet(ProtocolDefinition protocol, PacketKind kind, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind));
      body.write(output);
    }
    return bytes.toByteArray();
  }

  private static boolean defines(ProtocolDefinition protocol, PacketKind kind) {
    return protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, kind);
  }

  private static Text orEmpty(Text text) { return text == null ? Text.empty() : text; }
}

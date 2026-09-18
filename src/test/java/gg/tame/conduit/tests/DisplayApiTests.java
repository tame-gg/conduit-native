// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.api.event.player.PlayerServerConnectedEvent;
import gg.tame.conduit.api.player.BossBar;
import gg.tame.conduit.api.player.Player;
import gg.tame.conduit.api.player.TabListEntry;
import gg.tame.conduit.api.player.TitleTimes;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.api.text.TextColor;
import gg.tame.conduit.auth.Authenticators;
import gg.tame.conduit.config.AuthenticationSettings;
import gg.tame.conduit.config.BackendServer;
import gg.tame.conduit.config.ConduitConfiguration;
import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.config.HealthSettings;
import gg.tame.conduit.config.OpsSettings;
import gg.tame.conduit.config.TranslationSettings;
import gg.tame.conduit.crypto.RsaKeys;
import gg.tame.conduit.network.MinecraftProxy;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.DisplayPackets;
import gg.tame.conduit.protocol.Handshake;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.TranslationSupport;
import gg.tame.conduit.session.ClientDisplay;
import gg.tame.conduit.session.PlayerSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The proxy's own titles, action bar, boss bars and tab-list header and footer: each packet byte for
 * byte in every protocol family Conduit has a table for, the gate that keeps them from a client with
 * no world to show them in, and scripted sessions that receive them and get them back after a switch,
 * over a 1.8 pair, a 1.20.4 pair that reconfigures, and a 1.8 client Via-translated to 1.12.2.
 */
public final class DisplayApiTests {
  public static void main(String[] a) throws Exception {
    // Via's executors are not daemon threads; run on its own, this JVM would otherwise never exit.
    try { run(); } finally { gg.tame.conduit.viaversion.ConduitViaBootstrap.stop(); }
  }

  public static void run() throws Exception {
    multiplexedTitleFamiliesEncodeByAction();
    splitTitleFamiliesEncodeByPacket();
    aClientWithoutThePacketIsSentNothing();
    bossBarsNameThemselvesAndReportChanges();
    theGateHoldsEverythingUntilTheClientHasAWorld();
    tabListEntriesEncodePerFamily();
    theGateCarriesTabListEntries();
    aRespawnRebuildsALegacyClientsDisplay();
    aLegacyClientSeesItAndGetsItBackAfterASwitch();
    aModernClientGetsItBackAfterReconfiguring();
    aTranslatedClientIsWrittenInItsOwnDialect();
    System.out.println("DisplayApiTests OK");
  }

  private static final Text HI = Text.of("Hi");
  private static final Text SUB = Text.of("Sub");

  // ---------------------------------------------------------------- byte-level encoding

  /**
   * 1.8-1.16: one Title packet, the action a VarInt in front. 1.11 inserted the action bar as 2,
   * which moved times, hide and reset up by one, so 1.8 is checked against its own numbering. Ids are
   * the published ones for each release, including 1.15's, which the derived tables used to carry
   * from 1.14.
   */
  private static void multiplexedTitleFamiliesEncodeByAction() throws Exception {
    record Family(int protocol, int title, int boss, int header) {}
    for (Family family : List.of(new Family(47, 0x45, -1, 0x47), new Family(340, 0x48, 0x0C, 0x4A),
        new Family(393, 0x4B, 0x0C, 0x4E), new Family(477, 0x4F, 0x0C, 0x53), new Family(573, 0x50, 0x0D, 0x54),
        new Family(735, 0x4F, 0x0C, 0x53), new Family(754, 0x4F, 0x0C, 0x53))) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(family.protocol());
      String where = "protocol " + family.protocol();
      int shift = family.protocol() >= 315 ? 1 : 0;
      expect(DisplayPackets.title(p, HI), packet(family.title(), out -> { MinecraftOutput.varInt(out, 0); json(out, "Hi"); }), where + " title");
      expect(DisplayPackets.subtitle(p, SUB), packet(family.title(), out -> { MinecraftOutput.varInt(out, 1); json(out, "Sub"); }), where + " subtitle");
      expect(DisplayPackets.titleTimes(p, 10, 70, 20), packet(family.title(), out -> {
        MinecraftOutput.varInt(out, 2 + shift); out.writeInt(10); out.writeInt(70); out.writeInt(20);
      }), where + " times");
      expect(DisplayPackets.clearTitle(p, false), packet(family.title(), out -> MinecraftOutput.varInt(out, 3 + shift)), where + " hide");
      expect(DisplayPackets.clearTitle(p, true), packet(family.title(), out -> MinecraftOutput.varInt(out, 4 + shift)), where + " reset");
      if (shift == 1) {
        expect(DisplayPackets.actionBar(p, HI), packet(family.title(), out -> { MinecraftOutput.varInt(out, 2); json(out, "Hi"); }), where + " action bar");
      } else {
        // 1.8: a game-info chat line, whose text is all the client renders, so formatting rides in it.
        expect(DisplayPackets.actionBar(p, Text.of("Go").color(TextColor.RED).append(Text.of("!").bold())),
            packet(0x02, out -> { MinecraftOutput.string(out, "{\"text\":\"§cGo§c§l!\"}"); out.writeByte(2); }),
            where + " action bar as chat position 2");
      }
      expect(DisplayPackets.playerListHeaderAndFooter(p, HI, SUB), packet(family.header(), out -> { json(out, "Hi"); json(out, "Sub"); }), where + " header");
      if (family.boss() < 0) require(DisplayPackets.bossBarAdd(p, bar()).isEmpty(), where + " has no boss bars");
      else bossBars(p, family.boss(), false, where);
    }
  }

  /** 1.17+: a packet per title action. JSON through 1.20.2, network NBT from 1.20.3. */
  private static void splitTitleFamiliesEncodeByPacket() throws Exception {
    record Family(int protocol, int title, int subtitle, int times, int actionBar, int clear, int boss, int header) {}
    for (Family family : List.of(
        new Family(755, 0x59, 0x57, 0x5A, 0x41, 0x10, 0x0D, 0x5E),
        new Family(759, 0x5A, 0x58, 0x5B, 0x40, 0x0D, 0x0A, 0x60),
        new Family(761, 0x5B, 0x59, 0x5C, 0x42, 0x0C, 0x0A, 0x61),
        new Family(763, 0x5F, 0x5D, 0x60, 0x46, 0x0E, 0x0B, 0x65),
        new Family(764, 0x61, 0x5F, 0x62, 0x48, 0x0F, 0x0A, 0x68),
        new Family(765, 0x63, 0x61, 0x64, 0x4A, 0x0F, 0x0A, 0x6A),
        new Family(766, 0x65, 0x63, 0x66, 0x4C, 0x0F, 0x0A, 0x6D),
        new Family(767, 0x65, 0x63, 0x66, 0x4C, 0x0F, 0x0A, 0x6D),
        new Family(768, 0x6C, 0x6A, 0x6D, 0x51, 0x0F, 0x0A, 0x74),
        new Family(770, 0x6B, 0x69, 0x6C, 0x50, 0x0E, 0x09, 0x73),
        new Family(773, 0x70, 0x6E, 0x71, 0x55, 0x0E, 0x09, 0x78),
        new Family(775, 0x72, 0x70, 0x73, 0x57, 0x0E, 0x09, 0x7A),
        new Family(776, 0x72, 0x70, 0x73, 0x57, 0x0E, 0x09, 0x7A))) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(family.protocol());
      boolean nbt = family.protocol() >= 765;
      String where = "protocol " + family.protocol();
      require(!p.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TITLE), where + " has no multiplexed Title");
      expect(DisplayPackets.title(p, HI), packet(family.title(), out -> text(out, "Hi", nbt)), where + " title");
      expect(DisplayPackets.subtitle(p, SUB), packet(family.subtitle(), out -> text(out, "Sub", nbt)), where + " subtitle");
      expect(DisplayPackets.titleTimes(p, 10, 70, 20), packet(family.times(), out -> { out.writeInt(10); out.writeInt(70); out.writeInt(20); }), where + " times");
      expect(DisplayPackets.clearTitle(p, false), packet(family.clear(), out -> out.writeBoolean(false)), where + " clear");
      expect(DisplayPackets.clearTitle(p, true), packet(family.clear(), out -> out.writeBoolean(true)), where + " reset");
      expect(DisplayPackets.actionBar(p, HI), packet(family.actionBar(), out -> text(out, "Hi", nbt)), where + " action bar");
      expect(DisplayPackets.playerListHeaderAndFooter(p, HI, SUB), packet(family.header(), out -> { text(out, "Hi", nbt); text(out, "Sub", nbt); }), where + " header");
      bossBars(p, family.boss(), nbt, where);
    }
  }

  /** Add, remove and each of the four updates, byte for byte. */
  private static void bossBars(ProtocolDefinition p, int id, boolean nbt, String where) throws Exception {
    BossBar bar = bar();
    UUID uuid = bar.id();
    expect(DisplayPackets.bossBarAdd(p, bar), packet(id, out -> {
      uuid(out, uuid); MinecraftOutput.varInt(out, 0); text(out, "Boss", nbt); out.writeFloat(0.5f);
      MinecraftOutput.varInt(out, 5); MinecraftOutput.varInt(out, 3); out.writeByte(0x05);
    }), where + " boss bar add");
    expect(DisplayPackets.bossBarRemove(p, uuid), packet(id, out -> { uuid(out, uuid); MinecraftOutput.varInt(out, 1); }), where + " boss bar remove");
    bar.progress(0.25f);
    expect(DisplayPackets.bossBarUpdate(p, bar, BossBar.Change.PROGRESS),
        packet(id, out -> { uuid(out, uuid); MinecraftOutput.varInt(out, 2); out.writeFloat(0.25f); }), where + " boss bar progress");
    bar.name(Text.of("Boss 2"));
    expect(DisplayPackets.bossBarUpdate(p, bar, BossBar.Change.NAME),
        packet(id, out -> { uuid(out, uuid); MinecraftOutput.varInt(out, 3); text(out, "Boss 2", nbt); }), where + " boss bar name");
    bar.color(BossBar.Color.GREEN).overlay(BossBar.Overlay.NOTCHED_20);
    expect(DisplayPackets.bossBarUpdate(p, bar, BossBar.Change.STYLE), packet(id, out -> {
      uuid(out, uuid); MinecraftOutput.varInt(out, 4); MinecraftOutput.varInt(out, 3); MinecraftOutput.varInt(out, 4);
    }), where + " boss bar style");
    bar.flags(Set.of(BossBar.Flag.PLAY_BOSS_MUSIC));
    expect(DisplayPackets.bossBarUpdate(p, bar, BossBar.Change.FLAGS),
        packet(id, out -> { uuid(out, uuid); MinecraftOutput.varInt(out, 5); out.writeByte(0x02); }), where + " boss bar flags");
  }

  /** 1.7 has none of these, 1.8 no boss bars: they are sent nothing rather than a guess. */
  private static void aClientWithoutThePacketIsSentNothing() throws Exception {
    ProtocolDefinition p5 = ProtocolDefinition.forVersion(5);
    require(DisplayPackets.title(p5, HI).isEmpty() && DisplayPackets.subtitle(p5, HI).isEmpty()
        && DisplayPackets.titleTimes(p5, 1, 2, 3).isEmpty() && DisplayPackets.clearTitle(p5, true).isEmpty(), "1.7 has no titles");
    require(DisplayPackets.actionBar(p5, HI).isEmpty(), "1.7 has no action bar, and it is not turned into chat");
    require(DisplayPackets.playerListHeaderAndFooter(p5, HI, SUB).isEmpty(), "1.7 has no tab-list header");
    require(DisplayPackets.bossBarAdd(p5, bar()).isEmpty() && DisplayPackets.bossBarRemove(p5, UUID.randomUUID()).isEmpty(), "1.7 has no boss bars");
    require(DisplayPackets.bossBarUpdate(ProtocolDefinition.forVersion(47), bar(), BossBar.Change.PROGRESS).isEmpty(), "1.8 has no boss bars");
  }

  private static final UUID ENTRY = new UUID(0x1234, 0x5678);

  private static TabListEntry entry(int latency, boolean listed) {
    return new TabListEntry(ENTRY, "Fake", List.of(new TabListEntry.Property("textures", "skin", "sig")), Text.of("Shown"),
        latency, 1, listed, 3, false);
  }

  /**
   * Tab-list entries: 1.8-1.19.2 one action per Player Info packet (1.19-1.19.2 with an absent profile
   * key after an add), 1.19.3+ Player Info Update action bits with Remove split off, list order from
   * 1.21.2 and the hat from 1.21.4. 1.7 names entries by string and 1.20.1 has no Player Info ids in
   * Conduit's table, so neither is sent anything.
   */
  private static void tabListEntriesEncodePerFamily() throws Exception {
    record Legacy(int protocol, int id, boolean profileKey) {}
    for (Legacy family : List.of(new Legacy(47, 0x38, false), new Legacy(340, 0x2E, false), new Legacy(393, 0x30, false),
        new Legacy(754, 0x32, false), new Legacy(759, 0x34, true), new Legacy(760, 0x37, true))) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(family.protocol());
      String where = "protocol " + family.protocol();
      expect(DisplayPackets.tabListAdd(p, List.of(entry(5, true))), packet(family.id(), out -> {
        MinecraftOutput.varInt(out, 0); MinecraftOutput.varInt(out, 1); uuid(out, ENTRY);
        MinecraftOutput.string(out, "Fake"); properties(out);
        MinecraftOutput.varInt(out, 1); MinecraftOutput.varInt(out, 5); out.writeBoolean(true); json(out, "Shown");
        if (family.profileKey()) out.writeBoolean(false);
      }), where + " entry add");
      List<byte[]> updates = DisplayPackets.tabListUpdate(p, entry(5, true), entry(80, false));
      require(updates.size() == 1, where + ": a latency change is one packet, and listed has no field before 1.19.3");
      require(Arrays.equals(updates.get(0), packet(family.id(), out -> {
        MinecraftOutput.varInt(out, 2); MinecraftOutput.varInt(out, 1); uuid(out, ENTRY); MinecraftOutput.varInt(out, 80);
      })), where + " latency update");
      expect(DisplayPackets.tabListRemove(p, List.of(ENTRY)), packet(family.id(), out -> {
        MinecraftOutput.varInt(out, 4); MinecraftOutput.varInt(out, 1); uuid(out, ENTRY);
      }), where + " entry remove");
    }
    record Split(int protocol, int update, int remove, int addActions) {}
    for (Split family : List.of(new Split(761, 0x36, 0x35, 0x3D), new Split(764, 0x3C, 0x3B, 0x3D), new Split(765, 0x3C, 0x3B, 0x3D),
        new Split(768, 0x40, 0x3F, 0x7D), new Split(769, 0x40, 0x3F, 0xFD), new Split(776, 0x46, 0x45, 0xFD))) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(family.protocol());
      boolean nbt = family.protocol() >= 765;
      String where = "protocol " + family.protocol();
      expect(DisplayPackets.tabListAdd(p, List.of(entry(5, true))), packet(family.update(), out -> {
        out.writeByte(family.addActions()); MinecraftOutput.varInt(out, 1); uuid(out, ENTRY);
        MinecraftOutput.string(out, "Fake"); properties(out);
        MinecraftOutput.varInt(out, 1); out.writeBoolean(true); MinecraftOutput.varInt(out, 5);
        out.writeBoolean(true); text(out, "Shown", nbt);
        if ((family.addActions() & 0x40) != 0) MinecraftOutput.varInt(out, 3);
        if ((family.addActions() & 0x80) != 0) out.writeBoolean(false);
      }), where + " entry add");
      List<byte[]> updates = DisplayPackets.tabListUpdate(p, entry(5, true), entry(80, false));
      require(updates.size() == 1 && Arrays.equals(updates.get(0), packet(family.update(), out -> {
        out.writeByte(0x18); MinecraftOutput.varInt(out, 1); uuid(out, ENTRY); out.writeBoolean(false); MinecraftOutput.varInt(out, 80);
      })), where + ": listed and latency in one update, in bit order");
      expect(DisplayPackets.tabListRemove(p, List.of(ENTRY)), packet(family.remove(), out -> {
        MinecraftOutput.varInt(out, 1); uuid(out, ENTRY);
      }), where + " entry remove");
    }
    for (int protocol : new int[] {5, 763}) {
      ProtocolDefinition p = ProtocolDefinition.forVersion(protocol);
      require(!DisplayPackets.tabListEntries(p) && DisplayPackets.tabListAdd(p, List.of(entry(5, true))).isEmpty()
          && DisplayPackets.tabListRemove(p, List.of(ENTRY)).isEmpty(), "protocol " + protocol + " is sent no entries");
    }
  }

  private static void properties(DataOutputStream out) throws IOException {
    MinecraftOutput.varInt(out, 1);
    MinecraftOutput.string(out, "textures");
    MinecraftOutput.string(out, "skin");
    out.writeBoolean(true);
    MinecraftOutput.string(out, "sig");
  }

  private static BossBar bar() {
    return new BossBar(Text.of("Boss"), 0.5f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_12,
        EnumSet.of(BossBar.Flag.DARKEN_SCREEN, BossBar.Flag.CREATE_WORLD_FOG));
  }

  // ---------------------------------------------------------------- the bar and the gate

  private static void bossBarsNameThemselvesAndReportChanges() {
    BossBar one = bar();
    BossBar two = bar();
    require(one.id().version() == 8 && one.id().variant() == 2, "a version-8 IETF UUID, never a v4 a backend would pick: " + one.id());
    require(!one.id().equals(two.id()), "every bar has its own id");
    for (float bad : new float[] {-0.1f, 1.01f, Float.NaN}) {
      try { one.progress(bad); throw new AssertionError("progress " + bad + " accepted"); }
      catch (IllegalArgumentException expected) { }
    }
    List<BossBar.Change> heard = new ArrayList<>();
    BossBar.Listener listener = (bar, change) -> heard.add(change);
    one.addListener(listener);
    one.addListener(listener);
    one.progress(0.5f);                       // unchanged
    one.progress(0.75f).name(Text.of("x")).color(BossBar.Color.RED).overlay(BossBar.Overlay.PROGRESS)
        .flags(Set.of(BossBar.Flag.DARKEN_SCREEN));
    require(heard.equals(List.of(BossBar.Change.PROGRESS, BossBar.Change.NAME, BossBar.Change.STYLE,
        BossBar.Change.STYLE, BossBar.Change.FLAGS)), "one event per real change, a listener added twice heard once: " + heard);
    one.removeListener(listener);
    one.progress(0.1f);
    require(heard.size() == 5, "a removed listener hears nothing");
    require(one.viewers().isEmpty(), "a plain listener is not a viewer");
  }

  /**
   * The display writes nothing until Join Game, then everything, in order; Start Configuration shuts
   * it until the next Join Game, and what happened meanwhile is replayed: a bar hidden then is
   * removed, one shown then is added, a title sent then is shown.
   */
  private static void theGateHoldsEverythingUntilTheClientHasAWorld() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    Player viewer = dummyPlayer();
    List<byte[]> wire = new ArrayList<>();
    ClientDisplay display = new ClientDisplay(viewer, p, wire::add);
    byte[] joinGame = PlayPackets.idOnly(p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN));
    byte[] reconfigure = PlayPackets.startConfiguration(p);

    BossBar bar = bar();
    display.show(bar);
    display.title(Text.of("Early"));
    display.headerAndFooter(Text.of("Top"), Text.of("Bottom"));
    bar.progress(0.9f);
    require(wire.isEmpty(), "nothing reaches a client before its Join Game: " + wire.size());
    require(bar.viewers().equals(Set.of(viewer)), "the player is a viewer before the bar is on screen");
    // Neither half of the gate reacts to Configuration or Login packets that share an id.
    display.afterWrite(ConnectionState.CONFIGURATION, joinGame);
    require(wire.isEmpty(), "a Configuration packet with Join Game's id opens nothing");

    enter(display, joinGame);
    require(wire.size() == 3, "bar, header and the held title after Join Game: " + wire.size());
    require(bossOperation(wire.get(0)) == 0 && bossProgress(wire.get(0)) == 0.9f, "the bar is added as it is now");
    require(PlayPackets.packetId(wire.get(1)) == 0x6A && PlayPackets.packetId(wire.get(2)) == 0x63, "then header, then the title");
    wire.clear();
    bar.progress(0.1f);
    require(wire.size() == 1 && bossOperation(wire.get(0)) == 2, "a change is one update");

    display.beforeWrite(ConnectionState.PLAY, reconfigure);
    wire.clear();
    BossBar second = bar();
    bar.progress(0.2f);
    display.hide(bar);
    display.show(second);
    display.title(Text.of("Late"));
    display.headerAndFooter(Text.empty(), Text.empty());
    require(wire.isEmpty(), "nothing reaches a client that is reconfiguring");
    require(bar.viewers().isEmpty() && second.viewers().equals(Set.of(viewer)), "viewers follow show and hide at once");

    enter(display, joinGame);
    require(wire.size() == 4, "remove, add, cleared header, title: " + wire.size());
    require(bossOperation(wire.get(0)) == 1 && bossId(wire.get(0)).equals(bar.id()), "the bar hidden meanwhile is removed");
    require(bossOperation(wire.get(1)) == 0 && bossId(wire.get(1)).equals(second.id()), "the bar shown meanwhile is added");
    require(Arrays.equals(wire.get(2), DisplayPackets.playerListHeaderAndFooter(p, Text.empty(), Text.empty()).orElseThrow()),
        "the header cleared meanwhile is cleared");
    require(PlayPackets.packetId(wire.get(3)) == 0x63, "and the title");
    wire.clear();
    enter(display, joinGame);
    require(wire.size() == 1 && bossId(wire.get(0)).equals(second.id()), "a cleared header is not sent again, the bar is");

    display.close();
    wire.clear();
    second.progress(1f);
    display.title(HI);
    require(wire.isEmpty() && second.viewers().isEmpty(), "a closed display lets go of every bar and writes nothing");
  }

  /**
   * Entries through the gate: added after Join Game, a changed field is an update, a changed profile a
   * remove and an add, and what was removed while the client was reconfiguring is removed on its return.
   */
  private static void theGateCarriesTabListEntries() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    List<byte[]> wire = new ArrayList<>();
    ClientDisplay display = new ClientDisplay(dummyPlayer(), p, wire::add);
    byte[] joinGame = PlayPackets.idOnly(0x29);
    display.addEntry(entry(5, true));
    require(wire.isEmpty(), "no entry before Join Game");
    enter(display, joinGame);
    require(wire.size() == 1 && Arrays.equals(wire.get(0), DisplayPackets.tabListAdd(p, List.of(entry(5, true))).orElseThrow()),
        "the entry is added after Join Game");
    wire.clear();
    display.addEntry(entry(80, true));
    require(wire.size() == 1 && PlayPackets.packetId(wire.get(0)) == 0x3C && wire.get(0)[1] == 0x10, "a new latency is an update, not an add");
    wire.clear();
    TabListEntry renamed = new TabListEntry(ENTRY, "Other", List.of(), null, 80, 1, true, 3, false);
    display.addEntry(renamed);
    require(wire.size() == 2 && PlayPackets.packetId(wire.get(0)) == 0x3B && PlayPackets.packetId(wire.get(1)) == 0x3C,
        "a new profile is a remove and an add, since 1.19.3+ keeps the first profile");
    wire.clear();

    display.beforeWrite(ConnectionState.PLAY, PlayPackets.startConfiguration(p));
    require(display.removeEntry(ENTRY) && !display.removeEntry(ENTRY), "an entry is removed once");
    TabListEntry second = new TabListEntry(new UUID(9, 9), "Second", List.of(), null, 1, 0, true, 0, true);
    display.addEntry(second);
    require(wire.isEmpty(), "nothing while reconfiguring");
    enter(display, joinGame);
    require(wire.size() == 2 && Arrays.equals(wire.get(0), DisplayPackets.tabListRemove(p, List.of(ENTRY)).orElseThrow())
        && Arrays.equals(wire.get(1), DisplayPackets.tabListAdd(p, List.of(second)).orElseThrow()),
        "the entry removed meanwhile is removed, the one added meanwhile added");
    require(display.entries().equals(List.of(second)), "the proxy's entries");
    display.close();
    require(display.entries().isEmpty(), "a closed display forgets its entries");
  }

  /** A client with no Configuration phase rebuilds its world on Respawn; the proxy's are sent again. */
  private static void aRespawnRebuildsALegacyClientsDisplay() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(47);
    List<byte[]> wire = new ArrayList<>();
    ClientDisplay display = new ClientDisplay(dummyPlayer(), p, wire::add);
    display.headerAndFooter(Text.of("Top"), Text.empty());
    display.actionBar(Text.of("held"));
    enter(display, PlayPackets.idOnly(0x01));
    require(wire.size() == 2 && PlayPackets.packetId(wire.get(0)) == 0x47 && PlayPackets.packetId(wire.get(1)) == 0x02,
        "1.8 Join Game: header, then the held action bar");
    wire.clear();
    enter(display, PlayPackets.idOnly(0x07));
    require(wire.size() == 1 && PlayPackets.packetId(wire.get(0)) == 0x47, "1.8 Respawn: header again");
    wire.clear();
    ProtocolDefinition modern = ProtocolDefinition.forVersion(765);
    ClientDisplay configured = new ClientDisplay(dummyPlayer(), modern, wire::add);
    configured.headerAndFooter(Text.of("Top"), Text.empty());
    enter(configured, PlayPackets.idOnly(0x29));
    wire.clear();
    enter(configured, PlayPackets.idOnly(modern.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN)));
    require(wire.isEmpty(), "a 1.20.2+ Respawn keeps the display, so nothing is re-sent");
  }

  private static void enter(ClientDisplay display, byte[] packet) {
    display.beforeWrite(ConnectionState.PLAY, packet);
    display.afterWrite(ConnectionState.PLAY, packet);
  }

  // ---------------------------------------------------------------- scripted sessions

  /**
   * A 1.8 client over a 1.8 pair, DIRECT: titles in the Title packet's 1.8 numbering, the action bar
   * as game-info chat, the header after the new backend's Join Game when it switches, and a boss bar
   * it cannot display still counted until it disconnects.
   */
  private static void aLegacyClientSeesItAndGetsItBackAfterASwitch() throws Exception {
    BossBar bar = bar();
    try (NativeApiTests.Backend lobby = new NativeApiTests.Backend("lobby"); NativeApiTests.Backend survival = new NativeApiTests.Backend("survival");
         NativeApiTests.Fixture proxy = new NativeApiTests.Fixture(List.of(lobby, survival), List.of("lobby"), List.of("lobby"))) {
      try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "viewer")) {
        require(proxy.recorder.await(PlayerServerConnectedEvent.class, 1), "joined");
        require(client.await(packet -> NativeApiTests.id(packet) == 0x01), "Join Game");
        Player player = proxy.runtime.player("viewer").orElseThrow();
        player.showTitle(Text.of("Hello").color(TextColor.GOLD), SUB, new TitleTimes(5, 40, 5));
        player.sendActionBar(Text.of("Go").color(TextColor.RED));
        player.sendPlayerListHeaderAndFooter(Text.of("Top"), Text.of("Bottom"));
        player.showBossBar(bar);
        player.addTabListEntry(entry(5, true));
        require(client.await(packet -> legacyInfo(packet, 0x38, 0)), "the tab-list entry, as a 1.8 Player Info add");
        require(client.received(packet -> NativeApiTests.id(packet) == 0x47).size() == 1, "the header");
        List<byte[]> titles = client.received(packet -> NativeApiTests.id(packet) == 0x45);
        require(titles.size() == 3 && titleAction(titles.get(0)) == 2 && titleAction(titles.get(1)) == 1 && titleAction(titles.get(2)) == 0,
            "times, subtitle, title, in 1.8's numbering: " + titles.stream().map(DisplayApiTests::titleAction).toList());
        require(jsonAfterAction(titles.get(2)).contains("\"color\":\"gold\""), "the title keeps its colour");
        List<byte[]> bars = client.received(packet -> NativeApiTests.id(packet) == 0x02 && packet[packet.length - 1] == 2);
        require(bars.size() == 1 && chatJson(bars.get(0)).equals("{\"text\":\"§cGo\"}"), "the action bar as position-2 chat");
        require(player.playerListHeader().plain().equals("Top") && player.playerListFooter().plain().equals("Bottom"), "the header is remembered");
        require(bar.viewers().contains(player), "a 1.8 viewer is counted though it has no boss bar to show");

        int joins = client.received(packet -> NativeApiTests.id(packet) == 0x01).size();
        require(player.connectWithResult(proxy.runtime.servers().getServer("survival").orElseThrow()).get(15, TimeUnit.SECONDS).successful(), "switched");
        require(NativeApiTests.waitFor(() -> afterJoin(client.received(packet -> true), 0x01, joins + 1,
            packet -> NativeApiTests.id(packet) == 0x47), 10_000), "the header again after the new backend's Join Game");
        require(NativeApiTests.waitFor(() -> afterJoin(client.received(packet -> true), 0x01, joins + 1,
            packet -> legacyInfo(packet, 0x38, 0)), 10_000), "and the entry, which the 1.8 client may have kept, set again");
        require(player.removeTabListEntry(ENTRY) && player.tabListEntries().isEmpty(), "the entry is the proxy's to remove");
        require(client.await(packet -> legacyInfo(packet, 0x38, 4)), "the entry is removed from the client");
      }
      require(NativeApiTests.waitFor(() -> bar.viewers().isEmpty(), 10_000), "a player who disconnects stops viewing the bar");
    }
  }

  /** True once the {@code join}th Join Game (by id) has been followed by a packet {@code match} accepts. */
  static boolean afterJoin(List<byte[]> received, int joinGame, int join, Predicate<byte[]> match) {
    int seen = 0;
    for (byte[] packet : received) {
      if (NativeApiTests.id(packet) == joinGame) seen++;
      else if (seen >= join && match.test(packet)) return true;
    }
    return false;
  }

  /** A pre-1.19.3 Player Info packet with this action for the test entry. */
  static boolean legacyInfo(byte[] packet, int id, int action) {
    try {
      if (NativeApiTests.id(packet) != id) return false;
      DataInputStream input = body(packet);
      return MinecraftInput.varInt(input) == action && MinecraftInput.varInt(input) == 1 && new UUID(input.readLong(), input.readLong()).equals(ENTRY);
    } catch (IOException unreadable) {
      return false;
    }
  }

  /** A 1.19.3+ Player Info Update adding the test entry. */
  static boolean splitInfoAdd(byte[] packet, int id) {
    try {
      if (NativeApiTests.id(packet) != id) return false;
      DataInputStream input = body(packet);
      return (input.readUnsignedByte() & 0x01) != 0 && MinecraftInput.varInt(input) == 1 && new UUID(input.readLong(), input.readLong()).equals(ENTRY);
    } catch (IOException unreadable) {
      return false;
    }
  }

  /**
   * A 1.20.4 client over a 1.20.4 pair, DIRECT, switched: the switch sends it back into Configuration,
   * where nothing of the proxy's may reach it (a Play id there is a different packet). What changed
   * meanwhile arrives after the new Join Game: the bar as it now is, the header, the title.
   */
  private static void aModernClientGetsItBackAfterReconfiguring() throws Exception {
    ProtocolDefinition p = ProtocolDefinition.forVersion(765);
    int configOut = p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_PLUGIN_MESSAGE);
    byte finishOut = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, PacketKind.CONFIGURATION_FINISH);
    byte finishIn = (byte) p.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER, PacketKind.CONFIGURATION_FINISH);
    int joinGame = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    int startConfiguration = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_START_CONFIGURATION);
    int chatCommand = p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CHAT_COMMAND);
    byte configurationAck = (byte) p.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIGURATION_ACKNOWLEDGED);
    int boss = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_BOSS_BAR);
    int header = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_TAB_LIST_HEADER);
    int title = p.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_SET_TITLE_TEXT);
    BossBar bar = new BossBar(Text.of("Raid"), 0.25f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);

    try (ServerSocket lobbyListener = new ServerSocket(0); ServerSocket otherListener = new ServerSocket(0)) {
      ModLoaderTests.Mock lobby = new ModLoaderTests.Mock(lobbyListener, "lobby", new byte[0], configOut, finishOut, finishIn);
      ModLoaderTests.Mock other = new ModLoaderTests.Mock(otherListener, "other", new byte[0], configOut, finishOut, finishIn);
      platform("display-lobby", lobby);
      platform("display-other", other);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", ModLoaderTests.reservePort()), 8192,
          ForwardingMode.NONE, Optional.empty(),
          List.of(new BackendServer("lobby", new InetSocketAddress("127.0.0.1", lobbyListener.getLocalPort())),
              new BackendServer("other", new InetSocketAddress("127.0.0.1", otherListener.getLocalPort()))),
          List.of("lobby"), List.of());
      try (MinecraftProxy proxy = new MinecraftProxy(configuration)) {
        platform("display-serve", () -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
          client.setSoTimeout(15_000);
          InputStream in = client.getInputStream();
          OutputStream out = client.getOutputStream();
          MinecraftFrames.write(out, new Handshake(765, "localhost", 25565, 2).encode());
          MinecraftFrames.write(out, ModLoaderTests.loginStart());
          require(MinecraftFrames.read(in, 8192)[0] == 0x02, "Login Success");
          MinecraftFrames.write(out, new byte[] {0x03});
          ModLoaderTests.readConfiguration(in, finishOut);
          MinecraftFrames.write(out, new byte[] {finishIn});
          readUntil(in, packet -> packet[0] == (byte) joinGame);

          Player player = proxy.runtime().player("playr").orElseThrow();
          player.showBossBar(bar);
          player.sendPlayerListHeaderAndFooter(Text.of("Top"), Text.of("Bottom"));
          player.addTabListEntry(entry(5, true));
          List<byte[]> first = readUntil(in, packet -> splitInfoAdd(packet, 0x3C));
          require(first.stream().anyMatch(packet -> packet[0] == (byte) boss && bossOperation(packet) == 0), "the bar is added");
          require(first.stream().anyMatch(packet -> packet[0] == (byte) header), "the header is set");

          MinecraftFrames.write(out, ModLoaderTests.command(chatCommand, "server other"));
          readUntil(in, packet -> packet[0] == (byte) startConfiguration);
          // Conduit has written Start Configuration: anything shown now must wait for the new world.
          bar.progress(0.75f);
          player.sendTitle(Text.of("Welcome"));
          MinecraftFrames.write(out, new byte[] {configurationAck});
          List<byte[]> configuring = ModLoaderTests.readConfiguration(in, finishOut);
          require(configuring.stream().noneMatch(packet -> packet[0] == (byte) boss || packet[0] == (byte) header || packet[0] == (byte) title
              || packet[0] == 0x3C), "nothing of the proxy's reaches a client in Configuration");
          MinecraftFrames.write(out, new byte[] {finishIn});
          List<byte[]> after = readUntil(in, packet -> packet[0] == (byte) title);
          int join = indexOf(after, packet -> packet[0] == (byte) joinGame);
          int added = indexOf(after, packet -> packet[0] == (byte) boss && bossOperation(packet) == 0);
          int headed = indexOf(after, packet -> packet[0] == (byte) header);
          int listed = indexOf(after, packet -> splitInfoAdd(packet, 0x3C));
          require(join >= 0 && added > join && headed > join && listed > join, "bar, header and entry come again after the new Join Game");
          require(bossProgress(after.get(added)) == 0.75f, "the bar comes back as it now is");
          require(bar.viewers().equals(Set.of(player)), "still one viewer after the switch");
        }
        require(NativeApiTests.waitFor(() -> bar.viewers().isEmpty(), 10_000), "the disconnected player stops viewing the bar");
      }
    }
  }

  /**
   * A 1.8 client on 1.12.2 backends, Via translating. The proxy's packets bypass the translator and
   * are built in the client's 1.8 dialect; the gate opens on the Join Game Via produced from the
   * backend's 1.12.2 one, and again after the switch to a second 1.12.2 backend.
   */
  private static void aTranslatedClientIsWrittenInItsOwnDialect() throws Exception {
    try (Server1122 lobby = new Server1122(); Server1122 survival = new Server1122()) {
      OpsSettings ops = new OpsSettings(OpsSettings.CURRENT_SCHEMA, null, new HealthSettings(false, 10_000, 1_500, 3, 2), null, null, null, null,
          new TranslationSettings(true, TranslationSettings.TranslationEngine.VIA_PREFERRED, true, true, false, "via"), null);
      ConduitConfiguration configuration = new ConduitConfiguration(new InetSocketAddress("127.0.0.1", ModLoaderTests.reservePort()), 1 << 20,
          ForwardingMode.NONE, Optional.empty(), List.of(lobby.server("lobby"), survival.server("survival")), List.of("lobby"), List.of("lobby"),
          AuthenticationSettings.offline(), Optional.empty(), ops);
      try (MinecraftProxy proxy = new MinecraftProxy(configuration, Authenticators.create(configuration.authentication()), RsaKeys.generate(),
          TempFiles.dir("conduit-display-via").resolve("plugins"))) {
        platform("display-via-serve", () -> { try { proxy.serve(); } catch (Exception ignored) { } });
        try (NativeApiTests.Client client = NativeApiTests.Client.join(proxy.port(), "translated")) {
          require(client.await(packet -> NativeApiTests.id(packet) == 0x01), "a 1.8 Join Game, translated from 1.12.2's");
          PlayerSession player = (PlayerSession) proxy.runtime().player("translated").orElseThrow();
          require(player.translationSupport() == TranslationSupport.TRANSLATED && player.backendProtocol() == 340,
              "the session is Via-translated to 1.12.2: " + player.translationSupport() + " " + player.backendProtocol());
          player.sendTitle(HI);
          player.sendPlayerListHeaderAndFooter(Text.of("Top"), Text.empty());
          require(client.await(packet -> NativeApiTests.id(packet) == 0x47), "the header, as 1.8's packet");
          require(client.received(packet -> NativeApiTests.id(packet) == 0x45 && titleAction(packet) == 0).size() == 1,
              "the title, as 1.8's Title packet rather than 1.12.2's");

          int joins = client.received(packet -> NativeApiTests.id(packet) == 0x01).size();
          require(player.connectWithResult(proxy.runtime().servers().getServer("survival").orElseThrow()).get(15, TimeUnit.SECONDS).successful(),
              "switched to the second 1.12.2 backend");
          require(NativeApiTests.waitFor(() -> afterJoin(client.received(packet -> true), 0x01, joins + 1,
              packet -> NativeApiTests.id(packet) == 0x47), 10_000), "the header again after the Join Game Via made from the new backend's");
        }
      }
    }
  }

  /** A scripted 1.12.2 server: tells the status probe it is protocol 340, logs a player in, sends Join Game, then listens. */
  static final class Server1122 implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0);
    private final List<Socket> sockets = java.util.Collections.synchronizedList(new ArrayList<>());

    Server1122() throws IOException {
      platform("display-1122", () -> {
        try {
          while (true) {
            Socket socket = listener.accept();
            sockets.add(socket);
            platform("display-1122-session", () -> serve(socket));
          }
        } catch (IOException closed) { }
      });
    }

    BackendServer server(String name) { return new BackendServer(name, new InetSocketAddress("127.0.0.1", listener.getLocalPort())); }

    private void serve(Socket socket) {
      try (socket) {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        if (Handshake.decode(MinecraftFrames.read(in, 4096)).nextState() == 1) {
          MinecraftFrames.read(in, 4096);
          MinecraftFrames.write(out, packet(0x00, body -> MinecraftOutput.string(body,
              "{\"version\":{\"name\":\"1.12.2\",\"protocol\":340},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"\"}}")));
          return;
        }
        byte[] start = MinecraftFrames.read(in, 4096);
        String name = MinecraftInput.string(body(start), 16);
        MinecraftFrames.write(out, packet(0x02, body -> {
          MinecraftOutput.string(body, UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
          MinecraftOutput.string(body, name);
        }));
        // 1.12.2 Join Game: entity, game mode, dimension (int), difficulty, max players, level type, reduced debug.
        MinecraftFrames.write(out, packet(0x23, body -> {
          body.writeInt(1); body.writeByte(0); body.writeInt(0); body.writeByte(1); body.writeByte(20);
          MinecraftOutput.string(body, "default"); body.writeBoolean(false);
        }));
        while (true) MinecraftFrames.read(in, 1 << 20);
      } catch (IOException ended) { }
    }

    @Override public void close() throws IOException {
      listener.close();
      synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
    }
  }

  private static List<byte[]> readUntil(InputStream in, Predicate<byte[]> last) throws IOException {
    List<byte[]> read = new ArrayList<>();
    while (true) {
      byte[] packet = MinecraftFrames.read(in, 1 << 20);
      read.add(packet);
      if (last.test(packet)) return read;
    }
  }

  private static int indexOf(List<byte[]> packets, Predicate<byte[]> match) {
    for (int index = 0; index < packets.size(); index++) if (match.test(packets.get(index))) return index;
    return -1;
  }

  // ---------------------------------------------------------------- packet helpers

  interface Body { void write(DataOutputStream out) throws IOException; }

  static byte[] packet(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      body.write(out);
    }
    return bytes.toByteArray();
  }

  private static void json(DataOutputStream out, String text) throws IOException {
    MinecraftOutput.string(out, "{\"text\":\"" + text + "\"}");
  }

  /** A plain text component: a JSON string, or from 1.20.3 a nameless compound with one string tag. */
  private static void text(DataOutputStream out, String text, boolean nbt) throws IOException {
    if (!nbt) { json(out, text); return; }
    out.writeByte(10);
    out.writeByte(8);
    out.writeUTF("text");
    out.writeUTF(text);
    out.writeByte(0);
  }

  private static void uuid(DataOutputStream out, UUID id) throws IOException {
    out.writeLong(id.getMostSignificantBits());
    out.writeLong(id.getLeastSignificantBits());
  }

  private static void expect(Optional<byte[]> actual, byte[] expected, String what) {
    require(actual.isPresent(), what + ": nothing was built");
    require(Arrays.equals(actual.get(), expected), what + ": expected " + HexFormat.of().formatHex(expected)
        + " got " + HexFormat.of().formatHex(actual.get()));
  }

  static DataInputStream body(byte[] packet) throws IOException {
    DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet));
    MinecraftInput.varInt(input);
    return input;
  }

  static int bossOperation(byte[] packet) {
    try { DataInputStream input = body(packet); input.readLong(); input.readLong(); return MinecraftInput.varInt(input); }
    catch (IOException unreadable) { return -1; }
  }

  static UUID bossId(byte[] packet) throws IOException {
    DataInputStream input = body(packet);
    return new UUID(input.readLong(), input.readLong());
  }

  /** The progress of a boss-bar Add in 1.20.3+ form: its NBT name is skipped by reading it with the codec's own reader. */
  static float bossProgress(byte[] packet) throws IOException {
    DataInputStream input = body(packet);
    input.readLong(); input.readLong(); MinecraftInput.varInt(input);
    gg.tame.conduit.protocol.text.ComponentCodec.nbtToJson(input);
    return input.readFloat();
  }

  static int titleAction(byte[] packet) {
    try { return MinecraftInput.varInt(body(packet)); } catch (IOException unreadable) { return -1; }
  }

  static String jsonAfterAction(byte[] packet) {
    try { DataInputStream input = body(packet); MinecraftInput.varInt(input); return MinecraftInput.string(input, 1 << 16); }
    catch (IOException unreadable) { return ""; }
  }

  private static String chatJson(byte[] packet) {
    try { return MinecraftInput.string(body(packet), 1 << 16); } catch (IOException unreadable) { return ""; }
  }

  /** A Player that is only an identity, for driving the display without a session. */
  static Player dummyPlayer() {
    return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
        (self, method, arguments) -> switch (method.getName()) {
          case "hashCode" -> System.identityHashCode(self);
          case "equals" -> self == arguments[0];
          case "toString" -> "dummy player";
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  static void platform(String name, Runnable body) {
    Thread thread = new Thread(body, name);
    thread.setDaemon(true);
    thread.start();
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

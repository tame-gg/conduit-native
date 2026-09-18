// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.GameProfiles;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.semantic.SemanticPlayerInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 393 action-based player_info ↔ 765 bitflag Player Info Update.
 * Public layouts: PrismarineJS 1.13 + 1.20.4 protocol.json.
 */
public final class PlayerInfoCodec {
  private static final int MAX_ENTRIES = 1024;

  private PlayerInfoCodec() {}

  public static SemanticPlayerInfo decode(ProtocolDefinition protocol, byte[] packet) throws IOException {
    if (protocol.version().number() <= 404) return decode393(packet);
    return decode765(protocol, packet);
  }

  public static byte[] encode(ProtocolDefinition protocol, SemanticPlayerInfo info) throws IOException {
    if (protocol.version().number() <= 404) return encode393(protocol, info);
    return encode765(protocol, info);
  }

  private static SemanticPlayerInfo decode393(byte[] packet) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(in);
      int action = MinecraftInput.varInt(in);
      int count = MinecraftInput.varInt(in);
      if (count < 0 || count > MAX_ENTRIES) throw new IOException("player info count " + count);
      SemanticPlayerInfo.Action act = switch (action) {
        case 0 -> SemanticPlayerInfo.Action.ADD_PLAYER;
        case 1 -> SemanticPlayerInfo.Action.UPDATE_GAME_MODE;
        case 2 -> SemanticPlayerInfo.Action.UPDATE_LATENCY;
        case 3 -> SemanticPlayerInfo.Action.UPDATE_DISPLAY_NAME;
        case 4 -> SemanticPlayerInfo.Action.REMOVE_PLAYER;
        default -> throw new IOException("unknown 393 player info action " + action);
      };
      List<SemanticPlayerInfo.Entry> entries = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        UUID uuid = GameProfiles.readUuid(in);
        Optional<String> name = Optional.empty();
        List<ProfileProperty> props = List.of();
        Optional<Integer> gameMode = Optional.empty();
        Optional<Integer> latency = Optional.empty();
        Optional<String> display = Optional.empty();
        switch (act) {
          case ADD_PLAYER -> {
            name = Optional.of(MinecraftInput.string(in, 16));
            props = GameProfiles.readProperties(in);
            gameMode = Optional.of(MinecraftInput.varInt(in));
            latency = Optional.of(MinecraftInput.varInt(in));
            if (in.readBoolean()) display = Optional.of(MinecraftInput.string(in, 262144));
          }
          case UPDATE_GAME_MODE -> gameMode = Optional.of(MinecraftInput.varInt(in));
          case UPDATE_LATENCY -> latency = Optional.of(MinecraftInput.varInt(in));
          case UPDATE_DISPLAY_NAME -> {
            if (in.readBoolean()) display = Optional.of(MinecraftInput.string(in, 262144));
          }
          case REMOVE_PLAYER -> { }
          default -> { }
        }
        entries.add(new SemanticPlayerInfo.Entry(uuid, name, props, gameMode, latency, display, Optional.empty()));
      }
      return new SemanticPlayerInfo(PacketDirection.SERVER_TO_CLIENT, act, entries);
    }
  }

  private static byte[] encode393(ProtocolDefinition protocol, SemanticPlayerInfo info) throws IOException {
    int action = switch (info.action()) {
      case ADD_PLAYER -> 0;
      case UPDATE_GAME_MODE -> 1;
      case UPDATE_LATENCY -> 2;
      case UPDATE_DISPLAY_NAME -> 3;
      case REMOVE_PLAYER -> 4;
      case UPDATE_LISTED -> 1; // no listed flag on 393 — fold into gamemode update if present else skip fields
    };
    int id = info.action() == SemanticPlayerInfo.Action.REMOVE_PLAYER
        ? protocol.id(gg.tame.conduit.protocol.ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE)
        : protocol.id(gg.tame.conduit.protocol.ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      MinecraftOutput.varInt(out, action);
      MinecraftOutput.varInt(out, info.entries().size());
      for (SemanticPlayerInfo.Entry entry : info.entries()) {
        GameProfiles.writeUuid(out, entry.uuid());
        switch (info.action()) {
          case ADD_PLAYER -> {
            MinecraftOutput.string(out, entry.username().orElse(""));
            GameProfiles.writeProperties(out, entry.properties());
            MinecraftOutput.varInt(out, entry.gameMode().orElse(0));
            MinecraftOutput.varInt(out, entry.latency().orElse(0));
            out.writeBoolean(entry.displayNameJson().isPresent());
            if (entry.displayNameJson().isPresent()) MinecraftOutput.string(out, entry.displayNameJson().get());
          }
          case UPDATE_GAME_MODE, UPDATE_LISTED -> MinecraftOutput.varInt(out, entry.gameMode().orElse(0));
          case UPDATE_LATENCY -> MinecraftOutput.varInt(out, entry.latency().orElse(0));
          case UPDATE_DISPLAY_NAME -> {
            out.writeBoolean(entry.displayNameJson().isPresent());
            if (entry.displayNameJson().isPresent()) MinecraftOutput.string(out, entry.displayNameJson().get());
          }
          case REMOVE_PLAYER -> { }
        }
      }
    }
    return bytes.toByteArray();
  }

  private static SemanticPlayerInfo decode765(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
      int packetId = MinecraftInput.varInt(in);
      if (protocol.is(gg.tame.conduit.protocol.ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, packetId, PacketKind.PLAY_PLAYER_INFO_REMOVE)) {
        int count = MinecraftInput.varInt(in);
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("remove count");
        List<SemanticPlayerInfo.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
          entries.add(new SemanticPlayerInfo.Entry(GameProfiles.readUuid(in), Optional.empty(), List.of(),
              Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return new SemanticPlayerInfo(PacketDirection.SERVER_TO_CLIENT, SemanticPlayerInfo.Action.REMOVE_PLAYER, entries);
      }
      int actions = in.readUnsignedByte();
      int count = MinecraftInput.varInt(in);
      if (count < 0 || count > MAX_ENTRIES) throw new IOException("update count");
      boolean add = (actions & 0x01) != 0;
      boolean chat = (actions & 0x02) != 0;
      boolean gamemode = (actions & 0x04) != 0;
      boolean listed = (actions & 0x08) != 0;
      boolean latency = (actions & 0x10) != 0;
      boolean display = (actions & 0x20) != 0;
      SemanticPlayerInfo.Action primary = add ? SemanticPlayerInfo.Action.ADD_PLAYER
          : gamemode ? SemanticPlayerInfo.Action.UPDATE_GAME_MODE
          : latency ? SemanticPlayerInfo.Action.UPDATE_LATENCY
          : display ? SemanticPlayerInfo.Action.UPDATE_DISPLAY_NAME
          : listed ? SemanticPlayerInfo.Action.UPDATE_LISTED
          : SemanticPlayerInfo.Action.UPDATE_LATENCY;
      List<SemanticPlayerInfo.Entry> entries = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        UUID uuid = GameProfiles.readUuid(in);
        Optional<String> name = Optional.empty();
        List<ProfileProperty> props = List.of();
        Optional<Integer> gm = Optional.empty();
        Optional<Integer> ping = Optional.empty();
        Optional<String> disp = Optional.empty();
        Optional<Boolean> list = Optional.empty();
        if (add) {
          name = Optional.of(MinecraftInput.string(in, 16));
          props = GameProfiles.readProperties(in);
        }
        if (chat) {
          if (in.readBoolean()) {
            GameProfiles.readUuid(in);
            in.readLong();
            MinecraftInput.bytes(in, 8192);
            MinecraftInput.bytes(in, 8192);
          }
        }
        if (gamemode) gm = Optional.of(MinecraftInput.varInt(in));
        if (listed) list = Optional.of(in.readBoolean());
        if (latency) ping = Optional.of(MinecraftInput.varInt(in));
        if (display) {
          if (in.readBoolean()) {
            // skip NBT display name
            gg.tame.conduit.protocol.NetworkNbt.skip(in);
            disp = Optional.of("{\"text\":\"\"}");
          }
        }
        entries.add(new SemanticPlayerInfo.Entry(uuid, name, props, gm, ping, disp, list));
      }
      return new SemanticPlayerInfo(PacketDirection.SERVER_TO_CLIENT, primary, entries);
    }
  }

  private static byte[] encode765(ProtocolDefinition protocol, SemanticPlayerInfo info) throws IOException {
    if (info.action() == SemanticPlayerInfo.Action.REMOVE_PLAYER) {
      int id = protocol.id(gg.tame.conduit.protocol.ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(out, id);
        MinecraftOutput.varInt(out, info.entries().size());
        for (SemanticPlayerInfo.Entry entry : info.entries()) GameProfiles.writeUuid(out, entry.uuid());
      }
      return bytes.toByteArray();
    }
    int id = protocol.id(gg.tame.conduit.protocol.ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE);
    int actions = switch (info.action()) {
      case ADD_PLAYER -> 0x01 | 0x04 | 0x08 | 0x10;
      case UPDATE_GAME_MODE -> 0x04;
      case UPDATE_LATENCY -> 0x10;
      case UPDATE_DISPLAY_NAME -> 0x20;
      case UPDATE_LISTED -> 0x08;
      case REMOVE_PLAYER -> 0;
    };
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(out, id);
      out.writeByte(actions);
      MinecraftOutput.varInt(out, info.entries().size());
      for (SemanticPlayerInfo.Entry entry : info.entries()) {
        GameProfiles.writeUuid(out, entry.uuid());
        if ((actions & 0x01) != 0) {
          MinecraftOutput.string(out, entry.username().orElse(""));
          GameProfiles.writeProperties(out, entry.properties());
        }
        if ((actions & 0x04) != 0) MinecraftOutput.varInt(out, entry.gameMode().orElse(0));
        if ((actions & 0x08) != 0) out.writeBoolean(entry.listed().orElse(true));
        if ((actions & 0x10) != 0) MinecraftOutput.varInt(out, entry.latency().orElse(0));
        if ((actions & 0x20) != 0) {
          out.writeBoolean(false);
        }
      }
    }
    return bytes.toByteArray();
  }
}

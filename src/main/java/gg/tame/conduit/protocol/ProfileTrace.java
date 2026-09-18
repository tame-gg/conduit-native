// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.UUID;

/**
 * Decodes the identity-carrying clientbound packets for debugging a backend switch.
 *
 * <p>Presence only. Property values, signatures, chat keys, secrets and component text are never
 * printed; a textures blob is reported as PRESENT or ABSENT and nothing more.
 *
 * <p>Enable with {@code -Dconduit.trace.profile=true}.
 */
public final class ProfileTrace {
  private static final boolean ENABLED = Boolean.getBoolean("conduit.trace.profile");
  private ProfileTrace() {}
  public static boolean enabled() { return ENABLED; }
  /** Called for every packet Conduit writes to the client, at the single write choke point. */
  public static void clientbound(String where, ProtocolDefinition protocol, ConnectionState state, byte[] packet, PlayerProfile self) {
    if (!ENABLED) return;
    try {
      int id = PlayPackets.packetId(packet);
      if (state == ConnectionState.LOGIN
          && protocol.is(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.LOGIN_SUCCESS)) {
        loginSuccess(where, protocol, state, id, packet, self);
        return;
      }
      if (state != ConnectionState.PLAY) return;
      logSequence(id, packet);
      if (protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_INFO_UPDATE)) {
        playerInfoUpdate(where, state, id, packet, self);
      } else if (protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_REMOVE)
          && protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_INFO_REMOVE)) {
        playerInfoRemove(where, state, id, packet, self);
      } else if (protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_LOGIN)) {
        System.out.println(line(where, state, id, "PLAY_LOGIN") + " len=" + packet.length);
      }
    } catch (Exception exception) {
      System.out.println("TRACE " + where + " decode failed: " + exception);
    }
  }
  private static void loginSuccess(String where, ProtocolDefinition protocol, ConnectionState state, int id, byte[] packet, PlayerProfile self) throws Exception {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      UUID uuid;
      String name;
      List<ProfileProperty> properties;
      if (!protocol.capabilities().loginSuccessBinaryUuid()) {
        uuid = UUID.fromString(MinecraftInput.string(input, 36));
        name = MinecraftInput.string(input, 16);
        properties = List.of();
      } else {
        uuid = GameProfiles.readUuid(input);
        name = MinecraftInput.string(input, 16);
        properties = protocol.capabilities().loginSuccessProperties() ? GameProfiles.readProperties(input) : List.of();
      }
      int trailing = input.available();
      System.out.println(line(where, state, id, "LOGIN_SUCCESS") + " " + who(uuid, self) + " name=" + name + " "
          + properties(properties) + " trailingBytes=" + trailing);
      hex(packet);
    }
  }
  private static void playerInfoRemove(String where, ConnectionState state, int id, byte[] packet, PlayerProfile self) throws Exception {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      int count = MinecraftInput.varInt(input);
      StringBuilder targets = new StringBuilder();
      for (int index = 0; index < count && index < 32; index++) targets.append(' ').append(who(GameProfiles.readUuid(input), self));
      System.out.println(line(where, state, id, "PLAYER_INFO_REMOVE") + " count=" + count + targets);
    }
  }
  /**
   * Raw bytes of the first few player-info packets. The decoder and {@code selfAdd} share their
   * assumptions about the 26.2 layout, so a decoded trace cannot disprove those assumptions; the
   * bytes can. Bounded, and the textures blob is public profile data rather than a secret.
   */
  private static int hexBudget = 5;
  private static synchronized void hex(byte[] packet) {
    if (hexBudget <= 0) return;
    hexBudget--;
    StringBuilder out = new StringBuilder("    raw[" + packet.length + "] ");
    for (int index = 0; index < Math.min(packet.length, 96); index++) out.append(String.format("%02x", packet[index]));
    if (packet.length > 96) out.append("...(truncated)");
    System.out.println(out);
  }
  private static void playerInfoUpdate(String where, ConnectionState state, int id, byte[] packet, PlayerProfile self) throws Exception {
    hex(packet);
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      int actions = input.readUnsignedByte();
      int count = MinecraftInput.varInt(input);
      StringBuilder out = new StringBuilder(line(where, state, id, "PLAYER_INFO_UPDATE"));
      out.append(" actions=").append(actionNames(actions)).append(" count=").append(count);
      for (int index = 0; index < count; index++) {
        UUID uuid = GameProfiles.readUuid(input);
        out.append("\n    entry[").append(index).append("] ").append(who(uuid, self));
        if ((actions & PlayerInfoUpdate.ADD_PLAYER) != 0) {
          out.append(" name=").append(MinecraftInput.string(input, 16));
          out.append(' ').append(properties(GameProfiles.readProperties(input)));
        }
        if ((actions & PlayerInfoUpdate.INITIALIZE_CHAT) != 0) {
          boolean present = input.readBoolean();
          out.append(" chat=").append(present ? "PRESENT" : "ABSENT");
          if (present) { GameProfiles.readUuid(input); input.readLong(); MinecraftInput.bytes(input, 8192); MinecraftInput.bytes(input, 8192); }
        }
        if ((actions & PlayerInfoUpdate.UPDATE_GAME_MODE) != 0) out.append(" gameMode=").append(MinecraftInput.varInt(input));
        if ((actions & PlayerInfoUpdate.UPDATE_LISTED) != 0) out.append(" listed=").append(input.readBoolean());
        if ((actions & PlayerInfoUpdate.UPDATE_LATENCY) != 0) out.append(" latency=").append(MinecraftInput.varInt(input));
        if ((actions & PlayerInfoUpdate.UPDATE_DISPLAY_NAME) != 0) {
          boolean present = input.readBoolean();
          out.append(" displayName=").append(present ? "PRESENT" : "ABSENT");
          if (present) NetworkNbt.skip(input);
        }
        if ((actions & PlayerInfoUpdate.UPDATE_LIST_PRIORITY) != 0) out.append(" priority=").append(MinecraftInput.varInt(input));
        if ((actions & PlayerInfoUpdate.UPDATE_HAT) != 0) out.append(" hat=").append(input.readBoolean());
      }
      if (input.available() != 0) out.append("\n    TRAILING BYTES=").append(input.available());
      System.out.println(out);
    }
  }
  private static String properties(List<ProfileProperty> properties) {
    ProfileProperty textures = properties.stream().filter(property -> property.name().equals("textures")).findFirst().orElse(null);
    return "properties=" + properties.size()
        + " textures=" + (textures == null ? "ABSENT" : "PRESENT len=" + textures.value().length() + " " + fingerprint(textures.value()))
        + " signature=" + (textures == null || textures.signature().isEmpty() ? "ABSENT"
            : "PRESENT len=" + textures.signature().get().length() + " " + fingerprint(textures.signature().get()));
  }
  /**
   * Short digest so a value can be compared across packets without printing it. The textures blob
   * is public profile data, but there is no reason to put it in a log to tell two copies apart.
   */
  private static String fingerprint(String value) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "sha=" + String.format("%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3]);
    } catch (Exception exception) {
      return "sha=?";
    }
  }
  private static String who(UUID uuid, PlayerProfile self) {
    return self != null && uuid.equals(self.uniqueId()) ? "SELF" : "other(" + Integer.toHexString(uuid.hashCode()) + ")";
  }
  private static String actionNames(int actions) {
    StringBuilder names = new StringBuilder(String.format("0x%02X[", actions));
    if ((actions & PlayerInfoUpdate.ADD_PLAYER) != 0) names.append("ADD ");
    if ((actions & PlayerInfoUpdate.INITIALIZE_CHAT) != 0) names.append("CHAT ");
    if ((actions & PlayerInfoUpdate.UPDATE_GAME_MODE) != 0) names.append("GAMEMODE ");
    if ((actions & PlayerInfoUpdate.UPDATE_LISTED) != 0) names.append("LISTED ");
    if ((actions & PlayerInfoUpdate.UPDATE_LATENCY) != 0) names.append("LATENCY ");
    if ((actions & PlayerInfoUpdate.UPDATE_DISPLAY_NAME) != 0) names.append("DISPLAYNAME ");
    if ((actions & PlayerInfoUpdate.UPDATE_LIST_PRIORITY) != 0) names.append("PRIORITY ");
    if ((actions & PlayerInfoUpdate.UPDATE_HAT) != 0) names.append("HAT ");
    return names.append(']').toString();
  }
  /**
   * Writes the raw Declare Commands packet to disk when the brigadier merge fails, so the node
   * stream can be decoded offline. A command tree contains only command names and argument
   * metadata, no player data. Written once per run.
   */
  /**
   * Bounded log of every clientbound Play packet id after a switch begins, so a packet that makes
   * the client rebuild or discard its player list (a second Join Game, a Respawn) is visible even
   * though Conduit does not decode it. Ids only, never bodies.
   */
  private static int sequenceBudget;
  public static synchronized void beginSequence(String reason, int packets) {
    if (!ENABLED) return;
    sequenceBudget = packets;
    System.out.println("TRACE ---- packet sequence start: " + reason + " (next " + packets + " Play packets) ----");
  }
  private static synchronized void logSequence(int id, byte[] packet) {
    if (sequenceBudget <= 0) return;
    sequenceBudget--;
    System.out.printf("TRACE   seq id=0x%02X len=%d%n", id, packet.length);
    if (sequenceBudget == 0) System.out.println("TRACE ---- packet sequence end ----");
  }
  private static boolean commandTreeDumped;
  public static synchronized void dumpCommandTree(ProtocolDefinition protocol, byte[] packet, Exception cause) {
    if (!ENABLED || commandTreeDumped) return;
    commandTreeDumped = true;
    try {
      java.nio.file.Path path = java.nio.file.Path.of("command-tree-" + protocol.version().number() + ".bin");
      java.nio.file.Files.write(path, packet);
      System.out.println("TRACE wrote " + packet.length + " byte command tree to " + path.toAbsolutePath() + " (" + cause + ")");
    } catch (Exception exception) {
      System.out.println("TRACE could not dump command tree: " + exception);
    }
  }
  private static String line(String where, ConnectionState state, int id, String name) {
    return String.format("TRACE %-18s S2C %-13s id=0x%02X %s", where, state, id, name);
  }
}

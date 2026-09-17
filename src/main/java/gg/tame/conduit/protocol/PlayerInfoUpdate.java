package gg.tame.conduit.protocol;

import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Injects authenticated textures into ADD_PLAYER entries that the backend left empty. */
public final class PlayerInfoUpdate {
  public static final int ADD_PLAYER = 0x01;
  public static final int INITIALIZE_CHAT = 0x02;
  public static final int UPDATE_GAME_MODE = 0x04;
  public static final int UPDATE_LISTED = 0x08;
  public static final int UPDATE_LATENCY = 0x10;
  public static final int UPDATE_DISPLAY_NAME = 0x20;
  public static final int UPDATE_LIST_PRIORITY = 0x40;
  public static final int UPDATE_HAT = 0x80;
  /** Sanity bound on the entry count so a misparsed length cannot drive a huge decode loop. */
  private static final int MAX_ENTRIES = 4096;
  private PlayerInfoUpdate() {}
  /**
   * 26.2 reconfiguration clears TAB. Login Success is hidden on switch, so the client only
   * learns textures again from ADD_PLAYER. Synthesize that entry from the session profile
   * after Join Game rather than reconstructing from UUID+name with empty properties.
   */
  public static byte[] selfAdd(ProtocolDefinition protocol, PlayerProfile profile) {
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE)) {
      throw new IllegalArgumentException("protocol has no player-info update packet");
    }
    int id = protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE);
    int actions = ADD_PLAYER | UPDATE_GAME_MODE | UPDATE_LISTED | UPDATE_LATENCY;
    if (ProtocolEras.playerInfoHat(protocol.version().number())) actions |= UPDATE_HAT;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      output.writeByte(actions);
      MinecraftOutput.varInt(output, 1);
      GameProfiles.writeUuid(output, profile.uniqueId());
      MinecraftOutput.string(output, profile.username());
      GameProfiles.writeProperties(output, profile.properties());
      MinecraftOutput.varInt(output, 0);
      output.writeBoolean(true);
      MinecraftOutput.varInt(output, -1);
      if ((actions & UPDATE_HAT) != 0) output.writeBoolean(true);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot encode player-info ADD_PLAYER", exception);
    }
    return bytes.toByteArray();
  }
  /**
   * Replaces the local player's profile properties inside a backend player-info update.
   *
   * <p>The packet is re-serialized from what was decoded, so a decode that does not line up with
   * the wire layout must never reach the client. Any decode failure, or a leftover byte after the
   * declared entry count, returns the backend packet untouched instead of emitting a half-parsed
   * rewrite with the remaining bytes appended. The packet is likewise returned untouched when
   * nothing was substituted, so entries for other players stay byte-for-byte as the backend sent
   * them.
   */
  public static byte[] ensureOwnTextures(ProtocolDefinition protocol, byte[] packet, PlayerProfile profile) {
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE)) return packet;
    if (!profile.hasTextures()) return packet;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_INFO_UPDATE)) return packet;
      int actions = input.readUnsignedByte();
      int count = MinecraftInput.varInt(input);
      if ((actions & ADD_PLAYER) == 0) return packet;
      if (count < 0 || count > MAX_ENTRIES) throw new IOException("invalid player-info entry count " + count);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      boolean substituted = false;
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(output, id);
        output.writeByte(actions);
        MinecraftOutput.varInt(output, count);
        for (int index = 0; index < count; index++) substituted |= copyPlayer(input, output, actions, profile);
      }
      if (input.available() != 0) throw new IOException(input.available() + " trailing bytes after " + count + " entries");
      return substituted ? bytes.toByteArray() : packet;
    } catch (IOException | RuntimeException exception) {
      System.err.println("player-info rewrite skipped, backend packet forwarded unchanged: " + exception.getMessage());
      return packet;
    }
  }
  /** Returns true when this entry's properties were replaced by the authenticated ones. */
  private static boolean copyPlayer(DataInputStream input, DataOutputStream output, int actions, PlayerProfile profile) throws IOException {
    boolean substituted = false;
    UUID uuid = GameProfiles.readUuid(input);
    GameProfiles.writeUuid(output, uuid);
    if ((actions & ADD_PLAYER) != 0) {
      String name = MinecraftInput.string(input, 16);
      List<ProfileProperty> properties = GameProfiles.readProperties(input);
      if (uuid.equals(profile.uniqueId()) && !properties.equals(profile.properties())) {
        properties = profile.properties();
        substituted = true;
      }
      MinecraftOutput.string(output, name);
      GameProfiles.writeProperties(output, properties);
    }
    if ((actions & INITIALIZE_CHAT) != 0) copyOptionalChat(input, output);
    if ((actions & UPDATE_GAME_MODE) != 0) MinecraftOutput.varInt(output, copyVarInt(input));
    if ((actions & UPDATE_LISTED) != 0) output.writeBoolean(input.readBoolean());
    if ((actions & UPDATE_LATENCY) != 0) MinecraftOutput.varInt(output, copyVarInt(input));
    if ((actions & UPDATE_DISPLAY_NAME) != 0) copyOptionalComponent(input, output);
    if ((actions & UPDATE_LIST_PRIORITY) != 0) MinecraftOutput.varInt(output, copyVarInt(input));
    if ((actions & UPDATE_HAT) != 0) output.writeBoolean(input.readBoolean());
    return substituted;
  }
  private static int copyVarInt(DataInputStream input) throws IOException { return MinecraftInput.varInt(input); }
  private static void copyOptionalChat(DataInputStream input, DataOutputStream output) throws IOException {
    boolean present = input.readBoolean();
    output.writeBoolean(present);
    if (!present) return;
    GameProfiles.writeUuid(output, GameProfiles.readUuid(input));
    output.writeLong(input.readLong());
    writePrefixedBytes(output, MinecraftInput.bytes(input, 8192));
    writePrefixedBytes(output, MinecraftInput.bytes(input, 8192));
  }
  private static void writePrefixedBytes(DataOutputStream output, byte[] data) throws IOException {
    MinecraftOutput.varInt(output, data.length);
    output.write(data);
  }
  private static void copyOptionalComponent(DataInputStream input, DataOutputStream output) throws IOException {
    boolean present = input.readBoolean();
    output.writeBoolean(present);
    if (present) NetworkNbt.copy(input, output);
  }
}

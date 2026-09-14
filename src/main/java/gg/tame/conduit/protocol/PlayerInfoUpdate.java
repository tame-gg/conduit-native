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
  private PlayerInfoUpdate() {}
  public static byte[] ensureOwnTextures(ProtocolDefinition protocol, byte[] packet, PlayerProfile profile) {
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_INFO_UPDATE)) return packet;
    if (!profile.hasTextures()) return packet;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      int id = MinecraftInput.varInt(input);
      if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_INFO_UPDATE)) return packet;
      int actions = input.readUnsignedByte();
      int count = MinecraftInput.varInt(input);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        MinecraftOutput.varInt(output, id);
        output.writeByte(actions);
        MinecraftOutput.varInt(output, count);
        for (int index = 0; index < count; index++) copyPlayer(input, output, actions, profile);
        if (input.available() != 0) return packet;
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      return packet;
    }
  }
  private static void copyPlayer(DataInputStream input, DataOutputStream output, int actions, PlayerProfile profile) throws IOException {
    UUID uuid = GameProfiles.readUuid(input);
    GameProfiles.writeUuid(output, uuid);
    if ((actions & ADD_PLAYER) != 0) {
      String name = MinecraftInput.string(input, 16);
      List<ProfileProperty> properties = GameProfiles.readProperties(input);
      if (uuid.equals(profile.uniqueId()) && !GameProfiles.hasTextures(properties)) properties = profile.properties();
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
  }
  private static int copyVarInt(DataInputStream input) throws IOException { return MinecraftInput.varInt(input); }
  private static void copyOptionalChat(DataInputStream input, DataOutputStream output) throws IOException {
    boolean present = input.readBoolean();
    output.writeBoolean(present);
    if (!present) return;
    GameProfiles.writeUuid(output, GameProfiles.readUuid(input));
    output.writeLong(input.readLong());
    writePrefixedBytes(output, MinecraftInput.bytes(input, 512));
    writePrefixedBytes(output, MinecraftInput.bytes(input, 4096));
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

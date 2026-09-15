package gg.tame.conduit.protocol.codec;

import gg.tame.conduit.login.LoginStart;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.GameProfiles;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.NetworkNbt;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolCapabilities;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.semantic.DisconnectPacket;
import gg.tame.conduit.protocol.semantic.JoinGamePacket;
import gg.tame.conduit.protocol.semantic.LoginStartPacket;
import gg.tame.conduit.protocol.semantic.LoginSuccessPacket;
import gg.tame.conduit.protocol.semantic.MovementPacket;
import gg.tame.conduit.protocol.semantic.PlayerPositionPacket;
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
 * Field codecs for packets that differ between 393 and modern protocols.
 * Public protocol layouts from PrismarineJS minecraft-data (1.13 / 1.20.4).
 */
public final class JoinGameCodec {
  private JoinGameCodec() {}

  public static JoinGamePacket decode(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      if (protocol.version().number() <= 404) return decode113(input);
      return decodeModern(input);
    }
  }

  private static JoinGamePacket decode113(DataInputStream input) throws IOException {
    int entityId = input.readInt();
    int gameMode = input.readUnsignedByte();
    int dimension = input.readInt();
    byte difficulty = input.readByte();
    int maxPlayers = input.readUnsignedByte();
    String levelType = MinecraftInput.string(input, 16);
    boolean reduced = input.readBoolean();
    String world = JoinGamePacket.dimensionNameFromId(dimension);
    return new JoinGamePacket(PacketDirection.SERVER_TO_CLIENT, entityId, false, gameMode, -1, dimension,
        world, world, List.of(world), 0L, maxPlayers, 10, 10, reduced, true, false,
        "flat".equalsIgnoreCase(levelType), difficulty, levelType, false, 0);
  }

  private static JoinGamePacket decodeModern(DataInputStream input) throws IOException {
    int entityId = input.readInt();
    boolean hardcore = input.readBoolean();
    int worldCount = MinecraftInput.varInt(input);
    List<String> worlds = new ArrayList<>(worldCount);
    for (int i = 0; i < worldCount; i++) worlds.add(MinecraftInput.string(input, 32767));
    int maxPlayers = MinecraftInput.varInt(input);
    int viewDistance = MinecraftInput.varInt(input);
    int simulationDistance = MinecraftInput.varInt(input);
    boolean reduced = input.readBoolean();
    boolean respawn = input.readBoolean();
    boolean limited = input.readBoolean();
    String worldType = MinecraftInput.string(input, 32767);
    String worldName = MinecraftInput.string(input, 32767);
    long seed = input.readLong();
    int gameMode = input.readUnsignedByte();
    int previous = input.readByte();
    boolean debug = input.readBoolean();
    boolean flat = input.readBoolean();
    if (input.readBoolean()) {
      MinecraftInput.string(input, 32767);
      input.readLong(); // position packed — skip without full decode
    }
    int portal = 0;
    if (input.available() > 0) portal = MinecraftInput.varInt(input);
    return new JoinGamePacket(PacketDirection.SERVER_TO_CLIENT, entityId, hardcore, gameMode, previous,
        JoinGamePacket.dimensionIdFromName(worldName), worldType, worldName, worlds, seed, maxPlayers,
        viewDistance, simulationDistance, reduced, respawn, debug, flat, (byte) 0, flat ? "flat" : "default",
        limited, portal);
  }

  public static byte[] encode(ProtocolDefinition protocol, JoinGamePacket join) throws IOException {
    int id = protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_LOGIN);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      if (protocol.version().number() <= 404) encode113(output, join);
      else encodeModern(output, join);
    }
    return bytes.toByteArray();
  }

  private static void encode113(DataOutputStream output, JoinGamePacket join) throws IOException {
    output.writeInt(join.entityId());
    output.writeByte(join.gameMode() & 0xff);
    output.writeInt(join.dimensionId());
    output.writeByte(join.difficulty());
    output.writeByte(Math.min(255, Math.max(0, join.maxPlayers())));
    MinecraftOutput.string(output, join.levelType());
    output.writeBoolean(join.reducedDebugInfo());
  }

  private static void encodeModern(DataOutputStream output, JoinGamePacket join) throws IOException {
    output.writeInt(join.entityId());
    output.writeBoolean(join.hardcore());
    MinecraftOutput.varInt(output, join.worldNames().size());
    for (String name : join.worldNames()) MinecraftOutput.string(output, name);
    MinecraftOutput.varInt(output, join.maxPlayers());
    MinecraftOutput.varInt(output, join.viewDistance());
    MinecraftOutput.varInt(output, join.simulationDistance());
    output.writeBoolean(join.reducedDebugInfo());
    output.writeBoolean(join.enableRespawnScreen());
    output.writeBoolean(join.doLimitedCrafting());
    MinecraftOutput.string(output, join.dimensionType());
    MinecraftOutput.string(output, join.worldName());
    output.writeLong(join.hashedSeed());
    output.writeByte(join.gameMode() & 0xff);
    output.writeByte(join.previousGameMode());
    output.writeBoolean(join.isDebug());
    output.writeBoolean(join.isFlat());
    output.writeBoolean(false); // death location absent
    MinecraftOutput.varInt(output, join.portalCooldown());
  }

  public static LoginStartPacket decodeLoginStart(ProtocolDefinition protocol, byte[] body) throws IOException {
    LoginStart decoded = LoginStart.decode(body, protocol);
    Optional<UUID> uuid = protocol.capabilities().loginStartUuid()
        ? Optional.of(decoded.clientUuid()) : Optional.empty();
    return new LoginStartPacket(PacketDirection.CLIENT_TO_SERVER, decoded.username(), uuid);
  }

  public static byte[] encodeLoginStart(ProtocolDefinition protocol, LoginStartPacket packet) throws IOException {
    UUID uuid = packet.clientUuid().orElseGet(() -> LoginStart.offlineUuid(packet.username()));
    PlayerProfile profile = new PlayerProfile(uuid, packet.username(), List.of(), false);
    return LoginStart.encode(profile, protocol);
  }

  public static LoginSuccessPacket decodeLoginSuccess(ProtocolDefinition protocol, byte[] packet) throws IOException {
    ProtocolCapabilities caps = protocol.capabilities();
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      UUID uuid;
      String name;
      List<ProfileProperty> properties;
      if (!caps.loginSuccessBinaryUuid()) {
        uuid = UUID.fromString(MinecraftInput.string(input, 36));
        name = MinecraftInput.string(input, 16);
        properties = List.of();
      } else {
        uuid = GameProfiles.readUuid(input);
        name = MinecraftInput.string(input, 16);
        properties = caps.loginSuccessProperties() ? GameProfiles.readProperties(input) : List.of();
      }
      return new LoginSuccessPacket(PacketDirection.SERVER_TO_CLIENT, uuid, name, properties);
    }
  }

  public static byte[] encodeLoginSuccess(ProtocolDefinition protocol, LoginSuccessPacket packet) throws IOException {
    PlayerProfile profile = new PlayerProfile(packet.uniqueId(), packet.username(), packet.properties(), !packet.properties().isEmpty());
    return gg.tame.conduit.protocol.LoginSuccess.encode(protocol, profile);
  }

  public static DisconnectPacket decodeDisconnect(ProtocolDefinition protocol, ConnectionState state,
                                                  PacketKind kind, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      String reason;
      if (protocol.hasConfiguration() && state != ConnectionState.LOGIN) {
        // Prefer plain extraction; skip NBT body for safety when present.
        reason = "Disconnected";
        if (input.available() > 0) {
          try {
            // Attempt JSON string first (login / older).
            input.mark(8);
            // DataInputStream lacks mark for all cases — read remaining as best-effort text.
            byte[] rest = input.readAllBytes();
            reason = extractPlain(rest);
          } catch (Exception ignored) {
            reason = "Disconnected";
          }
        }
      } else {
        reason = MinecraftInput.string(input, 262144);
        reason = stripJsonText(reason);
      }
      return new DisconnectPacket(state, PacketDirection.SERVER_TO_CLIENT, kind, reason);
    }
  }

  public static byte[] encodeDisconnect(ProtocolDefinition protocol, DisconnectPacket packet) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(packet.state(), packet.direction(), packet.kind()));
      if (protocol.hasConfiguration() && packet.state() != ConnectionState.LOGIN) {
        NetworkNbt.stringComponent(output, packet.reason());
      } else {
        String json = "{\"text\":\"" + packet.reason().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        MinecraftOutput.string(output, json);
      }
    }
    return bytes.toByteArray();
  }

  public static PlayerPositionPacket decodePlayerPosition(byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      double x = input.readDouble();
      double y = input.readDouble();
      double z = input.readDouble();
      float yaw = input.readFloat();
      float pitch = input.readFloat();
      byte flags = input.readByte();
      int teleportId = MinecraftInput.varInt(input);
      return new PlayerPositionPacket(PacketDirection.SERVER_TO_CLIENT, x, y, z, yaw, pitch, flags, teleportId);
    }
  }

  public static byte[] encodePlayerPosition(ProtocolDefinition protocol, PlayerPositionPacket pos) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_PLAYER_POSITION));
      output.writeDouble(pos.x());
      output.writeDouble(pos.y());
      output.writeDouble(pos.z());
      output.writeFloat(pos.yaw());
      output.writeFloat(pos.pitch());
      output.writeByte(pos.flags());
      MinecraftOutput.varInt(output, pos.teleportId());
    }
    return bytes.toByteArray();
  }

  public static MovementPacket decodeMovement(PacketKind kind, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);
      return switch (kind) {
        case PLAY_FLYING -> MovementPacket.flying(input.readBoolean());
        case PLAY_POSITION -> {
          double x = input.readDouble(), y = input.readDouble(), z = input.readDouble();
          yield MovementPacket.position(x, y, z, input.readBoolean());
        }
        case PLAY_LOOK -> {
          float yaw = input.readFloat(), pitch = input.readFloat();
          yield MovementPacket.look(yaw, pitch, input.readBoolean());
        }
        case PLAY_POSITION_LOOK -> {
          double x = input.readDouble(), y = input.readDouble(), z = input.readDouble();
          float yaw = input.readFloat(), pitch = input.readFloat();
          yield MovementPacket.positionLook(x, y, z, yaw, pitch, input.readBoolean());
        }
        default -> throw new IOException("not a movement packet: " + kind);
      };
    }
  }

  public static byte[] encodeMovement(ProtocolDefinition protocol, MovementPacket move) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, protocol.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, move.kind()));
      switch (move.kind()) {
        case PLAY_FLYING -> output.writeBoolean(move.onGround());
        case PLAY_POSITION -> {
          output.writeDouble(move.x()); output.writeDouble(move.y()); output.writeDouble(move.z());
          output.writeBoolean(move.onGround());
        }
        case PLAY_LOOK -> {
          output.writeFloat(move.yaw()); output.writeFloat(move.pitch());
          output.writeBoolean(move.onGround());
        }
        case PLAY_POSITION_LOOK -> {
          output.writeDouble(move.x()); output.writeDouble(move.y()); output.writeDouble(move.z());
          output.writeFloat(move.yaw()); output.writeFloat(move.pitch());
          output.writeBoolean(move.onGround());
        }
        default -> throw new IOException("cannot encode " + move.kind());
      }
    }
    return bytes.toByteArray();
  }

  private static String extractPlain(byte[] rest) {
    try {
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(rest))) {
        return stripJsonText(MinecraftInput.string(input, 262144));
      }
    } catch (Exception ignored) {
      return "Disconnected";
    }
  }

  private static String stripJsonText(String json) {
    if (json == null) return "";
    int idx = json.indexOf("\"text\"");
    if (idx < 0) return json.length() > 200 ? json.substring(0, 200) : json;
    int start = json.indexOf('"', idx + 6);
    if (start < 0) return json;
    start++;
    int end = json.indexOf('"', start);
    if (end < 0) return json.substring(start);
    return json.substring(start, end);
  }
}

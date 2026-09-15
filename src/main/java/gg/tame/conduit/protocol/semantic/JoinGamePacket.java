package gg.tame.conduit.protocol.semantic;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import java.util.List;
import java.util.Optional;

/**
 * Join Game / Login (play) common fields across 1.13 and modern protocols.
 * Version-specific wire encode/decode lives in JoinGameCodec.
 */
public record JoinGamePacket(
    PacketDirection direction,
    int entityId,
    boolean hardcore,
    int gameMode,
    int previousGameMode,
    int dimensionId,
    String dimensionType,
    String worldName,
    List<String> worldNames,
    long hashedSeed,
    int maxPlayers,
    int viewDistance,
    int simulationDistance,
    boolean reducedDebugInfo,
    boolean enableRespawnScreen,
    boolean isDebug,
    boolean isFlat,
    byte difficulty,
    String levelType,
    boolean doLimitedCrafting,
    int portalCooldown
) implements SemanticPacket {
  public JoinGamePacket {
    if (dimensionType == null || dimensionType.isBlank()) dimensionType = "minecraft:overworld";
    if (worldName == null || worldName.isBlank()) worldName = "minecraft:overworld";
    if (worldNames == null || worldNames.isEmpty()) worldNames = List.of(worldName);
    else worldNames = List.copyOf(worldNames);
    if (levelType == null || levelType.isBlank()) levelType = "default";
  }

  @Override public PacketKind kind() { return PacketKind.PLAY_LOGIN; }
  @Override public ConnectionState state() { return ConnectionState.PLAY; }

  public static int dimensionIdFromName(String name) {
    if (name == null) return 0;
    String n = name.toLowerCase();
    if (n.contains("nether")) return -1;
    if (n.contains("end")) return 1;
    return 0;
  }

  public static String dimensionNameFromId(int id) {
    return switch (id) {
      case -1 -> "minecraft:the_nether";
      case 1 -> "minecraft:the_end";
      default -> "minecraft:overworld";
    };
  }

  public Optional<String> primaryWorld() {
    return Optional.of(worldName);
  }
}

package gg.tame.conduit.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Makes a client with no Configuration phase reload its world after a server switch.
 *
 * <p>A modern client leaves Play for Configuration and comes back, and coming back is what rebuilds
 * the world. A pre-1.20.2 client has nowhere to go. It is handed a second Join Game while it is
 * still standing in the previous backend's world, and a second Join Game is not by itself a world
 * change: the client keeps the world it has, renders nothing new, and sits on "Downloading terrain"
 * with chunks and its own position arriving behind the screen. That is what a real 1.8 client does
 * after a switch, with every packet translated correctly and delivered.
 *
 * <p>What does move it is a dimension change, which these releases express as Respawn. So the
 * switch is followed by two of them: one naming a dimension the player is not in, which tears the
 * old world down, and one naming the dimension the new backend actually put them in, which builds
 * the right one. The pair is necessary because a Respawn into the dimension the client already
 * believes it is in is the case the client optimises away, and that is exactly the common case —
 * both backends' overworld.
 *
 * <p>Everything here is read out of the Join Game Conduit is about to forward, in the client's own
 * dialect, so nothing is assumed about the backend. A packet that does not parse produces no
 * respawns rather than a guess.
 */
public final class LegacyWorldReload {
  private LegacyWorldReload() {}

  /** Dimensions these releases define: -1 nether, 0 overworld, 1 end. */
  private static final int OVERWORLD = 0;
  private static final int NETHER = -1;
  private static final int END = 1;

  /**
   * The Respawn pair to send after {@code joinGame}, or empty when this client does not need one.
   *
   * @param protocol the client's protocol, whose dialect both the input and the output are in
   * @param joinGame the Join Game already translated for this client
   */
  public static List<byte[]> afterSwitch(ProtocolDefinition protocol, byte[] joinGame) {
    if (protocol.hasConfiguration()) return List.of();
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN)) {
      return List.of();
    }
    JoinGameWorld world;
    try {
      if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
          PlayPackets.peekId(joinGame), PacketKind.PLAY_LOGIN)) {
        return List.of();
      }
      world = readWorld(protocol, joinGame);
    } catch (IOException | RuntimeException unreadable) {
      ProtocolTrace.note("could not read the world out of Join Game for a legacy world reload: " + unreadable);
      return List.of();
    }
    if (world == null) return List.of();
    int away = world.dimension() == OVERWORLD ? NETHER : OVERWORLD;
    try {
      return List.of(
          respawn(protocol, away, world),
          respawn(protocol, world.dimension(), world));
    } catch (IOException failure) {
      ProtocolTrace.note("could not build a legacy world reload: " + failure);
      return List.of();
    }
  }

  private record JoinGameWorld(int dimension, int difficulty, int gamemode, String levelType) {}

  /**
   * Reads the fields Respawn needs out of Join Game.
   *
   * <p>The layout is stable across the releases this applies to except for one field: the dimension
   * is a signed byte until 1.9.1 widened it to an int. {@link ProtocolEras} carries that boundary
   * so the decision is not a version comparison written out here.
   */
  private static JoinGameWorld readWorld(ProtocolDefinition protocol, byte[] packet) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);              // packet id
      input.readInt();                           // entity id
      int gamemode = input.readUnsignedByte();
      int dimension = ProtocolEras.joinGameDimensionIsInt(protocol.version().number())
          ? input.readInt()
          : input.readByte();
      int difficulty = input.readUnsignedByte();
      input.readUnsignedByte();                  // max players
      String levelType = MinecraftInput.string(input, 64);
      return new JoinGameWorld(dimension, difficulty, gamemode, levelType);
    }
  }

  /** Respawn has the same four fields on every release that has no Configuration phase. */
  private static byte[] respawn(ProtocolDefinition protocol, int dimension, JoinGameWorld world) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(dimension);
    out.writeByte(world.difficulty());
    out.writeByte(world.gamemode());
    MinecraftOutput.string(out, world.levelType());
    return PlayPackets.withId(
        protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN),
        bytes.toByteArray());
  }
}

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
    int number = protocol.version().number();
    // Only the layouts below are read and written; 1.17 onward has not been shown to need this, so
    // it gets nothing rather than a guess.
    boolean dimensionNbt = ProtocolEras.worldReloadDimensionNbt(number);
    boolean dimensionKey = ProtocolEras.worldReloadDimensionKey(number);
    if (!ProtocolEras.joinGameHasDifficulty(number) && !ProtocolEras.joinGame114(number) && !dimensionNbt && !dimensionKey) {
      return List.of();
    }
    if (!protocol.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN)) {
      return List.of();
    }
    JoinGameWorld world;
    try {
      if (!protocol.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT,
          PlayPackets.peekId(joinGame), PacketKind.PLAY_LOGIN)) {
        return List.of();
      }
      if (dimensionNbt) return dimensionNbtPair(protocol, joinGame);
      if (dimensionKey) return dimensionKeyPair(protocol, joinGame);
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

  private record JoinGameWorld(int dimension, int difficulty, int gamemode, String levelType, long hashedSeed) {}

  /**
   * The pair for 1.16.2-1.16.5, where Respawn names the world by key and carries its dimension type
   * as NBT. A real 1.16.5 client switched from 1.20.4 to 1.21.8 sat on "Loading terrain" without it.
   *
   * <p>Join Game: entity, hardcore, gamemode, previous gamemode, world keys, dimension codec, dimension
   * type, world key, hashed seed, max players, view distance, reduced debug, respawn screen, debug,
   * flat. Respawn: dimension type, world key, hashed seed, gamemode, previous gamemode, debug, flat,
   * copy metadata. The client rebuilds its world when the key changes, so the first of the pair names
   * another vanilla world with the same dimension type and the second names the real one.
   */
  private static List<byte[]> dimensionNbtPair(ProtocolDefinition protocol, byte[] packet) throws IOException {
    byte[] dimensionType;
    String world;
    long seed;
    int gamemode;
    int previous;
    boolean debug;
    boolean flat;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);              // packet id
      input.readInt();                           // entity id
      input.readBoolean();                       // hardcore
      gamemode = input.readUnsignedByte();
      previous = input.readByte();
      int worlds = MinecraftInput.varInt(input);
      if (worlds < 0 || worlds > 1024) throw new IOException("world key count " + worlds);
      for (int i = 0; i < worlds; i++) MinecraftInput.string(input, 32767);
      NetworkNbt.skipNamed(input);               // dimension codec
      ByteArrayOutputStream type = new ByteArrayOutputStream();
      NetworkNbt.copyNamed(input, new DataOutputStream(type));
      dimensionType = type.toByteArray();
      world = MinecraftInput.string(input, 32767);
      seed = input.readLong();
      MinecraftInput.varInt(input);              // max players
      MinecraftInput.varInt(input);              // view distance
      input.readBoolean();                       // reduced debug info
      input.readBoolean();                       // respawn screen
      debug = input.readBoolean();
      flat = input.readBoolean();
    }
    String away = "minecraft:overworld".equals(world) ? "minecraft:the_nether" : "minecraft:overworld";
    return List.of(
        dimensionNbtRespawn(protocol, dimensionType, away, seed, gamemode, previous, debug, flat),
        dimensionNbtRespawn(protocol, dimensionType, world, seed, gamemode, previous, debug, flat));
  }

  /**
   * The pair for 1.16 and 1.16.1, whose Join Game and Respawn name the dimension type by key where
   * 1.16.2 carries it as NBT. A real 1.16 client switched DIRECT between two 1.16 servers sat on
   * "Loading terrain" without it, as 1.16.5 had.
   *
   * <p>Join Game: entity, gamemode, previous gamemode, world keys, dimension codec, dimension type key,
   * world key, hashed seed, max players (a byte), view distance, reduced debug, respawn screen, debug,
   * flat. Respawn: dimension type key, world key, hashed seed, gamemode, previous gamemode, debug,
   * flat, copy metadata.
   */
  private static List<byte[]> dimensionKeyPair(ProtocolDefinition protocol, byte[] packet) throws IOException {
    byte[] dimensionType;
    String world;
    long seed;
    int gamemode;
    int previous;
    boolean debug;
    boolean flat;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      MinecraftInput.varInt(input);              // packet id
      input.readInt();                           // entity id
      gamemode = input.readUnsignedByte();
      previous = input.readByte();
      int worlds = MinecraftInput.varInt(input);
      if (worlds < 0 || worlds > 1024) throw new IOException("world key count " + worlds);
      for (int i = 0; i < worlds; i++) MinecraftInput.string(input, 32767);
      NetworkNbt.skipNamed(input);               // dimension codec
      ByteArrayOutputStream type = new ByteArrayOutputStream();
      MinecraftOutput.string(new DataOutputStream(type), MinecraftInput.string(input, 32767));
      dimensionType = type.toByteArray();
      world = MinecraftInput.string(input, 32767);
      seed = input.readLong();
      input.readUnsignedByte();                  // max players
      MinecraftInput.varInt(input);              // view distance
      input.readBoolean();                       // reduced debug info
      input.readBoolean();                       // respawn screen
      debug = input.readBoolean();
      flat = input.readBoolean();
    }
    // The Respawn is the 1.16.2 one with the key where the NBT was, so the same writer serves both.
    String away = "minecraft:overworld".equals(world) ? "minecraft:the_nether" : "minecraft:overworld";
    return List.of(
        dimensionNbtRespawn(protocol, dimensionType, away, seed, gamemode, previous, debug, flat),
        dimensionNbtRespawn(protocol, dimensionType, world, seed, gamemode, previous, debug, flat));
  }

  private static byte[] dimensionNbtRespawn(ProtocolDefinition protocol, byte[] dimensionType, String world,
                                            long seed, int gamemode, int previous, boolean debug, boolean flat)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.write(dimensionType);
    MinecraftOutput.string(out, world);
    out.writeLong(seed);
    out.writeByte(gamemode);
    out.writeByte(previous);
    out.writeBoolean(debug);
    out.writeBoolean(flat);
    out.writeBoolean(false);                     // copy metadata
    return PlayPackets.withId(
        protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN),
        bytes.toByteArray());
  }

  /**
   * Reads the fields Respawn needs out of Join Game.
   *
   * <p>Up to 1.13.2 the layout is stable except for one field: the dimension is a signed byte until
   * 1.9.1 widened it to an int. 1.14 and 1.15 moved difficulty out and 1.15 added a hashed seed;
   * those are read with the Join Game codec that already knows them. A 1.15.2 client was once sent
   * this pair built from the older layout -- seed bytes read as difficulty, under the 1.14 Respawn
   * id that 1.15 gives to Resource Pack Send -- and disconnected reading a URL length of 268435455.
   */
  private static JoinGameWorld readWorld(ProtocolDefinition protocol, byte[] packet) throws IOException {
    if (ProtocolEras.joinGame114(protocol.version().number())) {
      var join = gg.tame.conduit.protocol.codec.JoinGameCodec.decode(protocol, packet);
      return new JoinGameWorld(join.dimensionId(), 0, join.gameMode() & 0x07, join.levelType(), join.hashedSeed());
    }
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
      return new JoinGameWorld(dimension, difficulty, gamemode, levelType, 0L);
    }
  }

  /** Respawn: dimension, then difficulty up to 1.13.2, the hashed seed from 1.15, gamemode, level type. */
  private static byte[] respawn(ProtocolDefinition protocol, int dimension, JoinGameWorld world) throws IOException {
    int number = protocol.version().number();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(dimension);
    if (ProtocolEras.joinGameHasDifficulty(number)) out.writeByte(world.difficulty());
    else if (ProtocolEras.hashedSeed(number)) out.writeLong(world.hashedSeed());
    out.writeByte(world.gamemode());
    MinecraftOutput.string(out, world.levelType());
    return PlayPackets.withId(
        protocol.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_RESPAWN),
        bytes.toByteArray());
  }
}

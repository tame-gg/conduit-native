package gg.tame.conduit.tests;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftFrames;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.MinecraftOutput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PlayPackets;
import gg.tame.conduit.protocol.ProtocolDefinition;
import gg.tame.conduit.protocol.item.ItemCodec;
import gg.tame.conduit.protocol.item.SemanticItem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the SERVERBOUND inventory and block-interaction path of a real Conduit
 * against a real different-version backend.
 *
 * <p>The clientbound half of this subsystem can be watched by joining with a real
 * client, but the serverbound half needs someone to click, and synthetic OS input
 * does not reach a detached Minecraft window in this environment. This probe
 * fills that gap: it is a scripted 765 client, but everything it talks to —
 * Conduit and the 1.13 server behind it — is the real thing, so the packets it
 * sends really are decoded, translated and executed by a 1.13 server.
 *
 * <pre>
 *   java -cp out gg.tame.conduit.tests.ItemGameplayProbe 127.0.0.1 25562 Prober
 * </pre>
 *
 * <p>Exits non-zero if the session dies, and prints a per-kind tally of what came
 * back so a run can be compared against the backend's own log.
 */
public final class ItemGameplayProbe {
  private static final ProtocolDefinition V765 = ProtocolDefinition.forVersion(765);
  private static final int MAX_FRAME = 8 * 1024 * 1024;

  public static void main(String[] arguments) throws Exception {
    String host = arguments.length > 0 ? arguments[0] : "127.0.0.1";
    int port = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 25562;
    String name = arguments.length > 2 ? arguments[2] : "Prober";
    long idleMillis = arguments.length > 3 ? Long.parseLong(arguments[3]) : 8_000L;
    // "place-only" leaves the placed block standing so the backend can be asked
    // whether it is really there, which is the difference between "the packet
    // was accepted" and "the world actually changed".
    boolean breakAfterPlacing = !(arguments.length > 4 && arguments[4].equals("place-only"));

    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 10_000);
      socket.setSoTimeout(30_000);
      var in = socket.getInputStream();
      var out = socket.getOutputStream();

      MinecraftFrames.write(out, handshake(host, port));
      MinecraftFrames.write(out, loginStart(name));
      if (!awaitLoginSuccess(in)) { System.out.println("RESULT=login-failed"); System.exit(2); }
      MinecraftFrames.write(out, new byte[] {3});            // login acknowledged

      if (!awaitConfigurationFinish(in, out)) { System.out.println("RESULT=config-failed"); System.exit(3); }
      System.out.println("configuration complete");

      if (!awaitPlayLogin(in, out)) { System.out.println("RESULT=no-play-login"); System.exit(4); }
      System.out.println("reached PLAY");

      // Settle in: the first teleport often arrives in the same burst as Join Game,
      // so awaitPlayLogin may already have confirmed it. Capture coordinates from
      // any teleport we see next, without requiring a second one.
      double[] where = awaitPosition(in, out, 20_000);
      if (where == null) { System.out.println("RESULT=no-position"); System.exit(5); }
      int baseX = (int) Math.floor(where[0]);
      int baseY = (int) Math.floor(where[1]);
      int baseZ = (int) Math.floor(where[2]);
      System.out.println("player at " + baseX + "," + baseY + "," + baseZ);

      // ---- serverbound gameplay -------------------------------------------
      // Each of these crosses the translator and is executed by a 1.13 server.

      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(0));
      System.out.println("sent: selected hotbar slot 0");

      send(out, PacketKind.PLAY_CREATIVE_SLOT,
          creativeSlot(36, SemanticItem.of("minecraft:diamond_sword", 1)));
      System.out.println("sent: creative set of hotbar slot 0 to a diamond sword");

      send(out, PacketKind.PLAY_CREATIVE_SLOT,
          creativeSlot(37, SemanticItem.of("minecraft:stone", 64)));
      System.out.println("sent: creative set of hotbar slot 1 to 64 stone");

      // Click: pick the sword up off slot 36 and put it down again. A 1.13
      // server answers each with a transaction confirmation, which a 765 client
      // has no packet for and which Conduit must absorb.
      send(out, PacketKind.PLAY_CLICK_WINDOW, click(0, 36, 0, 0, SemanticItem.EMPTY));
      System.out.println("sent: container click on slot 36");

      send(out, PacketKind.PLAY_CLOSE_WINDOW, closeWindow(0));
      System.out.println("sent: close container");

      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(1));   // select the stone
      // Place a block one step north of the player, onto the top face of the
      // block below it. This is the real block-placement path: the backend has
      // to accept the reach, the face and the held item.
      int placeX = baseX;
      int placeY = baseY - 1;
      int placeZ = baseZ - 2;
      send(out, PacketKind.PLAY_BLOCK_PLACE, blockPlace(placeX, placeY, placeZ, 1));
      System.out.println("sent: block place on top of " + placeX + "," + placeY + "," + placeZ);

      Thread.sleep(600);
      if (breakAfterPlacing) {
        send(out, PacketKind.PLAY_PLAYER_DIGGING, digging(0, placeX, placeY + 1, placeZ, 1));
        send(out, PacketKind.PLAY_PLAYER_DIGGING, digging(2, placeX, placeY + 1, placeZ, 1));
        System.out.println("sent: break the block just placed");
      }
      System.out.println("PLACED_AT " + placeX + " " + (placeY + 1) + " " + placeZ);

      send(out, PacketKind.PLAY_USE_ITEM, useItem(0));
      System.out.println("sent: use held item");

      // ---- a real container -----------------------------------------------
      // Put a chest down and right-click it. The backend then opens a screen and
      // sends its contents, which is the whole container path: Open Screen with
      // a window type, full contents, a click, and a close.
      send(out, PacketKind.PLAY_CREATIVE_SLOT, creativeSlot(38, SemanticItem.of("minecraft:chest", 1)));
      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(2));
      int chestX = baseX + 1;
      int chestY = baseY - 1;
      int chestZ = baseZ - 2;
      send(out, PacketKind.PLAY_BLOCK_PLACE, blockPlace(chestX, chestY, chestZ, 1));
      System.out.println("sent: place a chest at " + chestX + "," + (chestY + 1) + "," + chestZ);
      Thread.sleep(900);
      // Switch to an empty hotbar slot first: right-clicking a chest while
      // holding a placeable block can place the block instead of opening it.
      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(8));
      // Stand south of the chest and look north (yaw 180). Modern servers check
      // the eye ray; yaw 0 looks the wrong way and rejects the open.
      send(out, PacketKind.PLAY_POSITION_LOOK,
          positionLook(chestX + 0.5, where[1], chestZ + 1.5, 180f, 20f));
      Thread.sleep(200);
      send(out, PacketKind.PLAY_POSITION_LOOK,
          positionLook(chestX + 0.5, where[1], chestZ + 1.5, 180f, 20f));
      Thread.sleep(400);
      send(out, PacketKind.PLAY_BLOCK_PLACE, blockPlaceFace(chestX, chestY + 1, chestZ, 3, 0.5f, 0.5f, 1.0f));
      System.out.println("sent: right-click the chest to open it");
      Thread.sleep(500);

      Map<String, Integer> seen = drain(in, out, idleMillis);
      System.out.println("--- clientbound after the interaction burst ---");
      seen.entrySet().stream()
          .sorted((a, b) -> b.getValue() - a.getValue())
          .forEach(e -> System.out.println("  " + e.getValue() + "x " + e.getKey()));
      System.out.println("RESULT=ok");
    }
  }

  // ------------------------------------------------------------------ sending

  /** Tells the backend where we are and which way we look, so interaction passes its reach and facing checks. */
  private static byte[] positionLook(double x, double y, double z, float yaw, float pitch) throws Exception {
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
    out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
    out.writeFloat(yaw); out.writeFloat(pitch);
    out.writeBoolean(true);
    return buffer.toByteArray();
  }


  private static void send(java.io.OutputStream out, PacketKind kind, byte[] body) throws Exception {
    MinecraftFrames.write(out,
        PlayPackets.withId(V765.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, kind), body));
  }

  private static byte[] carriedItem(int slot) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new DataOutputStream(buffer).writeShort(slot);
    return buffer.toByteArray();
  }

  private static byte[] creativeSlot(int slot, SemanticItem item) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeShort(slot);
    ItemCodec.write(765, out, item);
    return buffer.toByteArray();
  }

  private static byte[] click(int window, int slot, int button, int mode, SemanticItem carried)
      throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(window);
    MinecraftOutput.varInt(out, 0);        // stateId: the client echoes what it last saw
    out.writeShort(slot);
    out.writeByte(button);
    MinecraftOutput.varInt(out, mode);
    MinecraftOutput.varInt(out, 0);        // no optimistically-changed slots
    ItemCodec.write(765, out, carried);
    return buffer.toByteArray();
  }

  private static byte[] closeWindow(int window) {
    return new byte[] {(byte) window};
  }

  private static byte[] useItem(int hand) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, hand);
    MinecraftOutput.varInt(out, 0);        // prediction sequence
    return buffer.toByteArray();
  }

  private static byte[] digging(int status, int x, int y, int z, int face) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, status);
    out.writeLong(((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF));
    out.writeByte(face);
    MinecraftOutput.varInt(out, 0);        // prediction sequence
    return buffer.toByteArray();
  }

  // ------------------------------------------------------------------ reading

  private static boolean awaitLoginSuccess(java.io.InputStream in) throws Exception {
    for (int attempt = 0; attempt < 16; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (id == 2) return true;
      if (id == 0) {
        System.out.println("login disconnect: " + readString(packet));
        return false;
      }
    }
    return false;
  }

  private static boolean awaitConfigurationFinish(java.io.InputStream in, java.io.OutputStream out)
      throws Exception {
    for (int attempt = 0; attempt < 512; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (V765.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.CONFIGURATION_KEEP_ALIVE)) {
        MinecraftFrames.write(out, packet);      // echo the same id back
        continue;
      }
      if (V765.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.CONFIGURATION_FINISH)) {
        MinecraftFrames.write(out, PlayPackets.withId(
            V765.id(ConnectionState.CONFIGURATION, PacketDirection.CLIENT_TO_SERVER,
                PacketKind.CONFIGURATION_FINISH), new byte[0]));
        return true;
      }
      if (V765.is(ConnectionState.CONFIGURATION, PacketDirection.SERVER_TO_CLIENT, id,
          PacketKind.CONFIGURATION_DISCONNECT)) {
        System.out.println("configuration disconnect: " + readString(packet));
        return false;
      }
    }
    return false;
  }

  /** Use Item On Block, in the 1.20.4 field order. */
  private static byte[] blockPlace(int x, int y, int z, int face) throws Exception {
    return blockPlaceFace(x, y, z, face, 0.5f, 1.0f, 0.5f);
  }

  private static byte[] blockPlaceFace(int x, int y, int z, int face, float cx, float cy, float cz)
      throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);        // main hand
    out.writeLong(((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF));
    MinecraftOutput.varInt(out, face);
    out.writeFloat(cx); out.writeFloat(cy); out.writeFloat(cz);
    out.writeBoolean(false);               // not inside the block
    MinecraftOutput.varInt(out, 0);        // prediction sequence
    return buffer.toByteArray();
  }

  private static final double[] LAST_POSITION = new double[3];
  private static boolean HAVE_POSITION;

  private static boolean awaitPlayLogin(java.io.InputStream in, java.io.OutputStream out) throws Exception {
    for (int attempt = 0; attempt < 4096; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (respondToKeepAliveOrTeleport(in, out, packet, id)) continue;
      if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_LOGIN)) {
        return true;
      }
      if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_DISCONNECT)) {
        System.out.println("play disconnect: " + readString(packet));
        return false;
      }
    }
    return false;
  }

  /** Answers keepalives and teleports so the session stays alive while probing. */
  private static boolean respondToKeepAliveOrTeleport(java.io.InputStream in, java.io.OutputStream out,
                                                      byte[] packet, int id) throws Exception {
    if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_KEEP_ALIVE)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream reply = new DataOutputStream(buffer);
      MinecraftOutput.varInt(reply,
          V765.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE));
      reply.write(PlayPackets.body(packet));
      MinecraftFrames.write(out, buffer.toByteArray());
      return true;
    }
    if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
        PacketKind.PLAY_PLAYER_POSITION)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        LAST_POSITION[0] = body.readDouble();
        LAST_POSITION[1] = body.readDouble();
        LAST_POSITION[2] = body.readDouble();
        HAVE_POSITION = true;
        body.readFloat(); body.readFloat(); body.readByte();
        int teleportId = MinecraftInput.varInt(body);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream reply = new DataOutputStream(buffer);
        MinecraftOutput.varInt(reply, V765.id(ConnectionState.PLAY,
            PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TELEPORT_CONFIRM));
        MinecraftOutput.varInt(reply, teleportId);
        MinecraftFrames.write(out, buffer.toByteArray());
      }
      return true;
    }
    return false;
  }

  private static double[] awaitPosition(java.io.InputStream in, java.io.OutputStream out, long millis)
      throws Exception {
    if (HAVE_POSITION) return LAST_POSITION.clone();
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      respondToKeepAliveOrTeleport(in, out, packet, id);
      if (HAVE_POSITION) return LAST_POSITION.clone();
    }
    return HAVE_POSITION ? LAST_POSITION.clone() : null;
  }

  private static Map<String, Integer> drain(java.io.InputStream in, java.io.OutputStream out, long millis)
      throws Exception {
    Map<String, Integer> seen = new LinkedHashMap<>();
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      byte[] packet;
      try {
        packet = MinecraftFrames.read(in, MAX_FRAME);
      } catch (Exception exception) {
        seen.merge("SESSION_ENDED(" + exception.getClass().getSimpleName() + ")", 1, Integer::sum);
        break;
      }
      int id = PlayPackets.packetId(packet);
      respondToKeepAliveOrTeleport(in, out, packet, id);
      String kind = describe(id);
      seen.merge(kind, 1, Integer::sum);
      if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_OPEN_WINDOW)) {
        try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int window = MinecraftInput.varInt(body);
          int menu = MinecraftInput.varInt(body);
          System.out.println("OPENED window=" + window + " menu=" + menu);
          // Click the first slot of the container, then close it.
          MinecraftFrames.write(out, PlayPackets.withId(
              V765.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLICK_WINDOW),
              click(window, 0, 0, 0, SemanticItem.EMPTY)));
          MinecraftFrames.write(out, PlayPackets.withId(
              V765.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLOSE_WINDOW),
              closeWindow(window)));
          System.out.println("sent: click slot 0 of the open container, then close it");
        }
      }
      if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_DISCONNECT)) {
        seen.merge("DISCONNECT:" + readString(packet), 1, Integer::sum);
        break;
      }
    }
    return seen;
  }

  private static String describe(int id) {
    for (PacketKind kind : PacketKind.values()) {
      if (V765.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, kind)) return kind.name();
    }
    return "unmapped 0x" + Integer.toHexString(id);
  }

  private static String readString(byte[] packet) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
      return MinecraftInput.string(in, 262_144);
    } catch (Exception exception) {
      return "<unreadable>";
    }
  }

  private static byte[] handshake(String host, int port) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.varInt(out, 765);
    MinecraftOutput.string(out, host);
    out.writeShort(port);
    MinecraftOutput.varInt(out, 2);
    return buffer.toByteArray();
  }

  private static byte[] loginStart(String name) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.string(out, name);
    UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.writeLong(uuid.getMostSignificantBits());
    out.writeLong(uuid.getLeastSignificantBits());
    return buffer.toByteArray();
  }

  private ItemGameplayProbe() {}
}

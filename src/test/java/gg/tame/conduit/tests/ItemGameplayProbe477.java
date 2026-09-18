// SPDX-License-Identifier: GPL-3.0-or-later
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

/**
 * The mirror of {@link ItemGameplayProbe}: a scripted protocol-477 client
 * driving the serverbound inventory and block path through a real Conduit into
 * a real 1.20.4 server.
 *
 * <p>The 393 side is not the 765 side with different numbers. There is no
 * configuration phase, login start carries no UUID, the slot is a present+VarInt,
 * a container click carries an action number the server must confirm, and
 * neither block placement nor digging carries the 1.19 prediction sequence. Each
 * of those is a place where Conduit has to invent or discard a field.
 *
 * <pre>
 *   java -cp out gg.tame.conduit.tests.ItemGameplayProbe477 127.0.0.1 25561 Prober114
 * </pre>
 */
public final class ItemGameplayProbe477 {
  private static final ProtocolDefinition V477 = ProtocolDefinition.forVersion(477);
  private static final int MAX_FRAME = 8 * 1024 * 1024;

  public static void main(String[] arguments) throws Exception {
    String host = arguments.length > 0 ? arguments[0] : "127.0.0.1";
    int port = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 25561;
    String name = arguments.length > 2 ? arguments[2] : "Prober114";
    long idleMillis = arguments.length > 3 ? Long.parseLong(arguments[3]) : 8_000L;
    boolean breakAfterPlacing = !(arguments.length > 4 && arguments[4].equals("place-only"));

    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 10_000);
      socket.setSoTimeout(30_000);
      var in = socket.getInputStream();
      var out = socket.getOutputStream();

      MinecraftFrames.write(out, handshake(host, port));
      MinecraftFrames.write(out, loginStart(name));
      if (!awaitLoginSuccess(in)) { System.out.println("RESULT=login-failed"); System.exit(2); }
      System.out.println("login success");

      if (!awaitPlayLogin(in, out)) { System.out.println("RESULT=no-play-login"); System.exit(3); }
      System.out.println("reached PLAY");

      // 1.13 expects the client to announce its settings; a modern backend uses
      // them to decide view distance, so send them before anything else.
      send(out, PacketKind.PLAY_CLIENT_INFORMATION, clientSettings());

      double[] where = awaitPosition(in, out, 20_000);
      if (where == null) { System.out.println("RESULT=no-position"); System.exit(4); }
      int baseX = (int) Math.floor(where[0]);
      int baseY = (int) Math.floor(where[1]);
      int baseZ = (int) Math.floor(where[2]);
      System.out.println("player at " + baseX + "," + baseY + "," + baseZ);

      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(0));
      System.out.println("sent: selected hotbar slot 0");

      send(out, PacketKind.PLAY_CREATIVE_SLOT, creativeSlot(36, SemanticItem.of("minecraft:stone", 64)));
      System.out.println("sent: creative set of hotbar slot 0 to 64 stone");

      send(out, PacketKind.PLAY_CLICK_WINDOW, click(0, 36, 0, 1, SemanticItem.EMPTY));
      System.out.println("sent: container click on slot 36");

      send(out, PacketKind.PLAY_CLOSE_WINDOW, new byte[] {0});
      System.out.println("sent: close container");

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
      // Geometry verified against a real 1.20.4 backend under translation
      // (probe393 …171215): chest at base+1/-2, stand south, yaw 0 / pitch 0,
      // north face (2).
      send(out, PacketKind.PLAY_CREATIVE_SLOT, creativeSlot(38, SemanticItem.of("minecraft:chest", 1)));
      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(2));
      int chestX = baseX + 1;
      int chestY = baseY - 1;
      int chestZ = baseZ - 2;
      send(out, PacketKind.PLAY_BLOCK_PLACE, blockPlace(chestX, chestY, chestZ, 1));
      System.out.println("sent: place a chest at " + chestX + "," + (chestY + 1) + "," + chestZ);
      Thread.sleep(900);
      send(out, PacketKind.PLAY_SET_CARRIED_ITEM, carriedItem(8));
      send(out, PacketKind.PLAY_POSITION_LOOK,
          positionLook(chestX + 0.5, where[1], chestZ + 1.5, 0f, 0f));
      Thread.sleep(200);
      send(out, PacketKind.PLAY_POSITION_LOOK,
          positionLook(chestX + 0.5, where[1], chestZ + 1.5, 0f, 0f));
      Thread.sleep(400);
      send(out, PacketKind.PLAY_BLOCK_PLACE, blockPlace(chestX, chestY + 1, chestZ, 2));
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
        PlayPackets.withId(V477.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, kind), body));
  }

  private static byte[] clientSettings() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.string(out, "en_GB");
    out.writeByte(8);                      // view distance
    MinecraftOutput.varInt(out, 0);        // chat enabled
    out.writeBoolean(true);                // chat colours
    out.writeByte(0x7f);                   // all skin parts
    MinecraftOutput.varInt(out, 1);        // right main hand
    return buffer.toByteArray();
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
    ItemCodec.write(477, out, item);
    return buffer.toByteArray();
  }

  private static byte[] click(int window, int slot, int button, int action, SemanticItem clicked)
      throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeByte(window);
    out.writeShort(slot);
    out.writeByte(button);
    out.writeShort(action);                // the number the server must confirm
    MinecraftOutput.varInt(out, 0);        // mode
    ItemCodec.write(477, out, clicked);
    return buffer.toByteArray();
  }

  private static byte[] useItem(int hand) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    MinecraftOutput.varInt(new DataOutputStream(buffer), hand);
    return buffer.toByteArray();
  }

  /** 1.14 Player Block Placement: hand, location, face, cursor, insideBlock. */
  private static byte[] blockPlace(int x, int y, int z, int face) throws Exception {
    return blockPlaceFace(x, y, z, face, 0.5f, 1.0f, 0.5f);
  }

  private static byte[] blockPlaceFace(int x, int y, int z, int face, float cx, float cy, float cz)
      throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);        // main hand first on 1.14
    out.writeLong(packed114(x, y, z));
    MinecraftOutput.varInt(out, face);
    out.writeFloat(cx); out.writeFloat(cy); out.writeFloat(cz);
    out.writeBoolean(false);               // insideBlock
    return buffer.toByteArray();
  }

  private static byte[] digging(int status, int x, int y, int z, int face) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, status);
    out.writeLong(packed114(x, y, z));
    out.writeByte(face);
    return buffer.toByteArray();
  }

  /** 1.14 packs a block position as x:26, z:26, y:12 — y in the low bits. */
  private static long packed114(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }

  // ------------------------------------------------------------------ reading

  private static boolean awaitLoginSuccess(java.io.InputStream in) throws Exception {
    for (int attempt = 0; attempt < 16; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      System.out.println("login packet id=" + id + " len=" + packet.length
          + " hex=" + hex(packet, Math.min(48, packet.length)));
      if (id == 2) return true;
      if (id == 0) { System.out.println("login disconnect: " + readString(packet)); return false; }
      if (id == 3) {
        System.out.println("login set-compression (unexpected toward client); continuing");
        continue;
      }
    }
    return false;
  }

  private static String hex(byte[] bytes, int length) {
    StringBuilder builder = new StringBuilder(length * 2);
    for (int index = 0; index < length; index++) {
      builder.append(String.format("%02x", bytes[index] & 0xff));
    }
    return builder.toString();
  }

  private static boolean awaitPlayLogin(java.io.InputStream in, java.io.OutputStream out) throws Exception {
    for (int attempt = 0; attempt < 4096; attempt++) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (answer(in, out, packet, id)) continue;
      if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_LOGIN)) return true;
      if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_DISCONNECT)) {
        System.out.println("play disconnect: " + readString(packet));
        return false;
      }
    }
    return false;
  }

  private static double[] awaitPosition(java.io.InputStream in, java.io.OutputStream out, long millis)
      throws Exception {
    long deadline = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < deadline) {
      byte[] packet = MinecraftFrames.read(in, MAX_FRAME);
      int id = PlayPackets.packetId(packet);
      if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_POSITION)) {
        double[] where;
        try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          where = new double[] {body.readDouble(), body.readDouble(), body.readDouble()};
        }
        answer(in, out, packet, id);
        return where;
      }
      answer(in, out, packet, id);
    }
    return null;
  }

  /** Keeps the session alive: keepalives, teleport confirmations, transactions. */
  private static boolean answer(java.io.InputStream in, java.io.OutputStream out, byte[] packet, int id)
      throws Exception {
    if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_KEEP_ALIVE)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream reply = new DataOutputStream(buffer);
      MinecraftOutput.varInt(reply,
          V477.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_KEEP_ALIVE));
      reply.write(PlayPackets.body(packet));
      MinecraftFrames.write(out, buffer.toByteArray());
      return true;
    }
    if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_PLAYER_POSITION)) {
      try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
        body.readDouble(); body.readDouble(); body.readDouble();
        body.readFloat(); body.readFloat(); body.readByte();
        int teleportId = MinecraftInput.varInt(body);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream reply = new DataOutputStream(buffer);
        MinecraftOutput.varInt(reply, V477.id(ConnectionState.PLAY,
            PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_TELEPORT_CONFIRM));
        MinecraftOutput.varInt(reply, teleportId);
        MinecraftFrames.write(out, buffer.toByteArray());
      }
      return true;
    }
    if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id,
        PacketKind.PLAY_CONFIRM_TRANSACTION)) {
      // Echo it back: a 1.13.2 client that does not is locked out of its own
      // inventory. Here the confirmation is Conduit's, synthesised because a
      // 1.20.4 backend has no such packet.
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      DataOutputStream reply = new DataOutputStream(buffer);
      MinecraftOutput.varInt(reply, V477.id(ConnectionState.PLAY,
          PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CONFIRM_TRANSACTION));
      reply.write(PlayPackets.body(packet));
      MinecraftFrames.write(out, buffer.toByteArray());
      System.out.println("received and echoed a Confirm Transaction (window/action/accepted)");
      return true;
    }
    return false;
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
      answer(in, out, packet, id);
      seen.merge(describe(id), 1, Integer::sum);
      if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_OPEN_WINDOW)) {
        try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(PlayPackets.body(packet)))) {
          int window = MinecraftInput.varInt(body);
          int menu = MinecraftInput.varInt(body);
          String title = MinecraftInput.string(body, 262_144);
          System.out.println("OPENED window=" + window + " menu=" + menu + " title=" + title);
          MinecraftFrames.write(out, PlayPackets.withId(
              V477.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLICK_WINDOW),
              click(window, 0, 0, 2, SemanticItem.EMPTY)));
          MinecraftFrames.write(out, PlayPackets.withId(
              V477.id(ConnectionState.PLAY, PacketDirection.CLIENT_TO_SERVER, PacketKind.PLAY_CLOSE_WINDOW),
              new byte[] {(byte) window}));
          System.out.println("sent: click slot 0 of the open container, then close it");
        }
      }
      if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, PacketKind.PLAY_DISCONNECT)) {
        seen.merge("DISCONNECT:" + readString(packet), 1, Integer::sum);
        break;
      }
    }
    return seen;
  }

  private static String describe(int id) {
    for (PacketKind kind : PacketKind.values()) {
      if (V477.is(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, id, kind)) return kind.name();
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
    MinecraftOutput.varInt(out, 477);
    MinecraftOutput.string(out, host);
    out.writeShort(port);
    MinecraftOutput.varInt(out, 2);
    return buffer.toByteArray();
  }

  /** 1.13 login start is the username alone — no UUID field. */
  private static byte[] loginStart(String name) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    MinecraftOutput.varInt(out, 0);
    MinecraftOutput.string(out, name);
    return buffer.toByteArray();
  }

  private ItemGameplayProbe477() {}
}

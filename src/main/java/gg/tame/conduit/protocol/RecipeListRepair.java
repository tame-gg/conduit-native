package gg.tame.conduit.protocol;

import gg.tame.conduit.log.ConduitLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repairs a Declare Recipes packet addressed to a 1.13-family client before it reaches the socket.
 *
 * <p>Background. A 1.13 item slot is a big-endian short id, and when that id is {@code -1} the slot
 * is empty and nothing else follows it: no count, no tag. Conduit has observed a translated
 * Declare Recipes packet in which a recipe whose result has no 1.13 counterpart is written with id
 * {@code -1} <em>and</em> a trailing count and NBT compound. A 1.13 client reads those trailing
 * bytes as the beginning of the next recipe's identifier, fails to parse it as a resource location,
 * and closes the connection. Every recipe after the first such result is lost with it.
 *
 * <p>What this does. The packet is parsed twice against the published 1.13 layout. If the strict
 * reading — empty slots carry nothing — consumes the packet exactly, the packet is already correct
 * and is returned untouched; that is the path a fixed translator and Conduit's own translators take.
 * Otherwise a tolerant reading, in which every slot carries a count and a tag regardless of its id,
 * is tried. If that consumes the packet exactly, the recipe boundaries are known, and Conduit
 * re-emits the packet with every recipe that contains an empty slot removed and the recipe count
 * corrected. Those recipes could not have been useful to the client: their result does not exist on
 * its version. Every other recipe is copied byte for byte, so nothing is re-encoded and nothing is
 * invented.
 *
 * <p>If neither reading fits, Conduit does not guess. It emits an empty recipe list, which is a
 * valid packet that leaves the recipe book unpopulated, and logs the fact loudly rather than
 * forwarding bytes that are known to disconnect the client.
 *
 * <p>This is Conduit's own repair of Conduit's own outbound byte stream, written from the 1.13 wire
 * layout. It is deliberately narrow: only a 1.13-family client, only this packet.
 */
public final class RecipeListRepair {
  private RecipeListRepair() {}

  private static final int LOWEST = 393;   // 1.13
  private static final int HIGHEST = 404;  // 1.13.2
  private static final AtomicBoolean REPORTED = new AtomicBoolean();

  /** True when this client's Declare Recipes packet uses the layout this class understands. */
  public static boolean handles(int clientProtocol) {
    return clientProtocol >= LOWEST && clientProtocol <= HIGHEST;
  }

  /**
   * Returns {@code packet} unchanged unless it is a Declare Recipes packet for a 1.13-family client
   * that the client could not read, in which case a readable replacement is returned.
   */
  public static byte[] apply(ProtocolDefinition client, byte[] packet) {
    if (packet == null || packet.length == 0) return packet;
    if (!handles(client.version().number())) return packet;
    if (!client.defines(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_RECIPES)) {
      return packet;
    }
    int expected = client.id(ConnectionState.PLAY, PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY_DECLARE_RECIPES);
    Cursor head = new Cursor(packet);
    int id;
    try {
      id = head.varInt();
    } catch (IOException malformed) {
      return packet;
    }
    if (id != expected) return packet;
    int body = head.offset();

    // 1.13.2 changed the slot to a present flag and a VarInt id. Read with 1.13's short id, a real
    // 1.13.2 server's recipe list fit neither reading and every 1.13.2 client got an empty one.
    boolean presentFlag = ProtocolEras.slotPresentFlag(client.version().number());
    if (scan(packet, body, false, presentFlag) != null) return packet;

    List<int[]> recipes = scan(packet, body, true, presentFlag);
    if (recipes == null) {
      warnOnce("Conduit could not read the translated Declare Recipes packet for protocol "
          + client.version().number() + " (" + packet.length + " bytes); sending an empty recipe list");
      return emptyList(expected);
    }
    int dropped = 0;
    ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length);
    DataOutputStream sink = new DataOutputStream(out);
    List<int[]> kept = new ArrayList<>(recipes.size());
    for (int[] recipe : recipes) {
      if (recipe[2] == 1) dropped++;
      else kept.add(recipe);
    }
    if (dropped == 0) {
      // The tolerant reading fit but nothing is actually unrepresentable, so the strict reading
      // should have fit too. Rather than rewrite a packet Conduit does not understand, leave it.
      return packet;
    }
    try {
      MinecraftOutput.varInt(sink, expected);
      MinecraftOutput.varInt(sink, kept.size());
      for (int[] recipe : kept) sink.write(packet, recipe[0], recipe[1] - recipe[0]);
    } catch (IOException unreachable) {
      return emptyList(expected);
    }
    warnOnce("Conduit dropped " + dropped + " recipe(s) from Declare Recipes for protocol "
        + client.version().number() + ": their result item has no counterpart on that version and the"
        + " translated slot was not a valid empty slot. " + kept.size() + " recipe(s) forwarded.");
    return out.toByteArray();
  }

  private static byte[] emptyList(int packetId) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8);
    DataOutputStream sink = new DataOutputStream(out);
    try {
      MinecraftOutput.varInt(sink, packetId);
      MinecraftOutput.varInt(sink, 0);
    } catch (IOException unreachable) {
      throw new IllegalStateException(unreachable);
    }
    return out.toByteArray();
  }

  private static void warnOnce(String message) {
    if (REPORTED.compareAndSet(false, true)) ConduitLog.warn(message);
    else ProtocolTrace.note(message);
  }

  /**
   * Reads the recipe list and returns one {@code {start, end, hasEmptySlot}} triple per recipe, or
   * {@code null} if the reading does not account for the packet exactly.
   */
  private static List<int[]> scan(byte[] packet, int body, boolean tolerant, boolean presentFlag) {
    Cursor cursor = new Cursor(packet, body);
    cursor.presentFlag = presentFlag;
    try {
      int count = cursor.varInt();
      if (count < 0 || count > 1_000_000) return null;
      List<int[]> recipes = new ArrayList<>(Math.min(count, 4096));
      for (int index = 0; index < count; index++) {
        int start = cursor.offset();
        cursor.empty = false;
        cursor.string();
        String type = cursor.string();
        readBody(cursor, type, tolerant);
        recipes.add(new int[] {start, cursor.offset(), cursor.empty ? 1 : 0});
      }
      return cursor.offset() == packet.length ? recipes : null;
    } catch (IOException | RuntimeException mismatch) {
      return null;
    }
  }

  private static void readBody(Cursor cursor, String type, boolean tolerant) throws IOException {
    String name = type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
    switch (name) {
      case "crafting_shapeless" -> {
        cursor.string();
        int ingredients = cursor.varInt();
        for (int index = 0; index < ingredients; index++) cursor.ingredient(tolerant);
        cursor.slot(tolerant);
      }
      case "crafting_shaped" -> {
        int width = cursor.varInt();
        int height = cursor.varInt();
        if (width < 0 || height < 0 || width > 16 || height > 16) throw new IOException("recipe shape");
        cursor.string();
        for (int index = 0; index < width * height; index++) cursor.ingredient(tolerant);
        cursor.slot(tolerant);
      }
      case "smelting" -> {
        cursor.string();
        cursor.ingredient(tolerant);
        cursor.slot(tolerant);
        cursor.skip(4); // experience
        cursor.varInt(); // cooking time
      }
      default -> {
        // The crafting_special_* recipes carry no body on 1.13.
      }
    }
  }

  /** A bounds-checked reader over the packet that also remembers whether an empty slot was seen. */
  private static final class Cursor {
    private final byte[] data;
    private final DataInputStream input;
    private final ByteArrayInputStream source;
    private boolean empty;
    /** 1.13.2's slot layout: a present flag, then a VarInt id. */
    private boolean presentFlag;

    Cursor(byte[] data) { this(data, 0); }

    Cursor(byte[] data, int from) {
      this.data = data;
      this.source = new ByteArrayInputStream(data, from, data.length - from);
      this.input = new DataInputStream(source);
    }

    int offset() { return data.length - source.available(); }

    int varInt() throws IOException { return MinecraftInput.varInt(input); }

    String string() throws IOException { return MinecraftInput.string(input, 32767 * 4); }

    void skip(int bytes) throws IOException { input.skipBytes(bytes); }

    void ingredient(boolean tolerant) throws IOException {
      int options = varInt();
      if (options < 0 || options > 4096) throw new IOException("ingredient size");
      for (int index = 0; index < options; index++) slot(tolerant);
    }

    void slot(boolean tolerant) throws IOException {
      if (presentFlag) {
        if (!input.readBoolean()) {
          empty = true;
          if (!tolerant) return;
        }
        varInt();
      } else if (input.readShort() == -1) {
        empty = true;
        if (!tolerant) return;
      }
      input.readByte();
      NetworkNbt.skipNamed(input);
    }
  }
}

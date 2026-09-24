// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.player.ServerLink;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the proxy writes for a plugin's custom chat completions, server links and cookies, and the
 * client's answer to a cookie request, which it reads. Layouts from the published packet data
 * (minecraft-data); each release's ids are its protocol table's.
 */
public final class PlayerApiPackets {
  private PlayerApiPackets() {}

  /** The most a cookie holds. The client refuses a longer one, and never sends one. */
  public static final int COOKIE_MAX_BYTES = 5120;
  private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  /** Custom Chat Completions (1.19.1+): the action (0 add, 1 remove, 2 set), then the strings. */
  public static byte[] chatSuggestions(int id, int action, Collection<String> entries) throws IOException {
    return packet(id, output -> {
      MinecraftOutput.varInt(output, action);
      MinecraftOutput.varInt(output, entries.size());
      for (String entry : entries) MinecraftOutput.string(output, entry);
    });
  }

  /**
   * Server Links (1.21+): per link, whether it is a known type, then that type's id or a text label,
   * then the URL.
   */
  public static byte[] serverLinks(int id, int protocol, List<ServerLink> links) throws IOException {
    return packet(id, output -> {
      MinecraftOutput.varInt(output, links.size());
      for (ServerLink link : links) {
        output.writeBoolean(link.type() != null);
        if (link.type() != null) MinecraftOutput.varInt(output, link.type().ordinal());
        else gg.tame.conduit.text.TextCodec.write(output, link.label(), protocol);
        MinecraftOutput.string(output, link.url().toString());
      }
    });
  }

  /** Store Cookie (1.20.5+): the key, then the cookie as a length-prefixed array. */
  public static byte[] storeCookie(int id, String key, byte[] data) throws IOException {
    return packet(id, output -> {
      MinecraftOutput.string(output, key);
      MinecraftOutput.varInt(output, data.length);
      output.write(data);
    });
  }

  /** Clear Dialog (1.21.6+): the id and nothing else. */
  public static byte[] clearDialog(int id) throws IOException {
    return packet(id, output -> { });
  }

  /** Cookie Request (1.20.5+): the key alone. */
  public static byte[] cookieRequest(int id, String key) throws IOException {
    return packet(id, output -> MinecraftOutput.string(output, key));
  }

  /**
   * The key and cookie of a client's Cookie Response in {@code state}, the cookie null when it had none;
   * empty for any other packet, or one that does not read as a response.
   */
  public static Optional<CookieResponse> cookieResponse(ProtocolDefinition protocol, ConnectionState state, byte[] packet) {
    PacketKind kind = state == ConnectionState.PLAY ? PacketKind.PLAY_COOKIE_RESPONSE
        : state == ConnectionState.CONFIGURATION ? PacketKind.CONFIGURATION_COOKIE_RESPONSE : null;
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
      if (kind == null || !protocol.is(state, PacketDirection.CLIENT_TO_SERVER, MinecraftInput.varInt(input), kind)) return Optional.empty();
      String key = MinecraftInput.string(input, 32767);
      byte[] data = input.readBoolean() ? MinecraftInput.bytes(input, COOKIE_MAX_BYTES) : null;
      return input.available() == 0 ? Optional.of(new CookieResponse(key, data)) : Optional.empty();
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }
  public record CookieResponse(String key, byte[] data) {}

  /**
   * {@code key} as the client writes it back, namespace and all: a key with no namespace is
   * {@code minecraft:}'s, as the client reads it.
   *
   * @throws IllegalArgumentException for anything the client would not read as a key, which it
   *     would disconnect over
   */
  public static String cookieKey(String key) {
    if (key == null) throw new IllegalArgumentException("a cookie key is required");
    String full = key.indexOf(':') < 0 ? "minecraft:" + key : key;
    if (!KEY.matcher(full).matches()) throw new IllegalArgumentException("not a namespaced key: " + key);
    return full;
  }

  private interface Body { void write(DataOutputStream output) throws IOException; }
  private static byte[] packet(int id, Body body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      MinecraftOutput.varInt(output, id);
      body.write(output);
    }
    return bytes.toByteArray();
  }
}

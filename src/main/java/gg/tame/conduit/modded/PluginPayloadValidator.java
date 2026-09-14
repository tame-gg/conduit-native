package gg.tame.conduit.modded;

import gg.tame.conduit.protocol.MinecraftInput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lightweight plugin-message payload checks for modded traffic.
 * Distinct from Phase 2 channel-guard policy (log/drop/kick).
 */
public final class PluginPayloadValidator {
  public static final int MAX_CHANNEL_CHARS = 256;
  public static final int MAX_COLLECTION = 4096;

  private PluginPayloadValidator() {}

  public static void validateChannel(String channel) throws IOException {
    if (channel == null || channel.isBlank()) throw new IOException("empty plugin channel");
    if (channel.length() > MAX_CHANNEL_CHARS) throw new IOException("plugin channel too long");
    if (channel.indexOf('\0') >= 0) throw new IOException("plugin channel contains NUL");
    String lower = channel.toLowerCase(Locale.ROOT);
    if (lower.equals("minecraft:") || lower.endsWith(":") || lower.startsWith(":")) {
      throw new IOException("malformed plugin channel");
    }
  }

  public static void validatePayload(byte[] data, int maximumBytes) throws IOException {
    if (data == null) throw new IOException("plugin payload is null");
    if (data.length > maximumBytes) throw new IOException("plugin payload exceeds limit");
  }

  /** Best-effort UTF-8 string field check without allocating large strings first. */
  public static String readBoundedUtf8(DataInputStream input, int maximumBytes) throws IOException {
    int length = MinecraftInput.varInt(input);
    if (length < 0 || length > maximumBytes) throw new IOException("string length exceeds limit");
    byte[] bytes = new byte[length];
    input.readFully(bytes);
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException exception) {
      throw new IOException("invalid UTF-8 in payload", exception);
    }
  }

  public static int readBoundedCollectionSize(DataInputStream input, int maximum) throws IOException {
    int size = MinecraftInput.varInt(input);
    if (size < 0 || size > maximum) throw new IOException("collection size exceeds limit");
    return size;
  }

  public static void ensureDecodableBody(byte[] body, int maximumBytes) throws IOException {
    if (body == null) throw new IOException("empty body");
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
      String channel = MinecraftInput.string(input, MAX_CHANNEL_CHARS);
      validateChannel(channel);
      byte[] data = input.readAllBytes();
      validatePayload(data, maximumBytes);
    }
  }
}

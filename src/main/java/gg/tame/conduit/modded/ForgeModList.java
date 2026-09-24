// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

import gg.tame.conduit.api.server.ModInfo;
import gg.tame.conduit.protocol.MinecraftInput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Forge mod lists read off the wire, never written back: a 1.13+ client's, from its answer to the
 * login handshake, and a Forge server's, from its status answer. Anything that does not read as
 * one is no mod list, and nothing else is decided by it.
 */
public final class ForgeModList {
  private static final String WRAPPER = "fml:loginwrapper";
  private static final String HANDSHAKE = "fml:handshake";
  /** The handshake's C2SModListReply. */
  private static final int MOD_LIST_REPLY = 2;
  private static final int MAX_STRING = 32767;

  private ForgeModList() {}

  /**
   * The mod ids in a Login Plugin Response {@code packet} (packet id first) that carries Forge's
   * ModListReply; null for any other answer.
   *
   * <p>Two wrappings are read. Forge's documented one: the inner channel {@code fml:handshake}, then
   * the length-prefixed handshake packet. And the one a real NeoForge 20.2.93 client sent, which puts
   * the message id and {@code fml:loginwrapper} in front of that again.
   */
  public static List<String> clientMods(byte[] packet) {
    try {
      DataInputStream input = stream(packet, 0, packet.length);
      MinecraftInput.varInt(input);
      MinecraftInput.varInt(input);
      if (!input.readBoolean()) return null;
      byte[] data = input.readAllBytes();
      DataInputStream handshake = handshake(data, false);
      if (handshake == null) handshake = handshake(data, true);
      if (handshake == null || MinecraftInput.varInt(handshake) != MOD_LIST_REPLY) return null;
      int count = MinecraftInput.varInt(handshake);
      if (count < 0 || count > handshake.available()) return null;
      List<String> mods = new ArrayList<>(count);
      for (int index = 0; index < count; index++) mods.add(MinecraftInput.string(handshake, MAX_STRING));
      return mods;
    } catch (IOException | RuntimeException notAModList) {
      return null;
    }
  }
  private static DataInputStream handshake(byte[] data, boolean rewrapped) {
    try {
      DataInputStream input = stream(data, 0, data.length);
      if (rewrapped) {
        MinecraftInput.varInt(input);
        if (!WRAPPER.equals(MinecraftInput.string(input, 64))) return null;
      }
      if (!HANDSHAKE.equals(MinecraftInput.string(input, 64))) return null;
      int length = MinecraftInput.varInt(input);
      if (length < 0 || length > input.available()) return null;
      int start = data.length - input.available();
      return stream(data, start, length);
    } catch (IOException | RuntimeException notThisWrapping) {
      return null;
    }
  }

  /**
   * The mod list in a status answer's JSON {@code root}, as a Forge server gives it: {@code modinfo}
   * from 1.7-1.12, {@code forgeData} from 1.13 and {@code neoForgeData} from NeoForge. Empty for
   * none, or for one that does not read.
   */
  public static Optional<ModInfo> fromStatus(Map<?, ?> root) {
    try {
      if (root.get("modinfo") instanceof Map<?, ?> legacy) {
        List<ModInfo.Mod> mods = new ArrayList<>();
        if (legacy.get("modList") instanceof List<?> list) {
          for (Object entry : list) {
            if (entry instanceof Map<?, ?> mod && mod.get("modid") instanceof String id) {
              mods.add(new ModInfo.Mod(id, mod.get("version") instanceof String version ? version : ""));
            }
          }
        }
        return Optional.of(new ModInfo(legacy.get("type") instanceof String type ? type : "FML", mods));
      }
      Object data = root.get("forgeData") != null ? root.get("forgeData") : root.get("neoForgeData");
      if (!(data instanceof Map<?, ?> forge)) return Optional.empty();
      String type = "FML" + (forge.get("fmlNetworkVersion") instanceof Number version ? version.intValue() : 2);
      if (forge.get("d") instanceof String packed) return Optional.of(new ModInfo(type, packedMods(packed)));
      List<ModInfo.Mod> mods = new ArrayList<>();
      if (forge.get("mods") instanceof List<?> list) {
        for (Object entry : list) {
          if (entry instanceof Map<?, ?> mod && mod.get("modId") instanceof String id) {
            mods.add(new ModInfo.Mod(id, mod.get("modmarker") instanceof String marker ? marker : ""));
          }
        }
      }
      return Optional.of(new ModInfo(type, mods));
    } catch (IOException | RuntimeException unreadable) {
      return Optional.empty();
    }
  }

  /**
   * The {@code d} field Forge 1.18.2+ and NeoForge send in place of the mod list: bytes packed 15
   * bits to a character, the first two characters holding the byte count. The bytes are a truncated
   * flag, an unsigned short mod count, then per mod a VarInt whose low bit says the version is left
   * out and whose rest counts the mod's channels, the id, the version, and each channel's name,
   * version and required flag. Read off a real NeoForge 20.2.93 server's answer.
   */
  static List<ModInfo.Mod> packedMods(String packed) throws IOException {
    if (packed.length() < 2) throw new IOException("no length");
    int length = packed.charAt(0) | packed.charAt(1) << 15;
    if (length < 0 || length > packed.length() * 2) throw new IOException("length past the data");
    byte[] bytes = new byte[length];
    int filled = 0;
    long bits = 0;
    int held = 0;
    for (int index = 2; index < packed.length() && filled < length; index++) {
      bits |= (long) (packed.charAt(index) & 0x7FFF) << held;
      held += 15;
      while (held >= 8 && filled < length) {
        bytes[filled++] = (byte) bits;
        bits >>>= 8;
        held -= 8;
      }
    }
    if (filled < length) throw new IOException("data shorter than its length");
    DataInputStream input = stream(bytes, 0, length);
    input.readBoolean();
    int count = input.readUnsignedShort();
    List<ModInfo.Mod> mods = new ArrayList<>(Math.min(count, length));
    for (int index = 0; index < count; index++) {
      int flags = MinecraftInput.varInt(input);
      String id = MinecraftInput.string(input, MAX_STRING);
      String version = (flags & 1) == 0 ? MinecraftInput.string(input, MAX_STRING) : "";
      for (int channel = 0; channel < flags >>> 1; channel++) {
        MinecraftInput.string(input, MAX_STRING);
        MinecraftInput.string(input, MAX_STRING);
        input.readBoolean();
      }
      mods.add(new ModInfo.Mod(id, version));
    }
    return mods;
  }

  private static DataInputStream stream(byte[] data, int offset, int length) {
    return new DataInputStream(new ByteArrayInputStream(data, offset, length));
  }
}

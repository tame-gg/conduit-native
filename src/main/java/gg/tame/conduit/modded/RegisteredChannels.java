// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.modded;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The plugin channels a client has announced, kept so a backend it is moved to can be told them.
 *
 * <p>Registration is per connection, not per player. A client announces its channels once, while it
 * configures, and never again; a server it reaches after a {@code /server} is never told. Every mod
 * channel a Forge or NeoForge client speaks, and every plugin channel besides, is invisible to the
 * second backend the player ever sees.
 *
 * <p>The register channel is whatever the client used — {@code minecraft:register} from 1.13,
 * {@code REGISTER} before it — echoed back rather than derived, so no version rule is needed.
 */
public final class RegisteredChannels {
  public static final String REGISTER = "minecraft:register";
  public static final String UNREGISTER = "minecraft:unregister";
  public static final String LEGACY_REGISTER = "REGISTER";
  public static final String LEGACY_UNREGISTER = "UNREGISTER";
  /** The client fills this list, so it is bounded. A real client announces a few dozen at most. */
  public static final int MAX_CHANNELS = 256;

  private final Set<String> channels = new LinkedHashSet<>();
  private String registerChannel = REGISTER;

  /** What one register or unregister message named: at most {@link #MAX_CHANNELS} channels. */
  public record Change(boolean register, List<String> channels) {}

  /** Records a register or unregister payload. Returns true when {@code channel} was one. */
  public boolean observe(String channel, byte[] payload) { return record(channel, payload) != null; }

  /**
   * Records a register or unregister payload and returns the channels it named, at most
   * {@link #MAX_CHANNELS} of them, blank and over-long names left out; null when {@code channel} is
   * neither. The payload is scanned rather than split, so a hostile list of millions of names costs a
   * walk over the bytes, not millions of strings.
   */
  public synchronized Change record(String channel, byte[] payload) {
    boolean add = REGISTER.equalsIgnoreCase(channel) || LEGACY_REGISTER.equals(channel);
    boolean remove = UNREGISTER.equalsIgnoreCase(channel) || LEGACY_UNREGISTER.equals(channel);
    if (!add && !remove) return null;
    if (add) registerChannel = channel;
    Set<String> named = new LinkedHashSet<>();
    if (payload == null) return new Change(add, List.of());
    String text = new String(payload, StandardCharsets.UTF_8);
    for (int start = 0, end; start < text.length(); start = end + 1) {
      end = text.indexOf('\0', start);
      if (end < 0) end = text.length();
      int length = end - start;
      if (length == 0 || length > PluginPayloadValidator.MAX_CHANNEL_CHARS) continue;
      // Past every cap there is nothing left for a name to change, so none is made.
      boolean changes = remove ? !channels.isEmpty() : channels.size() < MAX_CHANNELS;
      if (!changes && named.size() >= MAX_CHANNELS) continue;
      String name = text.substring(start, end);
      if (name.isBlank()) continue;
      if (named.size() < MAX_CHANNELS) named.add(name);
      if (remove) channels.remove(name);
      else if (channels.size() < MAX_CHANNELS) channels.add(name);
    }
    return new Change(add, List.copyOf(named));
  }

  public synchronized Set<String> channels() { return Set.copyOf(channels); }

  /**
   * The registration packet to send a backend in {@code state}, or empty when there is nothing to
   * say or that state has no plugin message on this protocol.
   */
  public synchronized Optional<byte[]> replay(ProtocolDefinition protocol, ConnectionState state) throws IOException {
    if (channels.isEmpty()) return Optional.empty();
    PacketKind kind = state == ConnectionState.CONFIGURATION
        ? PacketKind.CONFIGURATION_PLUGIN_MESSAGE
        : PacketKind.PLAY_PLUGIN_MESSAGE;
    if (!protocol.defines(state, PacketDirection.CLIENT_TO_SERVER, kind)) return Optional.empty();
    byte[] payload = String.join("\0", channels).getBytes(StandardCharsets.UTF_8);
    return Optional.of(new PluginMessage(registerChannel, payload)
        .encode(protocol.id(state, PacketDirection.CLIENT_TO_SERVER, kind)));
  }
}

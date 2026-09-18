package gg.tame.conduit.modded;

import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.PluginMessage;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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

  /** Records a register or unregister payload. Returns true when {@code channel} was one. */
  public synchronized boolean observe(String channel, byte[] payload) {
    boolean add = REGISTER.equalsIgnoreCase(channel) || LEGACY_REGISTER.equals(channel);
    boolean remove = UNREGISTER.equalsIgnoreCase(channel) || LEGACY_UNREGISTER.equals(channel);
    if (!add && !remove) return false;
    if (add) registerChannel = channel;
    if (payload == null) return true;
    for (String name : new String(payload, StandardCharsets.UTF_8).split("\0")) {
      if (name.isBlank() || name.length() > PluginPayloadValidator.MAX_CHANNEL_CHARS) continue;
      if (remove) channels.remove(name);
      else if (channels.size() < MAX_CHANNELS) channels.add(name);
    }
    return true;
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

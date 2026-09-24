// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.forwarding;

import gg.tame.conduit.config.ForwardingMode;
import gg.tame.conduit.login.PlayerProfile;
import gg.tame.conduit.login.ProfileProperty;
import gg.tame.conduit.modded.FmlAddressMarkers;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * BungeeCord-style forwarding: the identity rides in the backend handshake's host field as
 * {@code host \0 clientIp \0 uuidWithoutDashes [\0 propertiesJson]}, the form a Spigot or Paper
 * backend with {@code bungeecord: true} splits and reads. Nothing signs it, so a backend reachable
 * by anyone but Conduit believes whoever connects. BungeeGuard adds one property, a shared token,
 * which the backend plugin checks; the token is the forwarding secret.
 *
 * <p>A Forge marker cannot stay on the host: the backend accepts three or four parts and no more.
 * It goes in an {@code extraData} property instead, its NULs written as \1, which is where
 * Forge-aware backends of this kind look for it.
 */
public final class LegacyForwarder implements PlayerInfoForwarder {
  public static final String TOKEN_PROPERTY = "bungeeguard-token";
  private final Optional<String> token;

  private LegacyForwarder(Optional<String> token) { this.token = token; }
  public static LegacyForwarder legacy() { return new LegacyForwarder(Optional.empty()); }
  public static LegacyForwarder bungeeGuard(ForwardingSecret secret) {
    return new LegacyForwarder(Optional.of(new String(secret.bytes(), StandardCharsets.UTF_8)));
  }

  @Override public ForwardingMode mode() { return token.isPresent() ? ForwardingMode.BUNGEEGUARD : ForwardingMode.LEGACY; }
  /** Nothing is sent at login: the backend already has it all from the handshake. */
  @Override public byte[] payload(ForwardingRequest request) { return new byte[0]; }

  @Override public String handshakeHost(String host, PlayerProfile player, InetAddress client) {
    FmlAddressMarkers.ParsedHost parsed = FmlAddressMarkers.parse(host);
    StringBuilder out = new StringBuilder(parsed.cleanHost())
        .append('\0').append(client.getHostAddress())
        .append('\0').append(player.uniqueId().toString().replace("-", ""));
    StringBuilder properties = new StringBuilder();
    for (ProfileProperty property : player.properties()) {
      property(properties, property.name(), property.value(), property.signature());
    }
    if (parsed.hasMarker()) {
      String marker = FmlAddressMarkers.append("", parsed.marker()).replace('\0', '\1');
      property(properties, "extraData", marker, Optional.empty());
    }
    token.ifPresent(value -> property(properties, TOKEN_PROPERTY, value, Optional.empty()));
    if (!properties.isEmpty()) out.append('\0').append('[').append(properties).append(']');
    return out.toString();
  }

  private static void property(StringBuilder out, String name, String value, Optional<String> signature) {
    if (!out.isEmpty()) out.append(',');
    out.append("{\"name\":").append(quote(name)).append(",\"value\":").append(quote(value));
    signature.ifPresent(signed -> out.append(",\"signature\":").append(quote(signed)));
    out.append('}');
  }

  /** Control characters escaped, NUL above all: one raw would split the host field in the wrong place. */
  private static String quote(String value) {
    StringBuilder text = new StringBuilder("\"");
    for (char character : value.toCharArray()) {
      if (character == '"' || character == '\\') text.append('\\').append(character);
      else if (character < 0x20) text.append(String.format("\\u%04x", (int) character));
      else text.append(character);
    }
    return text.append('"').toString();
  }
}

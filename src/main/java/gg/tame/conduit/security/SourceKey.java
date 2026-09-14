package gg.tame.conduit.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;

/** Source grouping key: IPv4 host bits masked by prefix, IPv6 by prefix (default /64). */
public final class SourceKey {
  private final byte[] bytes;
  private final int prefix;
  private final int hash;

  private SourceKey(byte[] bytes, int prefix) {
    this.bytes = bytes;
    this.prefix = prefix;
    this.hash = Arrays.hashCode(bytes) * 31 + prefix;
  }

  public static SourceKey of(InetAddress address, int ipv4Prefix, int ipv6Prefix) {
    Objects.requireNonNull(address, "address");
    byte[] raw = address.getAddress();
    int prefix = address instanceof Inet6Address ? ipv6Prefix : ipv4Prefix;
    if (address instanceof Inet4Address) prefix = Math.min(32, Math.max(8, prefix));
    else prefix = Math.min(128, Math.max(16, prefix));
    return new SourceKey(mask(raw, prefix), prefix);
  }

  private static byte[] mask(byte[] raw, int prefix) {
    byte[] out = Arrays.copyOf(raw, raw.length);
    int full = prefix / 8;
    int rem = prefix % 8;
    for (int i = full + (rem == 0 ? 0 : 1); i < out.length; i++) out[i] = 0;
    if (rem != 0 && full < out.length) {
      int keep = 0xFF << (8 - rem);
      out[full] = (byte) (out[full] & keep);
    }
    return out;
  }

  @Override public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof SourceKey key)) return false;
    return prefix == key.prefix && Arrays.equals(bytes, key.bytes);
  }

  @Override public int hashCode() { return hash; }

  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      if (i > 0) sb.append(bytes.length == 4 ? '.' : ':');
      if (bytes.length == 4) sb.append(bytes[i] & 0xFF);
      else sb.append(String.format("%02x", bytes[i]));
    }
    return sb.append('/').append(prefix).toString();
  }
}

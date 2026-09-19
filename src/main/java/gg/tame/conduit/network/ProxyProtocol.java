// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * The HAProxy PROXY protocol, v1 and v2, read off the front of a connection.
 *
 * <p>A reverse proxy -- TCPShield, HAProxy, a load balancer -- terminates the player's TCP
 * connection and opens its own to Conduit, so every player would otherwise arrive wearing that
 * service's address: one address for the whole network, which is the address the throttle counts,
 * the bot filter judges, bans match and the connection log prints. The PROXY header is how the
 * service states whose connection this really is, in the bytes before the Minecraft handshake.
 *
 * <p><b>The header is not authenticated.</b> Anything that can open a TCP connection to the
 * listener can claim any address it likes and walk through a ban or a rate limit wearing it. This
 * is why the setting is off by default and why, when it is on, the listener must be reachable only
 * from the service that sends the header -- a firewall rule, not a matter of configuration.
 *
 * <p>Both versions are accepted because there is no negotiation: the sender picks, and v1 is still
 * what several services emit. v2's LOCAL command is the health check a load balancer makes on its
 * own behalf rather than for a client, and is reported as such.
 */
public final class ProxyProtocol {

  /** v2's fixed 12-byte preamble. v1's is the ASCII "PROXY ", whose first byte it shares. */
  private static final byte[] V2_SIGNATURE = {
      0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
  private static final byte[] V1_SIGNATURE = "PROXY ".getBytes(StandardCharsets.US_ASCII);
  /** v1 is a single line and the spec caps it at 107 bytes including the CRLF. */
  private static final int V1_MAX = 107;

  private ProxyProtocol() {}

  /**
   * The address the header names, or empty when the header says this connection is the sender's
   * own (v2 LOCAL) and the socket's address is the truthful one.
   *
   * @throws IOException the stream did not begin with a header, or began with a malformed one;
   *     the connection is not usable afterwards either way, because the reader cannot know how
   *     many bytes to skip to reach the Minecraft handshake
   */
  public static java.util.Optional<InetSocketAddress> read(InputStream in) throws IOException {
    byte[] first = readFully(in, V2_SIGNATURE.length);
    if (java.util.Arrays.equals(first, V2_SIGNATURE)) return readVersion2(in);
    for (int i = 0; i < V1_SIGNATURE.length; i++) {
      if (first[i] != V1_SIGNATURE[i]) throw new IOException("not a PROXY protocol header");
    }
    // The v1 signature is shorter than v2's, so the 12 bytes already read run into the line.
    return readVersion1(in, new String(first, V1_SIGNATURE.length, first.length - V1_SIGNATURE.length,
        StandardCharsets.US_ASCII));
  }

  /** {@code PROXY TCP4 192.0.2.1 198.51.100.7 56324 25565\r\n} */
  private static java.util.Optional<InetSocketAddress> readVersion1(InputStream in, String start)
      throws IOException {
    StringBuilder line = new StringBuilder(start);
    while (line.indexOf("\r\n") < 0) {
      if (line.length() > V1_MAX) throw new IOException("PROXY v1 header is too long");
      int next = in.read();
      if (next < 0) throw new EOFException("PROXY v1 header was cut short");
      line.append((char) next);
    }
    String[] parts = line.substring(0, line.indexOf("\r\n")).trim().split(" ");
    // "UNKNOWN" is the sender saying it cannot describe the connection, and carries no addresses
    // worth trusting even when it pads the line out with some.
    if (parts.length > 0 && parts[0].equals("UNKNOWN")) return java.util.Optional.empty();
    if (parts.length != 5) throw new IOException("malformed PROXY v1 header");
    if (!parts[0].equals("TCP4") && !parts[0].equals("TCP6")) {
      throw new IOException("unsupported PROXY v1 family " + parts[0]);
    }
    try {
      return java.util.Optional.of(new InetSocketAddress(literalAddress(parts[1]),
          Integer.parseInt(parts[3])));
    } catch (NumberFormatException malformed) {
      throw new IOException("malformed PROXY v1 port", malformed);
    }
  }

  private static java.util.Optional<InetSocketAddress> readVersion2(InputStream in)
      throws IOException {
    int versionAndCommand = readByte(in);
    int familyAndProtocol = readByte(in);
    int length = (readByte(in) << 8) | readByte(in);
    byte[] body = readFully(in, length);
    if ((versionAndCommand & 0xF0) != 0x20) throw new IOException("unsupported PROXY protocol version");
    int command = versionAndCommand & 0x0F;
    // LOCAL: the sender's own connection, a health check rather than a player. PROXY is 0x01;
    // anything else is not defined by the spec.
    if (command == 0x00) return java.util.Optional.empty();
    if (command != 0x01) throw new IOException("unsupported PROXY v2 command " + command);
    int family = (familyAndProtocol & 0xF0) >> 4;
    // 1 is AF_INET, 2 is AF_INET6. AF_UNIX and UNSPEC carry nothing this proxy can use, and are
    // treated as "no claim" rather than as an error: the socket's own address still applies.
    int addressBytes = family == 1 ? 4 : family == 2 ? 16 : 0;
    if (addressBytes == 0) return java.util.Optional.empty();
    if (body.length < addressBytes * 2 + 4) throw new IOException("truncated PROXY v2 header");
    byte[] source = java.util.Arrays.copyOfRange(body, 0, addressBytes);
    int port = ((body[addressBytes * 2] & 0xFF) << 8) | (body[addressBytes * 2 + 1] & 0xFF);
    // Trailing bytes are the optional TLVs, already consumed as part of the declared length.
    return java.util.Optional.of(new InetSocketAddress(InetAddress.getByAddress(source), port));
  }

  /**
   * An address from the header, parsed as a literal and never looked up. {@code getByName} would
   * resolve anything that is not one, which would put a DNS round trip on the accept path, driven
   * by whatever sent the header -- so the shape is checked first and only literals reach it.
   */
  private static InetAddress literalAddress(String text) throws IOException {
    // A literal, never a name: getByName would resolve anything else, putting a DNS round trip
    // on the accept path, driven by whatever sent the header.
    boolean literal = !text.isEmpty() && text.chars().allMatch(
        c -> c == '.' || c == ':' || Character.digit(c, 16) >= 0);
    if (!literal) throw new IOException("malformed PROXY v1 address " + text);
    InetAddress parsed = InetAddress.getByName(text);
    if (!(parsed instanceof Inet4Address) && !(parsed instanceof Inet6Address)) {
      throw new IOException("malformed PROXY v1 address " + text);
    }
    return parsed;
  }

  private static int readByte(InputStream in) throws IOException {
    int read = in.read();
    if (read < 0) throw new EOFException("PROXY header was cut short");
    return read;
  }

  private static byte[] readFully(InputStream in, int count) throws IOException {
    byte[] buffer = new byte[count];
    int read = 0;
    while (read < count) {
      int step = in.read(buffer, read, count - read);
      if (step < 0) throw new EOFException("PROXY header was cut short");
      read += step;
    }
    return buffer;
  }
}

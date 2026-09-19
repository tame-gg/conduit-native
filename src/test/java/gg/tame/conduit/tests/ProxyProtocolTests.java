// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.tests;

import gg.tame.conduit.network.ProxyProtocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The PROXY protocol header a reverse proxy such as TCPShield puts in front of a connection. */
final class ProxyProtocolTests {

  private static final byte[] V2_SIGNATURE = {
      0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

  static void run() throws Exception {
    version1IsRead();
    version1LeavesTheHandshakeBehindIt();
    version1UnknownMakesNoClaim();
    version2IsRead();
    version2Ipv6IsRead();
    version2LocalIsAHealthCheck();
    version2TrailingTlvsAreSkipped();
    rubbishIsRefused();
    truncatedIsRefused();
    System.out.println("ProxyProtocolTests OK");
  }

  private static void version1IsRead() throws Exception {
    InetSocketAddress client = read("PROXY TCP4 203.0.113.7 198.51.100.2 56324 25565\r\n").orElseThrow();
    require(client.getAddress().getHostAddress().equals("203.0.113.7"), "v1 address: " + client);
    require(client.getPort() == 56324, "v1 port: " + client);
  }

  /** The bytes after the header are the Minecraft handshake, and must still be there to read. */
  private static void version1LeavesTheHandshakeBehindIt() throws Exception {
    byte[] stream = ("PROXY TCP4 203.0.113.7 198.51.100.2 56324 25565\r\nHANDSHAKE")
        .getBytes(StandardCharsets.US_ASCII);
    ByteArrayInputStream in = new ByteArrayInputStream(stream);
    ProxyProtocol.read(in);
    require(new String(in.readAllBytes(), StandardCharsets.US_ASCII).equals("HANDSHAKE"),
        "v1 consumed too much or too little");
  }

  private static void version1UnknownMakesNoClaim() throws Exception {
    require(read("PROXY UNKNOWN\r\n").isEmpty(), "UNKNOWN should name nobody");
  }

  private static void version2IsRead() throws Exception {
    InetSocketAddress client = read(version2(0x21, 0x11,
        new byte[] {(byte) 203, 0, 113, 7}, new byte[] {(byte) 198, 51, 100, 2}, 56324, 25565,
        new byte[0])).orElseThrow();
    require(client.getAddress().getHostAddress().equals("203.0.113.7"), "v2 address: " + client);
    require(client.getPort() == 56324, "v2 port: " + client);
  }

  private static void version2Ipv6IsRead() throws Exception {
    byte[] source = new byte[16];
    source[0] = 0x20; source[1] = 0x01; source[15] = 0x07;
    InetSocketAddress client = read(version2(0x21, 0x21, source, new byte[16], 40000, 25565,
        new byte[0])).orElseThrow();
    require(client.getPort() == 40000, "v2 IPv6 port: " + client);
    require(client.getAddress().getAddress().length == 16, "v2 IPv6 address: " + client);
  }

  private static void version2LocalIsAHealthCheck() throws Exception {
    // Command LOCAL: the sender opened this one for itself, so the socket's address is the honest one.
    require(read(version2(0x20, 0x11, new byte[4], new byte[4], 1, 2, new byte[0])).isEmpty(),
        "LOCAL should name nobody");
  }

  /** The length covers the optional TLVs too, and everything up to it belongs to the header. */
  private static void version2TrailingTlvsAreSkipped() throws Exception {
    byte[] stream = concat(version2(0x21, 0x11, new byte[] {(byte) 203, 0, 113, 7},
            new byte[] {(byte) 198, 51, 100, 2}, 56324, 25565, new byte[] {0x03, 0x00, 0x04, 1, 2, 3, 4}),
        "HANDSHAKE".getBytes(StandardCharsets.US_ASCII));
    ByteArrayInputStream in = new ByteArrayInputStream(stream);
    InetSocketAddress client = ProxyProtocol.read(in).orElseThrow();
    require(client.getPort() == 56324, "v2 with TLVs: " + client);
    require(new String(in.readAllBytes(), StandardCharsets.US_ASCII).equals("HANDSHAKE"),
        "v2 did not skip its TLVs");
  }

  private static void rubbishIsRefused() {
    refused("GET / HTTP/1.1\r\nHost: x\r\n\r\n", "a plain HTTP request");
    refused("PROXY TCP4 nonsense\r\n", "a malformed v1 line");
    refused("PROXY TCP4 not.a.host 198.51.100.2 1 2\r\n", "a hostname where an address belongs");
  }

  private static void truncatedIsRefused() {
    refused("PROXY TCP4 203.0.113.7 198.51.100.2 56324", "a v1 line with no CRLF");
  }

  private static void refused(String header, String what) {
    try {
      read(header);
      throw new AssertionError("accepted " + what);
    } catch (IOException expected) {
      // What a refusal looks like: the caller drops the connection.
    }
  }

  private static Optional<InetSocketAddress> read(String header) throws IOException {
    return read(header.getBytes(StandardCharsets.US_ASCII));
  }

  private static Optional<InetSocketAddress> read(byte[] header) throws IOException {
    return ProxyProtocol.read(new ByteArrayInputStream(header));
  }

  private static byte[] version2(int versionAndCommand, int familyAndProtocol, byte[] source,
      byte[] destination, int sourcePort, int destinationPort, byte[] tlvs) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(source);
    body.write(destination);
    body.write(sourcePort >> 8);
    body.write(sourcePort & 0xFF);
    body.write(destinationPort >> 8);
    body.write(destinationPort & 0xFF);
    body.write(tlvs);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(V2_SIGNATURE);
    out.write(versionAndCommand);
    out.write(familyAndProtocol);
    out.write(body.size() >> 8);
    out.write(body.size() & 0xFF);
    out.write(body.toByteArray());
    return out.toByteArray();
  }

  private static byte[] concat(byte[] first, byte[] second) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(first);
    out.write(second);
    return out.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}

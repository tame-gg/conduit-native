// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import gg.tame.conduit.api.event.proxy.ServerQueryEvent;
import gg.tame.conduit.log.ConduitLog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The GameSpy 4 query protocol server-list sites and status bots use, over UDP, as the published
 * Minecraft query description has it: a handshake hands out a challenge token, and a stat request
 * carrying it gets the basic stat, or the full one (players and plugins) when it is padded to 15
 * bytes. Off unless {@code [query] enabled}. One platform thread answers one datagram at a time.
 *
 * <p>The token is a keyed hash of the asker's address and the current 30-second window, so nothing
 * is stored per asker, and a stat (the one answer larger than its request) only goes to an address
 * that received its token, never to a spoofed one.
 */
public final class QueryResponder implements AutoCloseable {
  /** Builds the answer to one stat request. */
  public interface Answer { ServerQueryEvent.Response answer(boolean full, InetAddress querier); }

  private static final int HANDSHAKE = 9, STAT = 0;
  private static final long WINDOW_MS = 30_000;
  private final DatagramSocket socket;
  private final Answer answer;
  private final Mac mac;

  private QueryResponder(DatagramSocket socket, Answer answer) throws GeneralSecurityException {
    this.socket = socket;
    this.answer = answer;
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
  }

  public static QueryResponder start(InetSocketAddress address, Answer answer) throws IOException {
    DatagramSocket socket = new DatagramSocket(address);
    QueryResponder responder;
    try { responder = new QueryResponder(socket, answer); }
    catch (GeneralSecurityException missing) { socket.close(); throw new IOException("no HmacSHA256 in this JDK", missing); }
    // Platform, not virtual: a blocking datagram receive, and JDK-8334574 (see SocketThreads).
    Thread.ofPlatform().daemon().name("conduit-query").start(responder::serve);
    return responder;
  }
  public int port() { return socket.getLocalPort(); }

  private void serve() {
    byte[] buffer = new byte[64];
    while (!socket.isClosed()) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
        byte[] reply = reply(ByteBuffer.wrap(packet.getData(), 0, packet.getLength()), packet.getAddress());
        if (reply != null) socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
      } catch (IOException | RuntimeException failed) {
        if (!socket.isClosed()) ConduitLog.debug("query from " + packet.getAddress() + " not answered: " + failed);
      }
    }
  }

  /** The answer to one datagram, or null for one that gets none. */
  private byte[] reply(ByteBuffer request, InetAddress from) throws IOException {
    if (request.remaining() < 7 || request.get() != (byte) 0xFE || request.get() != (byte) 0xFD) return null;
    int type = request.get();
    int session = request.getInt() & 0x0F0F0F0F;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(type);
    out.write(ByteBuffer.allocate(4).putInt(session).array());
    if (type == HANDSHAKE) {
      text(out, Integer.toString(token(from, System.currentTimeMillis() / WINDOW_MS)));
      return out.toByteArray();
    }
    if (type != STAT || request.remaining() < 4) return null;
    int token = request.getInt();
    long window = System.currentTimeMillis() / WINDOW_MS;
    if (token != token(from, window) && token != token(from, window - 1)) return null;
    boolean full = request.remaining() >= 4;
    ServerQueryEvent.Response response = answer.answer(full, from);
    if (!full) {
      text(out, response.motd()); text(out, "SMP"); text(out, response.map());
      text(out, Integer.toString(response.onlinePlayers())); text(out, Integer.toString(response.maxPlayers()));
      out.write(response.port() & 0xFF); out.write((response.port() >> 8) & 0xFF);
      text(out, response.host());
      return out.toByteArray();
    }
    out.write("splitnum\0".getBytes(StandardCharsets.US_ASCII)); out.write(0x80); out.write(0);
    String plugins = response.plugins().stream()
        .map(plugin -> plugin.version() == null ? plugin.name() : plugin.name() + " " + plugin.version())
        .collect(Collectors.joining("; "));
    String[] pairs = {"hostname", response.motd(), "gametype", "SMP", "game_id", "MINECRAFT", "version", response.gameVersion(),
        "plugins", plugins.isEmpty() ? response.proxyVersion() : response.proxyVersion() + ": " + plugins, "map", response.map(),
        "numplayers", Integer.toString(response.onlinePlayers()), "maxplayers", Integer.toString(response.maxPlayers()),
        "hostport", Integer.toString(response.port()), "hostip", response.host()};
    for (String value : pairs) text(out, value);
    out.write(0);
    out.write(1); out.write("player_\0".getBytes(StandardCharsets.US_ASCII)); out.write(0);
    for (String player : response.players()) text(out, player);
    out.write(0);
    return out.toByteArray();
  }
  private static void text(ByteArrayOutputStream out, String value) throws IOException {
    out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    out.write(0);
  }
  /** Only the serving thread calls this, so the one Mac is never shared. */
  private int token(InetAddress from, long window) {
    mac.update(from.getAddress());
    byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(window).array());
    return ByteBuffer.wrap(hash).getInt() & 0x7FFFFFFF;
  }

  @Override public void close() { socket.close(); }
}

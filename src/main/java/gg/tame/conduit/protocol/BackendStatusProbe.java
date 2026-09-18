// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.protocol;

import gg.tame.conduit.api.event.proxy.ServerListPingEvent;
import gg.tame.conduit.api.server.ServerAvailability;
import gg.tame.conduit.api.server.ServerStatus;
import gg.tame.conduit.api.text.Text;
import gg.tame.conduit.network.SocketThreads;
import gg.tame.conduit.protocol.text.ComponentCodec;
import gg.tame.conduit.text.TextCodec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Status ping of a backend: health and routing learn its advertised protocol and player counts from
 * it, and plugins its whole answer. Not a translation layer.
 */
public final class BackendStatusProbe {
  /**
   * The most a vanilla client accepts: a status answer is a string of at most 32767 characters,
   * which UTF-8 can stretch to three bytes each. A backend declaring more is refused before anything
   * is allocated for it.
   */
  private static final int MAX_JSON_BYTES = 3 * 32767;
  /** A handshake that names no client version: the backend answers with its own. */
  public static final int ANY_PROTOCOL = -1;
  /**
   * Pings plugins ask for. Each blocks a socket thread for up to its timeout, so they are bounded:
   * enough that a plugin pinging every backend at once is not queued behind itself, few enough
   * that a flood of pings to dead backends cannot pile up threads. The queue is bounded too, and a
   * ping it cannot take is answered offline at once.
   */
  // ponytail: one blocking thread per in-flight ping; a non-blocking channel selector if 32 is ever too few.
  private static final ThreadPoolExecutor PINGS = pool(32, 1024);

  private BackendStatusProbe() {}

  public record Advertisement(int protocol, String name, int onlinePlayers, int maxPlayers, long latencyMillis) {
    public Advertisement {
      if (name == null) name = "unknown";
    }
  }

  /** Everything a status answer says, beyond the {@link Advertisement} health and routing use. */
  private record Answer(Advertisement advertisement, Text description, Optional<String> favicon,
                        List<ServerListPingEvent.SamplePlayer> samplePlayers) {
    ServerStatus toStatus(String server, Instant when) {
      Advertisement ad = advertisement;
      return new ServerStatus(server, ServerAvailability.ONLINE, OptionalInt.of(ad.protocol()), ad.name(),
          count(ad.onlinePlayers()), count(ad.maxPlayers()), OptionalLong.of(ad.latencyMillis()), when,
          description, favicon, samplePlayers);
    }
    private static OptionalInt count(int value) { return value >= 0 ? OptionalInt.of(value) : OptionalInt.empty(); }
  }

  /** The health and routing probe: the backend's own version, asked for through its configured address. */
  public static Optional<Advertisement> probe(InetSocketAddress address, int timeoutMillis) {
    try {
      return Optional.of(query(address, null, ANY_PROTOCOL, deadline(timeoutMillis)).advertisement());
    } catch (IOException exception) {
      return Optional.empty();
    }
  }

  /**
   * Asks for the status on a ping thread and completes there, never exceptionally: a backend that
   * cannot be reached, does not answer before {@code timeoutMillis} is up, or answers with something
   * that is not a status gets an offline status. The time spent waiting for a free ping thread
   * counts against the timeout.
   */
  public static CompletableFuture<ServerStatus> ping(String server, InetSocketAddress address, String virtualHost,
                                                     int protocol, int timeoutMillis) {
    CompletableFuture<ServerStatus> result = new CompletableFuture<>();
    long deadline = deadline(timeoutMillis);
    try {
      PINGS.execute(() -> {
        try {
          result.complete(query(address, virtualHost, protocol, deadline).toStatus(server, Instant.now()));
        } catch (IOException unanswered) {
          result.complete(ServerStatus.offline(server, Instant.now()));
        } catch (RuntimeException | Error failure) {
          result.complete(ServerStatus.offline(server, Instant.now()));
          throw failure;
        }
      });
    } catch (RejectedExecutionException busy) {
      result.complete(ServerStatus.offline(server, Instant.now()));
    }
    return result;
  }

  /**
   * Connects, asks and reads the whole answer before {@code deadline} ({@link System#nanoTime()}).
   * With no {@code virtualHost} the handshake names the host of the configured address, as the
   * health probe always has.
   */
  private static Answer query(InetSocketAddress address, String virtualHost, int protocol, long deadline) throws IOException {
    long started = System.nanoTime();
    try (Socket socket = new Socket()) {
      socket.connect(address, remaining(deadline));
      String host = virtualHost == null || virtualHost.isBlank() ? address.getHostString() : virtualHost;
      Handshake handshake = new Handshake(protocol, host, address.getPort(), 1);
      MinecraftFrames.write(socket.getOutputStream(), handshake.encode());
      MinecraftFrames.write(socket.getOutputStream(), new byte[] {0});
      byte[] response = MinecraftFrames.read(untilDeadline(socket, deadline), MAX_JSON_BYTES + 4);
      if (PlayPackets.packetId(response) != 0) throw new IOException("not a status response");
      String json = MinecraftInput.string(new DataInputStream(new ByteArrayInputStream(Arrays.copyOfRange(response, 1, response.length))), MAX_JSON_BYTES);
      long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
      return answer(json, latency).orElseThrow(() -> new IOException("malformed status response"));
    }
  }

  public static Optional<Advertisement> parse(String json) {
    return parse(json, -1);
  }

  public static Optional<Advertisement> parse(String json, long latencyMillis) {
    return answer(json, latencyMillis).map(Answer::advertisement);
  }

  /** Empty unless the answer is a JSON object naming a protocol, as every status answer does. */
  private static Optional<Answer> answer(String json, long latencyMillis) {
    try {
      if (!(ComponentCodec.parseJson(json) instanceof Map<?, ?> root)) return Optional.empty();
      if (!(root.get("version") instanceof Map<?, ?> version) || !(version.get("protocol") instanceof Number protocol)) return Optional.empty();
      String name = version.get("name") instanceof String named ? named : null;
      int online = -1;
      int max = -1;
      List<ServerListPingEvent.SamplePlayer> sample = new ArrayList<>();
      if (root.get("players") instanceof Map<?, ?> players) {
        if (players.get("online") instanceof Number number) online = number.intValue();
        if (players.get("max") instanceof Number number) max = number.intValue();
        if (players.get("sample") instanceof List<?> entries) {
          for (Object entry : entries) {
            if (entry instanceof Map<?, ?> player && player.get("name") instanceof String playerName && player.get("id") instanceof String id) {
              try { sample.add(new ServerListPingEvent.SamplePlayer(playerName, UUID.fromString(id))); }
              catch (IllegalArgumentException notAUuid) { }
            }
          }
        }
      }
      Object description = root.get("description");
      // Only an icon a client would draw: anything else in that field is not an icon.
      Optional<String> favicon = root.get("favicon") instanceof String icon && icon.startsWith("data:image/png;base64,")
          ? Optional.of(icon) : Optional.empty();
      return Optional.of(new Answer(new Advertisement(protocol.intValue(), name, online, max, latencyMillis),
          description == null ? Text.empty() : TextCodec.fromTree(description), favicon, sample));
    } catch (StackOverflowError nested) {
      // A backend can nest a description as deep as its answer is long; the parsers recurse.
      return Optional.empty();
    }
  }

  public static String describe(Advertisement advertisement) {
    TranslationSupport support = ProtocolCompatibility.between(advertisement.protocol(), advertisement.protocol());
    String codec = ProtocolDefinition.hasCodec(advertisement.protocol()) ? "DIRECT codec" : "no codec yet";
    return advertisement.name() + " protocol " + advertisement.protocol() + " (" + ProtocolVersion.display(advertisement.protocol()) + ", " + codec + ", translation=" + support + ")";
  }

  private static long deadline(int timeoutMillis) {
    return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
  }

  private static int remaining(long deadline) throws SocketTimeoutException {
    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (left <= 0) throw new SocketTimeoutException("no status answer in time");
    return (int) Math.min(Integer.MAX_VALUE, left);
  }

  /**
   * The socket's input, each read bounded by what is left before the deadline. A plain read timeout
   * restarts with every byte, so a backend trickling its answer could hold the ping for as long as
   * it liked.
   */
  private static InputStream untilDeadline(Socket socket, long deadline) throws IOException {
    return new FilterInputStream(socket.getInputStream()) {
      @Override public int read() throws IOException {
        socket.setSoTimeout(remaining(deadline));
        return super.read();
      }
      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        socket.setSoTimeout(remaining(deadline));
        return super.read(buffer, offset, length);
      }
    };
  }

  private static ThreadPoolExecutor pool(int threads, int queued) {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(queued), SocketThreads.factory());
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }
}

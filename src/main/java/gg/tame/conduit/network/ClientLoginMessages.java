// SPDX-License-Identifier: GPL-3.0-or-later
package gg.tame.conduit.network;

import gg.tame.conduit.login.LoginPluginRequest;
import gg.tame.conduit.protocol.ConnectionState;
import gg.tame.conduit.protocol.MinecraftInput;
import gg.tame.conduit.protocol.PacketDirection;
import gg.tame.conduit.protocol.PacketKind;
import gg.tame.conduit.protocol.ProtocolDefinition;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Login Plugin Requests plugins send a client while it logs in to the proxy, and the client's answers.
 *
 * <p>A request is only queued when a plugin makes it: a listener runs in the middle of the proxy's
 * own exchange with the client (before encryption, for PlayerPreLoginEvent), where a packet of the
 * plugin's would land between two of the proxy's. {@link #exchange} sends what is queued at the
 * points where the login stops for it, and reads the answers there; {@link #close} ends it once the
 * login is decided, and a request after that is refused.
 */
public final class ClientLoginMessages {
  /**
   * The first message id. The ids of a backend's own queries, which the proxy relays to this client
   * later in the same login, count up from small numbers; these stay clear of them.
   */
  private static final int FIRST_ID = 0x4C50_0000;
  /** The most data the protocol lets a Login Plugin Request carry. */
  public static final int MAX_DATA_BYTES = 1 << 20;
  private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  private record Request(LoginPluginRequest packet, CompletableFuture<byte[]> answer) {}

  private final ProtocolDefinition protocol;
  // Guarded by this: plugins queue from their own threads while the connection's thread exchanges.
  private final List<Request> queued = new ArrayList<>();
  private final Map<Integer, CompletableFuture<byte[]>> unanswered = new LinkedHashMap<>();
  private int next = FIRST_ID;
  private boolean closed;

  public ClientLoginMessages(ProtocolDefinition protocol) { this.protocol = protocol; }

  /**
   * Queues a request; the future completes with the client's answer, or null when it did not
   * understand the channel.
   *
   * @throws IllegalStateException for a client before 1.13, which has no such packet, or once the login is decided
   * @throws IllegalArgumentException for a channel that is not a namespaced key, or data over 1 MiB
   */
  public synchronized CompletableFuture<byte[]> send(String channel, byte[] data) {
    if (!protocol.defines(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST)) {
      throw new IllegalStateException("login plugin messages need a 1.13 or later client; this one is " + protocol.version().displayName());
    }
    if (closed) throw new IllegalStateException("the login has been decided; no more login plugin messages can be sent");
    String full = channel == null || channel.indexOf(':') >= 0 ? channel : "minecraft:" + channel;
    if (full == null || !CHANNEL.matcher(full).matches()) throw new IllegalArgumentException("not a namespaced channel: " + channel);
    if (data == null) throw new IllegalArgumentException("data is required");
    if (data.length > MAX_DATA_BYTES) throw new IllegalArgumentException("login plugin message data over 1 MiB: " + data.length + " bytes");
    CompletableFuture<byte[]> answer = new CompletableFuture<>();
    queued.add(new Request(new LoginPluginRequest(next++, full, data.clone()), answer));
    return answer;
  }

  /**
   * Sends every queued request, in order, and reads the client's answers until none is owed; a
   * request queued meanwhile goes out in the same exchange. What the client sends here can only be an
   * answer, and one to a request of this exchange.
   */
  public void exchange(PacketTransport transport, int maximumFrameBytes) throws IOException {
    while (true) {
      List<Request> batch;
      synchronized (this) {
        batch = List.copyOf(queued);
        queued.clear();
        for (Request request : batch) unanswered.put(request.packet().messageId(), request.answer());
        if (unanswered.isEmpty()) return;
      }
      int requestId = protocol.id(ConnectionState.LOGIN, PacketDirection.SERVER_TO_CLIENT, PacketKind.LOGIN_PLUGIN_REQUEST);
      for (Request request : batch) transport.write(request.packet().encode(requestId));
      byte[] packet = transport.read(maximumFrameBytes);
      int messageId;
      byte[] data;
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
        int id = MinecraftInput.varInt(input);
        if (!protocol.is(ConnectionState.LOGIN, PacketDirection.CLIENT_TO_SERVER, id, PacketKind.LOGIN_PLUGIN_RESPONSE)) {
          throw new IOException("client sent packet 0x" + Integer.toHexString(id) + " while answering login plugin messages");
        }
        messageId = MinecraftInput.varInt(input);
        data = input.readBoolean() ? input.readAllBytes() : null;
      }
      CompletableFuture<byte[]> answer;
      synchronized (this) { answer = unanswered.remove(messageId); }
      if (answer == null) throw new IOException("login plugin response answers nothing the proxy asked (" + messageId + ")");
      answer.complete(data);
    }
  }

  /** No request may follow; one never sent or never answered fails, as the login it belonged to is over. */
  public void close() {
    List<CompletableFuture<byte[]>> abandoned = new ArrayList<>();
    synchronized (this) {
      if (closed) return;
      closed = true;
      for (Request request : queued) abandoned.add(request.answer());
      abandoned.addAll(unanswered.values());
      queued.clear();
      unanswered.clear();
    }
    for (CompletableFuture<byte[]> answer : abandoned) answer.completeExceptionally(new IOException("the login ended before the client answered"));
  }
}
